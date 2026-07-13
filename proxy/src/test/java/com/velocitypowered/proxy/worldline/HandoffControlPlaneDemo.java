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

import java.util.UUID;

/**
 * Scriptable placeholder prepare-abort round trip for M2.
 */
public final class HandoffControlPlaneDemo {

  private HandoffControlPlaneDemo() {
  }

  /**
   * Runs a prepare-abort round trip and exits non-zero on failure.
   */
  public static void main(final String[] args) {
    UUID player = UUID.fromString("00000000-0000-0000-0000-000000000021");
    UUID client = UUID.fromString("00000000-0000-0000-0000-000000000022");
    UUID transfer = UUID.fromString("00000000-0000-0000-0000-000000000023");

    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(player, client, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    ControlEnvelope envelope = new ControlEnvelope(HandoffControlPlane.PROTOCOL_VERSION, transfer,
        player, "server-a", "server-b", "west", "east", 0, 0, 0, 0);

    require(LivePlayerSessionStore.Status.APPLIED, control.prepare(envelope).status(), "prepare");
    require(HandoffPhase.DESTINATION_READY, sessions.get(player).orElseThrow().handoffPhase(),
        "prepared phase");
    require(LivePlayerSessionStore.Status.APPLIED, control.abort(envelope).status(), "abort");
    require(HandoffPhase.ACTIVE_SOURCE, sessions.get(player).orElseThrow().handoffPhase(),
        "aborted phase");

    System.out.println("prepare-abort ok");
  }

  private static void require(final Object expected, final Object actual, final String label) {
    if (!expected.equals(actual)) {
      throw new IllegalStateException(label + ": expected " + expected + ", got " + actual);
    }
  }
}
