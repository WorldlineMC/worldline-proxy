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

import com.velocitypowered.api.network.ProtocolVersion;

/**
 * Classifies serverbound play packets the proxy does not decode but that a healthy vanilla
 * client emits during ordinary play. These carry connection bookkeeping rather than
 * authoritative gameplay actions, so they must never trip the frozen-transfer abort: they are
 * forwarded while the source is frozen (its connection traffic continues) and elided during the
 * post-commit replay fence. Every other undecoded packet keeps the fail-safe abort required by
 * the M5 packet-classification table.
 */
public final class ServerboundHandoffTraffic {

  private static final int CHUNK_BATCH_RECEIVED_26_1 = 0x0B;
  private static final int PLAYER_INPUT_26_1 = 0x2B;
  private static final int PONG_26_1 = 0x2D;

  private ServerboundHandoffTraffic() {
  }

  /**
   * Returns whether an undecoded serverbound play packet is connection bookkeeping that is safe
   * to pass through (or elide) during a handoff instead of aborting the transfer.
   */
  public static boolean isConnectionBookkeeping(final ProtocolVersion version,
      final int packetId) {
    if (version.lessThan(ProtocolVersion.MINECRAFT_26_1)) {
      return false;
    }
    return packetId == CHUNK_BATCH_RECEIVED_26_1
        || packetId == PLAYER_INPUT_26_1
        || packetId == PONG_26_1;
  }
}
