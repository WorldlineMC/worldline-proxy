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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * Tests for serverbound movement packets.
 */
public class ServerboundMovePlayerPacketTest {

  @Test
  void decodesModernPositionRotationFlags() {
    ByteBuf buf = Unpooled.buffer();
    buf.writeDouble(1.25);
    buf.writeDouble(64);
    buf.writeDouble(-3.5);
    buf.writeFloat(90);
    buf.writeFloat(45);
    buf.writeByte(0x03);

    ServerboundMovePlayerPacket.PositionRotation packet =
        new ServerboundMovePlayerPacket.PositionRotation();
    packet.decode(buf, SERVERBOUND, MINECRAFT_26_1);

    assertEquals(1.25, packet.getX());
    assertEquals(64, packet.getY());
    assertEquals(-3.5, packet.getZ());
    assertEquals(90, packet.getYaw());
    assertEquals(45, packet.getPitch());
    assertTrue(packet.isOnGround());
    assertTrue(packet.isHorizontalCollision());
  }

  @Test
  void encodesModernPositionFlags() {
    ServerboundMovePlayerPacket.Position packet = new ServerboundMovePlayerPacket.Position();
    packet.x = -1;
    packet.y = 70;
    packet.z = 2;
    packet.onGround = true;

    ByteBuf buf = Unpooled.buffer();
    packet.encode(buf, SERVERBOUND, MINECRAFT_26_1);

    assertEquals(-1, buf.readDouble());
    assertEquals(70, buf.readDouble());
    assertEquals(2, buf.readDouble());
    assertEquals(0x01, buf.readByte());
  }

  @Test
  void registersModernMovementPacketIds() {
    StateRegistry.PacketRegistry.ProtocolRegistry registry =
        StateRegistry.PLAY.getProtocolRegistry(SERVERBOUND, MINECRAFT_26_2);

    MinecraftPacket position = registry.createPacket(0x1E);
    MinecraftPacket positionRotation = registry.createPacket(0x1F);
    MinecraftPacket rotation = registry.createPacket(0x20);
    MinecraftPacket statusOnly = registry.createPacket(0x21);

    assertEquals(ServerboundMovePlayerPacket.PositionRotation.class, positionRotation.getClass());
    assertEquals(ServerboundMovePlayerPacket.Position.class, position.getClass());
    assertEquals(ServerboundMovePlayerPacket.Rotation.class, rotation.getClass());
    assertEquals(ServerboundMovePlayerPacket.StatusOnly.class, statusOnly.getClass());
    assertEquals(0x1E, registry.getPacketId(position));
    assertEquals(0x1F, registry.getPacketId(positionRotation));
    assertEquals(0x20, registry.getPacketId(rotation));
    assertEquals(0x21, registry.getPacketId(statusOnly));
  }
}
