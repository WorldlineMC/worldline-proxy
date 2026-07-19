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

/** Immutable authority identity assigned to one backend connection. */
public record BackendSessionBinding(
    UUID playerUuid,
    UUID clientConnectionId,
    String backendServerId,
    long playerSessionEpoch,
    long routeGeneration,
    @Nullable UUID transferId) {

  /** Creates and validates an immutable backend binding. */
  public BackendSessionBinding {
    Objects.requireNonNull(playerUuid, "playerUuid");
    Objects.requireNonNull(clientConnectionId, "clientConnectionId");
    Objects.requireNonNull(backendServerId, "backendServerId");
    if (backendServerId.isBlank()) {
      throw new IllegalArgumentException("backendServerId must not be blank");
    }
    if (playerSessionEpoch < 0 || routeGeneration < 0) {
      throw new IllegalArgumentException("epochs and generations must be non-negative");
    }
  }

  /** Matches the durable authority fences while deliberately ignoring an old transfer id. */
  public boolean matchesAuthority(final UUID expectedPlayerUuid,
      final UUID expectedClientConnectionId, final String expectedBackendServerId,
      final long expectedPlayerSessionEpoch, final long expectedRouteGeneration) {
    return playerUuid.equals(expectedPlayerUuid)
        && clientConnectionId.equals(expectedClientConnectionId)
        && backendServerId.equals(expectedBackendServerId)
        && playerSessionEpoch == expectedPlayerSessionEpoch
        && routeGeneration == expectedRouteGeneration;
  }
}
