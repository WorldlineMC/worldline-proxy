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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

public class HandoffReplayBufferTest {

  private static final UUID TRANSFER =
      UUID.fromString("00000000-0000-0000-0000-000000000071");

  @Test
  void drainsCrossingFirstAndLaterInputInMonotonicOrder() {
    HandoffReplayBuffer<String> buffer = new HandoffReplayBuffer<>(TRANSFER, 4, 9, 4);
    buffer.append("crossing");
    buffer.append("later-1");
    buffer.append("later-2");

    assertEquals(List.of(
        new HandoffReplayBuffer.Entry<>(0L, "crossing"),
        new HandoffReplayBuffer.Entry<>(1L, "later-1"),
        new HandoffReplayBuffer.Entry<>(2L, "later-2")), buffer.drain(TRANSFER, 4, 9));
    assertTrue(buffer.consumed());
    assertThrows(IllegalStateException.class, () -> buffer.drain(TRANSFER, 4, 9));
  }

  @Test
  void rejectsWrongTransferEpochAndGeneration() {
    HandoffReplayBuffer<String> buffer = new HandoffReplayBuffer<>(TRANSFER, 4, 9, 4);
    buffer.append("crossing");

    assertThrows(IllegalArgumentException.class,
        () -> buffer.drain(UUID.randomUUID(), 4, 9));
    assertThrows(IllegalArgumentException.class, () -> buffer.drain(TRANSFER, 5, 9));
    assertThrows(IllegalArgumentException.class, () -> buffer.drain(TRANSFER, 4, 10));
    assertFalse(buffer.consumed());
  }

  @Test
  void enforcesCountAndTimeBounds() {
    AtomicLong now = new AtomicLong();
    HandoffReplayBuffer<String> full = new HandoffReplayBuffer<>(TRANSFER, 4, 9, 1, 10,
        now::get);
    assertEquals(HandoffReplayBuffer.AppendResult.APPENDED, full.append("crossing"));
    assertEquals(HandoffReplayBuffer.AppendResult.FULL, full.append("later"));

    HandoffReplayBuffer<String> expired = new HandoffReplayBuffer<>(TRANSFER, 4, 9, 2, 10,
        now::get);
    now.set(11);
    assertEquals(HandoffReplayBuffer.AppendResult.EXPIRED, expired.append("crossing"));

    now.set(0);
    HandoffReplayBuffer<String> expiredBeforeDrain = new HandoffReplayBuffer<>(
        TRANSFER, 4, 9, 2, 10, now::get);
    expiredBeforeDrain.append("crossing");
    now.set(11);
    assertThrows(IllegalStateException.class,
        () -> expiredBeforeDrain.drain(TRANSFER, 4, 9));
    assertTrue(expiredBeforeDrain.consumed());
  }

  @Test
  void discardIsOneShotAndPreventsReplay() {
    HandoffReplayBuffer<String> buffer = new HandoffReplayBuffer<>(TRANSFER, 4, 9, 4);
    buffer.append("crossing");

    assertTrue(buffer.discard());
    assertFalse(buffer.discard());
    assertEquals(HandoffReplayBuffer.AppendResult.CONSUMED, buffer.append("later"));
    assertThrows(IllegalStateException.class, () -> buffer.drain(TRANSFER, 4, 9));
  }

  @Test
  void replayGateStaysClosedAcrossActivationUntilTheEventLoopDrain() {
    assertTrue(HandoffReplayGate.mustBuffer(HandoffPhase.SNAPSHOT_STAGED, true));
    assertTrue(HandoffReplayGate.mustBuffer(HandoffPhase.COMMITTED, true));
    assertTrue(HandoffReplayGate.mustBuffer(HandoffPhase.ACTIVE_DESTINATION, true));
    assertFalse(HandoffReplayGate.mustBuffer(HandoffPhase.ACTIVE_DESTINATION, false));
    assertFalse(HandoffReplayGate.mustBuffer(HandoffPhase.SOURCE_CLEANED, false));
    assertTrue(HandoffReplayGate.rejectNonReplayable(HandoffPhase.COMMITTED, true));
    assertTrue(HandoffReplayGate.rejectNonReplayable(HandoffPhase.ACTIVE_DESTINATION, true));
    assertTrue(HandoffReplayGate.rejectNonReplayable(HandoffPhase.SOURCE_CLEANED, true));
    assertFalse(HandoffReplayGate.rejectNonReplayable(HandoffPhase.ACTIVE_SOURCE, false));
  }

  @Test
  void precommitDelayDoesNotConsumeThePostCommitReplayDeadline() {
    AtomicLong now = new AtomicLong(100);
    HandoffReplayBuffer<String> buffer = new HandoffReplayBuffer<>(
        TRANSFER, 4, 9, 4, now::get);

    now.set(10_000);
    assertEquals(HandoffReplayBuffer.AppendResult.APPENDED, buffer.append("crossing"));

    buffer.armDeadline(10_010);
    now.set(10_010);
    assertEquals(HandoffReplayBuffer.AppendResult.APPENDED, buffer.append("at-deadline"));
    now.set(10_011);
    assertEquals(HandoffReplayBuffer.AppendResult.EXPIRED, buffer.append("too-late"));
    assertThrows(IllegalStateException.class, () -> buffer.armDeadline(20_000));
  }
}
