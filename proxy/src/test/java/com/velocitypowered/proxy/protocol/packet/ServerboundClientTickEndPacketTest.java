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

package com.velocitypowered.proxy.protocol.packet;

import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_26_1;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_26_2;
import static com.velocitypowered.proxy.protocol.ProtocolUtils.Direction.SERVERBOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * Tests for the serverbound end-of-client-tick marker.
 */
public class ServerboundClientTickEndPacketTest {

  @Test
  void registersModernTickEndPacketId() {
    StateRegistry.PacketRegistry.ProtocolRegistry registry =
        StateRegistry.PLAY.getProtocolRegistry(SERVERBOUND, MINECRAFT_26_2);

    MinecraftPacket tickEnd = registry.createPacket(0x0D);

    assertEquals(ServerboundClientTickEndPacket.class, tickEnd.getClass());
    assertEquals(0x0D, registry.getPacketId(tickEnd));
  }

  @Test
  void encodesToEmptyPayload() {
    ByteBuf buf = Unpooled.buffer();
    ServerboundClientTickEndPacket.INSTANCE.encode(buf, SERVERBOUND, MINECRAFT_26_1);

    assertEquals(0, buf.readableBytes());
  }
}
