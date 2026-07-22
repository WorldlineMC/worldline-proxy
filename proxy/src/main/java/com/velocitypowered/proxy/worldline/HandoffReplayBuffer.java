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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Bounded, transfer-fenced, one-shot replay storage for serverbound input. */
public final class HandoffReplayBuffer<T> {

  private final UUID transferId;
  private final long playerSessionEpoch;
  private final long routeGeneration;
  private final int maxEntries;
  private final LongSupplier ticker;
  private final List<Entry<T>> entries = new ArrayList<>();
  private volatile long deadlineNanos;
  private volatile boolean deadlineArmed;
  private boolean consumed;

  /** Creates the production replay buffer. */
  public HandoffReplayBuffer(final UUID transferId, final long playerSessionEpoch,
      final long routeGeneration, final int maxEntries) {
    this(transferId, playerSessionEpoch, routeGeneration, maxEntries, System::nanoTime);
  }

  HandoffReplayBuffer(final UUID transferId, final long playerSessionEpoch,
      final long routeGeneration, final int maxEntries, final LongSupplier ticker) {
    this(transferId, playerSessionEpoch, routeGeneration, maxEntries, ticker, false, 0);
  }

  HandoffReplayBuffer(final UUID transferId, final long playerSessionEpoch,
      final long routeGeneration, final int maxEntries, final long maxAgeNanos,
      final LongSupplier ticker) {
    this(transferId, playerSessionEpoch, routeGeneration, maxEntries, ticker, true, maxAgeNanos);
  }

  private HandoffReplayBuffer(final UUID transferId, final long playerSessionEpoch,
      final long routeGeneration, final int maxEntries, final LongSupplier ticker,
      final boolean armDeadline, final long maxAgeNanos) {
    this.transferId = Objects.requireNonNull(transferId, "transferId");
    this.ticker = Objects.requireNonNull(ticker, "ticker");
    if (playerSessionEpoch < 0 || routeGeneration < 0 || maxEntries < 1
        || (armDeadline && maxAgeNanos < 1)) {
      throw new IllegalArgumentException("invalid replay fence or bound");
    }
    this.playerSessionEpoch = playerSessionEpoch;
    this.routeGeneration = routeGeneration;
    this.maxEntries = maxEntries;
    if (armDeadline) {
      this.deadlineNanos = ticker.getAsLong() + maxAgeNanos;
      this.deadlineArmed = true;
    }
  }

  /** Arms the one post-commit deadline shared with the transfer coordinator. */
  public synchronized void armDeadline(final long absoluteDeadlineNanos) {
    if (deadlineArmed) {
      throw new IllegalStateException("replay deadline was already armed");
    }
    if (absoluteDeadlineNanos < 1) {
      throw new IllegalArgumentException("replay deadline must be positive");
    }
    deadlineNanos = absoluteDeadlineNanos;
    deadlineArmed = true;
  }

  /** Appends one ordered entry while the buffer remains live and within bounds. */
  public AppendResult append(final T value) {
    Objects.requireNonNull(value, "value");
    if (consumed) {
      return AppendResult.CONSUMED;
    }
    if (expired()) {
      return AppendResult.EXPIRED;
    }
    if (entries.size() >= maxEntries) {
      return AppendResult.FULL;
    }
    entries.add(new Entry<>(entries.size(), value));
    return AppendResult.APPENDED;
  }

  /** Drains exactly once when every authority fence matches. */
  public List<Entry<T>> drain(final UUID expectedTransferId, final long expectedEpoch,
      final long expectedRouteGeneration) {
    validateFence(expectedTransferId, expectedEpoch, expectedRouteGeneration);
    if (consumed) {
      throw new IllegalStateException("replay buffer was already consumed");
    }
    if (expired()) {
      consumed = true;
      entries.clear();
      throw new IllegalStateException("replay buffer expired before drain");
    }
    consumed = true;
    List<Entry<T>> replay = List.copyOf(entries);
    entries.clear();
    return replay;
  }

  /** Discards all entries exactly once after a terminal post-commit failure. */
  public boolean discard() {
    if (consumed) {
      return false;
    }
    consumed = true;
    entries.clear();
    return true;
  }

  public int size() {
    return entries.size();
  }

  public boolean consumed() {
    return consumed;
  }

  private boolean expired() {
    return deadlineArmed && ticker.getAsLong() > deadlineNanos;
  }

  private void validateFence(final UUID expectedTransferId, final long expectedEpoch,
      final long expectedRouteGeneration) {
    if (!transferId.equals(expectedTransferId) || playerSessionEpoch != expectedEpoch
        || routeGeneration != expectedRouteGeneration) {
      throw new IllegalArgumentException("mismatched replay authority fence");
    }
  }

  public enum AppendResult {
    APPENDED,
    FULL,
    EXPIRED,
    CONSUMED
  }

  /** One input and its monotonic transfer-local sequence number. */
  public record Entry<T>(long sequence, T value) {
  }
}
