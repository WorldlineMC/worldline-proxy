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

import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_21_4;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_26_1;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_26_2;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for the undecoded serverbound bookkeeping classification.
 */
public class ServerboundHandoffTrafficTest {

  @Test
  void classifiesModernConnectionBookkeeping() {
    assertTrue(ServerboundHandoffTraffic.isConnectionBookkeeping(MINECRAFT_26_1, 0x0B),
        "chunk batch received");
    assertTrue(ServerboundHandoffTraffic.isConnectionBookkeeping(MINECRAFT_26_1, 0x2B),
        "player input");
    assertTrue(ServerboundHandoffTraffic.isConnectionBookkeeping(MINECRAFT_26_1, 0x2D),
        "pong");
    assertTrue(ServerboundHandoffTraffic.isConnectionBookkeeping(MINECRAFT_26_2, 0x0B),
        "later protocol versions keep the classification");
  }

  @Test
  void keepsTheFailSafeAbortForEverythingElse()  {
    assertFalse(ServerboundHandoffTraffic.isConnectionBookkeeping(MINECRAFT_26_1, 0x12),
        "container click is gameplay");
    assertFalse(ServerboundHandoffTraffic.isConnectionBookkeeping(MINECRAFT_26_1, 0x1A),
        "interact is gameplay");
    assertFalse(ServerboundHandoffTraffic.isConnectionBookkeeping(MINECRAFT_1_21_4, 0x0B),
        "unmapped protocol versions never classify by 26.1 packet id");
  }
}
