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

import org.checkerframework.checker.nullness.qual.Nullable;

/** Decides whether movement must remain in the post-commit replay queue. */
public final class HandoffReplayGate {

  private HandoffReplayGate() {
  }

  /** Keeps the gate closed until the event-loop replay drain has completed. */
  public static boolean mustBuffer(final @Nullable HandoffPhase phase,
      final boolean replayPending) {
    return replayPending;
  }

  /** Rejects unsupported input for the entire committed transfer, including cleanup. */
  public static boolean rejectNonReplayable(final @Nullable HandoffPhase phase,
      final boolean transferActive) {
    return transferActive && (phase == HandoffPhase.COMMITTED
        || phase == HandoffPhase.ACTIVE_DESTINATION
        || phase == HandoffPhase.SOURCE_CLEANED);
  }
}
