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

import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_26_1;
import static com.velocitypowered.proxy.protocol.ProtocolUtils.Direction.SERVERBOUND;
import static com.velocitypowered.proxy.worldline.BoundaryCrossingDetector.Action.BUFFER_LIMIT_EXCEEDED;
import static com.velocitypowered.proxy.worldline.BoundaryCrossingDetector.Action.FORWARD;
import static com.velocitypowered.proxy.worldline.BoundaryCrossingDetector.Action.PREPARE;
import static com.velocitypowered.proxy.worldline.BoundaryCrossingDetector.Action.PREPARE_NOT_READY;
import static com.velocitypowered.proxy.worldline.BoundaryCrossingDetector.Action.PREPARE_TIMEOUT;
import static com.velocitypowered.proxy.worldline.BoundaryCrossingDetector.Action.WITHHOLD_CROSSING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.protocol.packet.ServerboundMovePlayerPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for decoded movement routing.
 */
public class ServerboundMovementRouterTest {

  @TempDir
  Path tempDir;

  @Test
  void withholdsFirstCrossingPacket() throws Exception {
    ServerboundMovementRouter router = router();

    assertEquals(FORWARD, router.route("server-a", position(-32)).action());
    assertEquals(PREPARE, router.route("server-a", position(-1)).action());
    ServerboundMovePlayerPacket.Position crossing = position(0);
    assertEquals(WITHHOLD_CROSSING, router.route("server-a", crossing).action());
    assertEquals(crossing, router.bufferedPackets().get(0));
  }

  @Test
  void capsHeldCrossingPackets() throws Exception {
    ServerboundMovementRouter router = new ServerboundMovementRouter(detector(), 1);

    assertEquals(PREPARE, router.route("server-a", position(-1)).action());
    assertEquals(WITHHOLD_CROSSING, router.route("server-a", position(0)).action());
    assertEquals(BUFFER_LIMIT_EXCEEDED, router.route("server-a", position(0.1)).action());
    assertEquals(1, router.bufferedPackets().size());
  }

  @Test
  void timesOutSlowPreparationBeforeBufferingCrossing() throws Exception {
    AtomicLong now = new AtomicLong();
    ServerboundMovementRouter router = new ServerboundMovementRouter(detector(), 64, 10,
        now::get);

    assertEquals(PREPARE, router.route("server-a", position(-1)).action());
    now.set(11);
    assertEquals(PREPARE_TIMEOUT, router.route("server-a", position(0)).action());
    assertEquals(0, router.bufferedPackets().size());
  }

  @Test
  void refusesToBufferCrossingBeforePrepareStarts() throws Exception {
    ServerboundMovementRouter router = router();

    assertEquals(FORWARD, router.route("server-a", position(-32)).action());
    assertEquals(PREPARE_NOT_READY, router.route("server-a", position(0)).action());
    assertEquals(0, router.bufferedPackets().size());
  }

  @Test
  void clearBufferResetsHeldInputAndPrepareDeadline() throws Exception {
    AtomicLong now = new AtomicLong();
    ServerboundMovementRouter router = new ServerboundMovementRouter(detector(), 64, 10,
        now::get);

    assertEquals(PREPARE, router.route("server-a", position(-1)).action());
    assertEquals(WITHHOLD_CROSSING, router.route("server-a", position(0)).action());
    router.clearBuffer();
    now.set(11);

    assertEquals(PREPARE_NOT_READY, router.route("server-a", position(0.1)).action());
    assertEquals(0, router.bufferedPackets().size());
  }

  @Test
  void drainBufferReturnsHeldInputInOrderAndClearsIt() throws Exception {
    ServerboundMovementRouter router = router();
    ServerboundMovePlayerPacket.Position crossing = position(0);

    assertEquals(PREPARE, router.route("server-a", position(-1)).action());
    assertEquals(WITHHOLD_CROSSING, router.route("server-a", crossing).action());
    assertTrue(router.hasBufferedInput());
    assertEquals(WITHHOLD_CROSSING, router.bufferedDecision().action());

    assertEquals(List.of(crossing), router.drainBuffer());
    assertFalse(router.hasBufferedInput());
    assertEquals(0, router.bufferedPackets().size());
  }

  @Test
  void buffersMovementVariantsAfterCrossingInOrder() throws Exception {
    ServerboundMovementRouter router = router();
    ServerboundMovePlayerPacket.Position crossing = position(0);
    ServerboundMovePlayerPacket.Rotation rotation = new ServerboundMovePlayerPacket.Rotation();

    assertEquals(PREPARE, router.route("server-a", position(-1)).action());
    assertEquals(WITHHOLD_CROSSING, router.route("server-a", crossing).action());
    assertEquals(WITHHOLD_CROSSING, router.route("server-a", rotation).action());

    assertEquals(List.of(crossing, rotation), router.drainBuffer());
  }

  @Test
  void timesOutMovementVariantsAfterCrossingIsHeld() throws Exception {
    AtomicLong now = new AtomicLong();
    ServerboundMovementRouter router = new ServerboundMovementRouter(detector(), 64, 10,
        now::get);

    assertEquals(PREPARE, router.route("server-a", position(-1)).action());
    assertEquals(WITHHOLD_CROSSING, router.route("server-a", position(0)).action());
    now.set(11);

    assertEquals(PREPARE_TIMEOUT, router.route("server-a",
        new ServerboundMovePlayerPacket.Rotation()).action());
    assertEquals(1, router.bufferedPackets().size());
  }

  @Test
  void forwardsRotationOnlyPackets() throws Exception {
    ServerboundMovementRouter router = router();

    assertEquals(FORWARD, router.route("server-a",
        new ServerboundMovePlayerPacket.Rotation()).action());
  }

  private ServerboundMovementRouter router() throws Exception {
    return new ServerboundMovementRouter(detector());
  }

  private BoundaryCrossingDetector detector() throws Exception {
    Path config = tempDir.resolve("worldline.toml");
    Files.writeString(config, """
        [world]
        level-name = "world"
        dimension = "minecraft:overworld"

        [[partitions]]
        id = "west"
        owner = "server-a"
        chunk-x-max = -1

        [[partitions]]
        id = "east"
        owner = "server-b"
        chunk-x-min = 0
        """);
    return new BoundaryCrossingDetector(StaticPartitionMap.read(config),
        "world", "minecraft:overworld", 1);
  }

  private static ServerboundMovePlayerPacket.Position position(final double x) {
    ByteBuf buf = Unpooled.buffer();
    buf.writeDouble(x);
    buf.writeDouble(64);
    buf.writeDouble(0);
    buf.writeByte(0x01);
    ServerboundMovePlayerPacket.Position packet = new ServerboundMovePlayerPacket.Position();
    packet.decode(buf, SERVERBOUND, MINECRAFT_26_1);
    return packet;
  }
}
