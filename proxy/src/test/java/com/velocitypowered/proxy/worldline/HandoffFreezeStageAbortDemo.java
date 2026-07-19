/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.worldline;

import java.nio.file.Path;
import java.util.UUID;

/** Live M4 freeze, exact snapshot stage, and pre-commit abort acceptance probe. */
public final class HandoffFreezeStageAbortDemo {

  private HandoffFreezeStageAbortDemo() {
  }

  public static void main(final String[] args) throws Exception {
    UUID player = UUID.fromString(args[1]);
    String playerName = args[2];
    UUID client = UUID.randomUUID();
    UUID transfer = UUID.randomUUID();
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(player, client, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    control.configure(StaticPartitionMap.read(Path.of(args[0])));
    ControlEnvelope envelope = new ControlEnvelope(HandoffControlPlane.PROTOCOL_VERSION, transfer,
        player, client, "server-a", "server-b", "west", "east", 1, 1, 0, 1, 0);
    PrepareTarget target = control.prepareTarget(playerName, 8, 64, 0);

    requireApplied(control.prepare(envelope, target), "prepare");
    HandoffControlPlane.SnapshotResult frozen = control.freezeSource(envelope);
    requireApplied(frozen.transition(), "freeze");
    if (frozen.snapshot().length == 0) {
      throw new IllegalStateException("freeze returned an empty snapshot");
    }
    requireApplied(control.stageSnapshot(envelope, frozen.snapshot()), "stage");
    requireApplied(control.abort(envelope), "abort");

    LivePlayerSession session = sessions.get(player).orElseThrow();
    if (!session.authoritativeServerId().equals("server-a")
        || session.playerSessionEpoch() != 0
        || session.handoffPhase() != HandoffPhase.ACTIVE_SOURCE
        || session.activeTransferId() != null) {
      throw new IllegalStateException("pre-commit abort did not restore source authority");
    }
    System.out.printf("freeze-stage-abort ok: player=%s transfer=%s snapshot_bytes=%d "
            + "authority=%s epoch=%d%n", player, transfer, frozen.snapshot().length,
        session.authoritativeServerId(), session.playerSessionEpoch());
  }

  private static void requireApplied(final LivePlayerSessionStore.TransitionResult result,
      final String phase) {
    if (result.status() != LivePlayerSessionStore.Status.APPLIED
        && result.status() != LivePlayerSessionStore.Status.ALREADY_APPLIED) {
      throw new IllegalStateException(phase + " failed: " + result.status());
    }
  }
}
