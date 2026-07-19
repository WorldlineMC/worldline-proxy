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

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests the exhaustive ADR 0005 phase-transition matrix. */
public class HandoffPhaseTest {

  private static final Set<Edge> LEGAL = Set.of(
      edge(HandoffPhase.ACTIVE_SOURCE, HandoffPhase.PREPARING_DESTINATION),
      edge(HandoffPhase.PREPARING_DESTINATION, HandoffPhase.DESTINATION_READY),
      edge(HandoffPhase.PREPARING_DESTINATION, HandoffPhase.ACTIVE_SOURCE),
      edge(HandoffPhase.DESTINATION_READY, HandoffPhase.SOURCE_FROZEN),
      edge(HandoffPhase.DESTINATION_READY, HandoffPhase.ACTIVE_SOURCE),
      edge(HandoffPhase.SOURCE_FROZEN, HandoffPhase.SNAPSHOT_STAGED),
      edge(HandoffPhase.SOURCE_FROZEN, HandoffPhase.ACTIVE_SOURCE),
      edge(HandoffPhase.SNAPSHOT_STAGED, HandoffPhase.COMMITTED),
      edge(HandoffPhase.SNAPSHOT_STAGED, HandoffPhase.ACTIVE_SOURCE),
      edge(HandoffPhase.COMMITTED, HandoffPhase.ACTIVE_DESTINATION),
      edge(HandoffPhase.ACTIVE_DESTINATION, HandoffPhase.SOURCE_CLEANED),
      edge(HandoffPhase.SOURCE_CLEANED, HandoffPhase.ACTIVE_SOURCE));

  @Test
  void enforcesEveryLegalAndIllegalPhasePair() {
    for (HandoffPhase current : HandoffPhase.values()) {
      for (HandoffPhase next : HandoffPhase.values()) {
        assertEquals(LEGAL.contains(edge(current, next)), current.canTransitionTo(next),
            () -> current + " -> " + next);
      }
    }
  }

  private static Edge edge(final HandoffPhase current, final HandoffPhase next) {
    return new Edge(current, next);
  }

  private record Edge(HandoffPhase current, HandoffPhase next) {
  }
}
