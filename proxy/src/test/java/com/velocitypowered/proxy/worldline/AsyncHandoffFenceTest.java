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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

public class AsyncHandoffFenceTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000081");
  private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000082");
  private static final UUID TRANSFER = UUID.fromString("00000000-0000-0000-0000-000000000083");

  @Test
  void requiresEverySessionAndBackendFenceToRemainUnchanged() {
    LivePlayerSession captured = session(CLIENT, TRANSFER, 4, 7,
        HandoffPhase.SOURCE_FROZEN, "server-a");
    AsyncHandoffFence fence = AsyncHandoffFence.capture(CLIENT, captured, "server-a");

    assertTrue(fence.matches(CLIENT, captured, "server-a"));
    assertFalse(fence.matches(UUID.randomUUID(), captured, "server-a"));
    assertFalse(fence.matches(CLIENT,
        session(CLIENT, UUID.randomUUID(), 4, 7, HandoffPhase.SOURCE_FROZEN, "server-a"),
        "server-a"));
    assertFalse(fence.matches(CLIENT,
        session(CLIENT, TRANSFER, 5, 7, HandoffPhase.SOURCE_FROZEN, "server-a"), "server-a"));
    assertFalse(fence.matches(CLIENT,
        session(CLIENT, TRANSFER, 4, 8, HandoffPhase.SOURCE_FROZEN, "server-a"), "server-a"));
    assertFalse(fence.matches(CLIENT,
        session(CLIENT, TRANSFER, 4, 7, HandoffPhase.SNAPSHOT_STAGED, "server-a"),
        "server-a"));
    assertFalse(fence.matches(CLIENT,
        session(CLIENT, TRANSFER, 4, 7, HandoffPhase.SOURCE_FROZEN, "server-b"),
        "server-a"));
    assertFalse(fence.matches(CLIENT, captured, "server-b"));
  }

  @Test
  void absenceOfALiveSessionIsAlsoPartOfTheFence() {
    AsyncHandoffFence fence = AsyncHandoffFence.capture(CLIENT, null, "server-a");

    assertTrue(fence.matches(CLIENT, null, "server-a"));
    assertFalse(fence.matches(CLIENT,
        session(CLIENT, null, 0, 0, HandoffPhase.ACTIVE_SOURCE, "server-a"), "server-a"));
  }

  private static LivePlayerSession session(final UUID client, final UUID transfer,
      final long epoch, final long generation, final HandoffPhase phase, final String server) {
    return new LivePlayerSession(PLAYER, client, server, epoch, generation, transfer, phase);
  }
}
