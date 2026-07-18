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

/**
 * Scriptable M3 prepare-abort round trip against the two Paper control endpoints.
 */
public final class HandoffControlPlaneDemo {

  private HandoffControlPlaneDemo() {
  }

  /**
   * Runs a prepare-abort round trip and exits non-zero on failure.
   */
  public static void main(final String[] args) throws Exception {
    UUID player = UUID.fromString("00000000-0000-0000-0000-000000000021");
    UUID client = UUID.fromString("00000000-0000-0000-0000-000000000022");
    UUID transfer = UUID.fromString("00000000-0000-0000-0000-000000000023");

    LivePlayerSessionStore sessions = new LivePlayerSessionStore((name, before, after) ->
        System.out.printf("transition transfer_id=%s previous=%s next=%s epoch=%d%n",
            before.activeTransferId() == null ? transfer : before.activeTransferId(),
            before.handoffPhase(), after.handoffPhase(), after.playerSessionEpoch()));
    sessions.putActive(player, client, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    control.configure(StaticPartitionMap.read(Path.of(args[0])));
    ControlEnvelope envelope = new ControlEnvelope(HandoffControlPlane.PROTOCOL_VERSION, transfer,
        player, client, "server-a", "server-b", "west", "east", 1, 1, 0, 0, 0);

    System.out.printf("initial phase=%s authority=%s epoch=%d transfer=%s%n",
        sessions.get(player).orElseThrow().handoffPhase(),
        sessions.get(player).orElseThrow().authoritativeServerId(),
        sessions.get(player).orElseThrow().playerSessionEpoch(),
        sessions.get(player).orElseThrow().activeTransferId());
    System.out.printf("prepare sent transfer_id=%s source=server-a destination=server-b%n",
        transfer);
    PrepareTarget target = control.prepareTarget("WorldlineDemo", 8, 64, 0);
    require(LivePlayerSessionStore.Status.APPLIED, control.prepare(envelope, target).status(),
        "prepare");
    require(HandoffPhase.DESTINATION_READY, sessions.get(player).orElseThrow().handoffPhase(),
        "prepared phase");
    System.out.println("destination loaded target halo and prepared a non-authoritative player");
    System.out.println("destination acknowledgement received phase=DESTINATION_READY");
    System.out.printf("abort requested transfer_id=%s%n", transfer);
    require(LivePlayerSessionStore.Status.APPLIED, control.abort(envelope).status(), "abort");
    System.out.printf("abort sent and acknowledged transfer_id=%s%n", transfer);
    require(HandoffPhase.ACTIVE_SOURCE, sessions.get(player).orElseThrow().handoffPhase(),
        "aborted phase");
    LivePlayerSession finalSession = sessions.get(player).orElseThrow();
    require("server-a", finalSession.authoritativeServerId(), "authority");
    require(0L, finalSession.playerSessionEpoch(), "epoch");
    require(null, finalSession.activeTransferId(), "cleared transfer");

    System.out.printf("final phase=%s authority=%s epoch=%d transfer=%s%n",
        finalSession.handoffPhase(), finalSession.authoritativeServerId(),
        finalSession.playerSessionEpoch(), finalSession.activeTransferId());
    System.out.println("prepare-abort ok: authority unchanged, epoch unchanged, transfer cleared");
  }

  private static void require(final Object expected, final Object actual, final String label) {
    if (!java.util.Objects.equals(expected, actual)) {
      throw new IllegalStateException(label + ": expected " + expected + ", got " + actual);
    }
  }
}
