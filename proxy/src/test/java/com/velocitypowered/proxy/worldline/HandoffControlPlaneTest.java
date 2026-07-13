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

import static com.velocitypowered.proxy.worldline.LivePlayerSessionStore.Status.ALREADY_APPLIED;
import static com.velocitypowered.proxy.worldline.LivePlayerSessionStore.Status.APPLIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests for the placeholder Worldline control coordinator.
 */
public class HandoffControlPlaneTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000011");
  private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000012");
  private static final UUID TRANSFER = UUID.fromString("00000000-0000-0000-0000-000000000013");

  @Test
  void scriptedPrepareAbortRoundTrip() {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);

    assertEquals(APPLIED, control.prepare(envelope()).status());
    assertEquals(HandoffPhase.DESTINATION_READY, sessions.get(PLAYER).orElseThrow().handoffPhase());
    assertEquals(APPLIED, control.abort(envelope()).status());
    assertEquals(HandoffPhase.ACTIVE_SOURCE, sessions.get(PLAYER).orElseThrow().handoffPhase());
    assertEquals(ALREADY_APPLIED, control.abort(envelope()).status());
  }

  @Test
  void scriptedCommitRoundTrip() {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    ControlEnvelope envelope = envelope();

    assertEquals(APPLIED, control.prepare(envelope).status());
    assertEquals(APPLIED, control.freezeSource(envelope).status());
    assertEquals(APPLIED, control.stageSnapshot(envelope).status());
    assertEquals(APPLIED, control.commit(envelope).status());
    assertEquals(APPLIED, control.activateDestination(envelope).status());
    assertEquals(APPLIED, control.cleanSource(envelope).status());

    LivePlayerSession session = sessions.get(PLAYER).orElseThrow();
    assertEquals("server-b", session.authoritativeServerId());
    assertEquals(1, session.playerSessionEpoch());
    assertEquals(HandoffPhase.SOURCE_CLEANED, session.handoffPhase());
  }

  @Test
  void rejectsUnsupportedProtocolVersion() {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    ControlEnvelope envelope = new ControlEnvelope(2, TRANSFER, PLAYER, "server-a", "server-b",
        "west", "east", 0, 0, 0, 0);

    assertThrows(IllegalArgumentException.class, () -> control.prepare(envelope));
  }

  private static ControlEnvelope envelope() {
    return new ControlEnvelope(HandoffControlPlane.PROTOCOL_VERSION, TRANSFER, PLAYER,
        "server-a", "server-b", "west", "east", 0, 0, 0, 0);
  }
}
