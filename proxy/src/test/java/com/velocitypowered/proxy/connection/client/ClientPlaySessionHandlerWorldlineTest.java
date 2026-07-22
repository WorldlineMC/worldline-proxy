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

package com.velocitypowered.proxy.connection.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.worldline.AsyncHandoffFence;
import com.velocitypowered.proxy.worldline.HandoffPhase;
import com.velocitypowered.proxy.worldline.LivePlayerSession;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class ClientPlaySessionHandlerWorldlineTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000091");
  private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000092");

  @Test
  void delayedCallbackCannotWriteOrEnqueueAfterItsFenceChanges() {
    LivePlayerSession before = new LivePlayerSession(PLAYER, CLIENT, "server-a", 0, 0, null,
        HandoffPhase.ACTIVE_SOURCE);
    AsyncHandoffFence fence = AsyncHandoffFence.capture(CLIENT, before, "server-a");
    LivePlayerSession after = new LivePlayerSession(PLAYER, CLIENT, "server-b", 1, 1,
        UUID.randomUUID(), HandoffPhase.ACTIVE_DESTINATION);
    AtomicBoolean wrote = new AtomicBoolean();
    AtomicBoolean enqueued = new AtomicBoolean();

    assertFalse(ClientPlaySessionHandler.runFencedWorldlineCallback(fence, CLIENT, after,
        "server-b", false, () -> wrote.set(true)));
    assertFalse(ClientPlaySessionHandler.runFencedWorldlineCallback(fence, CLIENT, before,
        "server-a", false, () -> enqueued.set(true)));
    assertFalse(wrote.get());
    assertFalse(enqueued.get());
  }

  @Test
  void unchangedCallbackFenceRunsExactlyOnce() {
    LivePlayerSession session = new LivePlayerSession(PLAYER, CLIENT, "server-a", 0, 0, null,
        HandoffPhase.ACTIVE_SOURCE);
    AsyncHandoffFence fence = AsyncHandoffFence.capture(CLIENT, session, "server-a");
    AtomicBoolean invoked = new AtomicBoolean();

    assertTrue(ClientPlaySessionHandler.runFencedWorldlineCallback(fence, CLIENT, session,
        "server-a", true, () -> invoked.set(true)));
    assertTrue(invoked.get());
  }

  @Test
  void M5AndLegacySpliceModesUseUnsignedChat() {
    assertTrue(ClientPlaySessionHandler.usesUnsignedWorldlineChat(true, ""));
    assertTrue(ClientPlaySessionHandler.usesUnsignedWorldlineChat(false, "server-b"));
    assertFalse(ClientPlaySessionHandler.usesUnsignedWorldlineChat(false, ""));
  }

  @Test
  void resourcePackHandlingIsStoppedDuringFrozenAndCommittedPhases() {
    assertTrue(ClientPlaySessionHandler.blocksConnectionSensitiveInput(
        HandoffPhase.SOURCE_FROZEN, true, true));
    assertTrue(ClientPlaySessionHandler.blocksConnectionSensitiveInput(
        HandoffPhase.COMMITTED, true, false));
    assertFalse(ClientPlaySessionHandler.blocksConnectionSensitiveInput(
        HandoffPhase.ACTIVE_SOURCE, false, false));
  }
}
