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
import java.util.UUID;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Immutable authority snapshot used to reject stale asynchronous packet callbacks. */
public record AsyncHandoffFence(
    UUID clientConnectionId,
    String backendServerId,
    boolean liveSessionPresent,
    @Nullable String authoritativeServerId,
    long playerSessionEpoch,
    long routeGeneration,
    @Nullable UUID transferId,
    @Nullable HandoffPhase phase) {

  /** Captures every live-session field that can change across a handoff. */
  public static AsyncHandoffFence capture(final UUID clientConnectionId,
      final @Nullable LivePlayerSession session, final String backendServerId) {
    Objects.requireNonNull(clientConnectionId, "clientConnectionId");
    Objects.requireNonNull(backendServerId, "backendServerId");
    if (session == null) {
      return new AsyncHandoffFence(clientConnectionId, backendServerId, false, null,
          0, 0, null, null);
    }
    return new AsyncHandoffFence(clientConnectionId, backendServerId, true,
        session.authoritativeServerId(), session.playerSessionEpoch(), session.routeGeneration(),
        session.activeTransferId(), session.handoffPhase());
  }

  /** Returns whether the callback still belongs to the same client, session, and backend. */
  public boolean matches(final UUID currentClientConnectionId,
      final @Nullable LivePlayerSession current, final String currentBackendServerId) {
    if (!clientConnectionId.equals(currentClientConnectionId)
        || !backendServerId.equals(currentBackendServerId)
        || liveSessionPresent != (current != null)) {
      return false;
    }
    if (current == null) {
      return true;
    }
    return current.clientConnectionId().equals(clientConnectionId)
        && current.authoritativeServerId().equals(authoritativeServerId)
        && current.playerSessionEpoch() == playerSessionEpoch
        && current.routeGeneration() == routeGeneration
        && Objects.equals(current.activeTransferId(), transferId)
        && current.handoffPhase() == phase;
  }
}
