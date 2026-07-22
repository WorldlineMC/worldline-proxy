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

import java.util.Objects;
import java.util.function.Supplier;

/** Executes a bounded retry policy for idempotent terminal control commands. */
final class WorldlineControlRetry {

  private static final int DEFAULT_MAX_ATTEMPTS = 3;
  private static final long DEFAULT_BACKOFF_MILLIS = 25;

  private final int maxAttempts;
  private final long backoffMillis;
  private final Sleeper sleeper;

  WorldlineControlRetry() {
    this(DEFAULT_MAX_ATTEMPTS, DEFAULT_BACKOFF_MILLIS, Thread::sleep);
  }

  WorldlineControlRetry(final int maxAttempts, final long backoffMillis,
      final Sleeper sleeper) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be positive");
    }
    if (backoffMillis < 0) {
      throw new IllegalArgumentException("backoffMillis must not be negative");
    }
    this.maxAttempts = maxAttempts;
    this.backoffMillis = backoffMillis;
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  Outcome execute(final Supplier<LivePlayerSessionStore.TransitionResult> operation) {
    Objects.requireNonNull(operation, "operation");
    LivePlayerSessionStore.TransitionResult result = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      result = Objects.requireNonNull(operation.get(), "operation result");
      if (successful(result)
          || result.status() != LivePlayerSessionStore.Status.CONTROL_UNAVAILABLE) {
        return new Outcome(result, attempt, successful(result));
      }
      if (attempt < maxAttempts && !backoff()) {
        return new Outcome(result, attempt, false);
      }
    }
    return new Outcome(Objects.requireNonNull(result), maxAttempts, false);
  }

  private boolean backoff() {
    try {
      sleeper.sleep(backoffMillis);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static boolean successful(final LivePlayerSessionStore.TransitionResult result) {
    return result.status() == LivePlayerSessionStore.Status.APPLIED
        || result.status() == LivePlayerSessionStore.Status.ALREADY_APPLIED;
  }

  record Outcome(LivePlayerSessionStore.TransitionResult result, int attempts,
                 boolean successful) {
  }

  @FunctionalInterface
  interface Sleeper {

    void sleep(long millis) throws InterruptedException;
  }
}
