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

/**
 * Worldline player handoff phases from ADR 0005.
 */
public enum HandoffPhase {
  ACTIVE_SOURCE,
  PREPARING_DESTINATION,
  DESTINATION_READY,
  SOURCE_FROZEN,
  SNAPSHOT_STAGED,
  COMMITTED,
  ACTIVE_DESTINATION,
  SOURCE_CLEANED;

  /**
   * Returns whether this phase may transition directly to the supplied phase.
   */
  public boolean canTransitionTo(final HandoffPhase next) {
    return switch (this) {
      case ACTIVE_SOURCE -> next == PREPARING_DESTINATION;
      case PREPARING_DESTINATION -> next == DESTINATION_READY || next == ACTIVE_SOURCE;
      case DESTINATION_READY -> next == SOURCE_FROZEN || next == ACTIVE_SOURCE;
      case SOURCE_FROZEN -> next == SNAPSHOT_STAGED || next == ACTIVE_SOURCE;
      case SNAPSHOT_STAGED -> next == COMMITTED || next == ACTIVE_SOURCE;
      case COMMITTED -> next == ACTIVE_DESTINATION;
      case ACTIVE_DESTINATION -> next == SOURCE_CLEANED;
      case SOURCE_CLEANED -> false;
    };
  }
}
