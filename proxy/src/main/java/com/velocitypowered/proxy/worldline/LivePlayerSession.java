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

import java.util.UUID;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Proxy-owned live authority record for one connected player.
 */
public record LivePlayerSession(
    UUID playerUuid,
    UUID clientConnectionId,
    String authoritativeServerId,
    long playerSessionEpoch,
    long routeGeneration,
    @Nullable UUID activeTransferId,
    HandoffPhase handoffPhase) {
}
