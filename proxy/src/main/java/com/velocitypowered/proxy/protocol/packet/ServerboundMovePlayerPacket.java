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

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;

/**
 * Serverbound player movement packet variants decoded for Worldline boundary detection.
 */
public abstract class ServerboundMovePlayerPacket implements MinecraftPacket {

  protected double x;
  protected double y;
  protected double z;
  protected float yaw;
  protected float pitch;
  protected boolean onGround;
  protected boolean horizontalCollision;

  public double getX() {
    return x;
  }

  public double getY() {
    return y;
  }

  public double getZ() {
    return z;
  }

  public float getYaw() {
    return yaw;
  }

  public float getPitch() {
    return pitch;
  }

  public boolean isOnGround() {
    return onGround;
  }

  public boolean isHorizontalCollision() {
    return horizontalCollision;
  }

  public boolean hasPosition() {
    return false;
  }

  public boolean hasRotation() {
    return false;
  }

  @Override
  public boolean handle(final MinecraftSessionHandler handler) {
    return handler.handle(this);
  }

  void decodeFlags(final ByteBuf buf, final ProtocolVersion version) {
    if (version.noLessThan(ProtocolVersion.MINECRAFT_1_21_2)) {
      byte flags = buf.readByte();
      onGround = (flags & 0x01) != 0;
      horizontalCollision = (flags & 0x02) != 0;
    } else {
      onGround = buf.readBoolean();
      horizontalCollision = false;
    }
  }

  void encodeFlags(final ByteBuf buf, final ProtocolVersion version) {
    if (version.noLessThan(ProtocolVersion.MINECRAFT_1_21_2)) {
      buf.writeByte((onGround ? 0x01 : 0) | (horizontalCollision ? 0x02 : 0));
    } else {
      buf.writeBoolean(onGround);
    }
  }

  /**
   * Position-only movement packet.
   */
  public static final class Position extends ServerboundMovePlayerPacket {

    @Override
    public boolean hasPosition() {
      return true;
    }

    @Override
    public void decode(final ByteBuf buf, final ProtocolUtils.Direction direction,
        final ProtocolVersion protocolVersion) {
      x = buf.readDouble();
      y = buf.readDouble();
      z = buf.readDouble();
      decodeFlags(buf, protocolVersion);
    }

    @Override
    public void encode(final ByteBuf buf, final ProtocolUtils.Direction direction,
        final ProtocolVersion protocolVersion) {
      buf.writeDouble(x);
      buf.writeDouble(y);
      buf.writeDouble(z);
      encodeFlags(buf, protocolVersion);
    }
  }

  /**
   * Position-and-rotation movement packet.
   */
  public static final class PositionRotation extends ServerboundMovePlayerPacket {

    @Override
    public boolean hasPosition() {
      return true;
    }

    @Override
    public boolean hasRotation() {
      return true;
    }

    @Override
    public void decode(final ByteBuf buf, final ProtocolUtils.Direction direction,
        final ProtocolVersion protocolVersion) {
      x = buf.readDouble();
      y = buf.readDouble();
      z = buf.readDouble();
      yaw = buf.readFloat();
      pitch = buf.readFloat();
      decodeFlags(buf, protocolVersion);
    }

    @Override
    public void encode(final ByteBuf buf, final ProtocolUtils.Direction direction,
        final ProtocolVersion protocolVersion) {
      buf.writeDouble(x);
      buf.writeDouble(y);
      buf.writeDouble(z);
      buf.writeFloat(yaw);
      buf.writeFloat(pitch);
      encodeFlags(buf, protocolVersion);
    }
  }

  /**
   * Rotation-only movement packet.
   */
  public static final class Rotation extends ServerboundMovePlayerPacket {

    @Override
    public boolean hasRotation() {
      return true;
    }

    @Override
    public void decode(final ByteBuf buf, final ProtocolUtils.Direction direction,
        final ProtocolVersion protocolVersion) {
      yaw = buf.readFloat();
      pitch = buf.readFloat();
      decodeFlags(buf, protocolVersion);
    }

    @Override
    public void encode(final ByteBuf buf, final ProtocolUtils.Direction direction,
        final ProtocolVersion protocolVersion) {
      buf.writeFloat(yaw);
      buf.writeFloat(pitch);
      encodeFlags(buf, protocolVersion);
    }
  }

  /**
   * Ground-state movement packet without coordinates.
   */
  public static final class StatusOnly extends ServerboundMovePlayerPacket {

    @Override
    public void decode(final ByteBuf buf, final ProtocolUtils.Direction direction,
        final ProtocolVersion protocolVersion) {
      decodeFlags(buf, protocolVersion);
    }

    @Override
    public void encode(final ByteBuf buf, final ProtocolUtils.Direction direction,
        final ProtocolVersion protocolVersion) {
      encodeFlags(buf, protocolVersion);
    }
  }
}
