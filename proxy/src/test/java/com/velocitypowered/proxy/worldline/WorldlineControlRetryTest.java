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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class WorldlineControlRetryTest {

  @Test
  void retriesUnavailableStatusThenReportsSuccess() {
    Queue<LivePlayerSessionStore.TransitionResult> results = new ArrayDeque<>(List.of(
        result(LivePlayerSessionStore.Status.CONTROL_UNAVAILABLE),
        result(LivePlayerSessionStore.Status.APPLIED)));
    AtomicInteger backoffs = new AtomicInteger();
    WorldlineControlRetry retry = new WorldlineControlRetry(3, 25,
        ignored -> backoffs.incrementAndGet());

    WorldlineControlRetry.Outcome outcome = retry.execute(results::remove);

    assertTrue(outcome.successful());
    assertEquals(LivePlayerSessionStore.Status.APPLIED, outcome.result().status());
    assertEquals(2, outcome.attempts());
    assertEquals(1, backoffs.get());
  }

  @Test
  void boundsPermanentUnavailabilityAndReportsTheLastStatus() {
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger backoffs = new AtomicInteger();
    WorldlineControlRetry retry = new WorldlineControlRetry(3, 25,
        ignored -> backoffs.incrementAndGet());

    WorldlineControlRetry.Outcome outcome = retry.execute(() -> {
      attempts.incrementAndGet();
      return result(LivePlayerSessionStore.Status.CONTROL_UNAVAILABLE);
    });

    assertFalse(outcome.successful());
    assertEquals(LivePlayerSessionStore.Status.CONTROL_UNAVAILABLE, outcome.result().status());
    assertEquals(3, outcome.attempts());
    assertEquals(3, attempts.get());
    assertEquals(2, backoffs.get());
  }

  @Test
  void doesNotRetryDefinitiveRejection() {
    AtomicInteger attempts = new AtomicInteger();
    WorldlineControlRetry retry = new WorldlineControlRetry(3, 25, ignored -> { });

    WorldlineControlRetry.Outcome outcome = retry.execute(() -> {
      attempts.incrementAndGet();
      return result(LivePlayerSessionStore.Status.REJECTED_MISMATCH);
    });

    assertFalse(outcome.successful());
    assertEquals(1, outcome.attempts());
    assertEquals(1, attempts.get());
  }

  private static LivePlayerSessionStore.TransitionResult result(
      final LivePlayerSessionStore.Status status) {
    return new LivePlayerSessionStore.TransitionResult(status, Optional.empty(), Optional.empty(),
        0);
  }
}
