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

import static com.velocitypowered.proxy.worldline.BoundaryCrossingDetector.Action.FORWARD;
import static com.velocitypowered.proxy.worldline.BoundaryCrossingDetector.Action.PREPARE;
import static com.velocitypowered.proxy.worldline.BoundaryCrossingDetector.Action.WITHHOLD_CROSSING;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for movement boundary classification.
 */
public class BoundaryCrossingDetectorTest {

  @TempDir
  Path tempDir;

  @Test
  void forwardsMovementInsideCurrentPartition() throws Exception {
    BoundaryCrossingDetector detector = detector();

    assertEquals(FORWARD, detector.classify("server-a", -48, -40).action());
  }

  @Test
  void preparesWhenApproachingRemotePartition() throws Exception {
    BoundaryCrossingDetector detector = detector();
    BoundaryCrossingDetector.Decision decision = detector.classify("server-a", -17, -16);

    assertEquals(PREPARE, decision.action());
    assertEquals("west", decision.sourcePartitionId().orElseThrow());
    assertEquals("east", decision.remotePartitionId().orElseThrow());
    assertEquals("server-b", decision.remoteOwner().orElseThrow());
    assertEquals(0, decision.remoteEntryChunkX());
  }

  @Test
  void withholdsCrossingMovementFromSource() throws Exception {
    BoundaryCrossingDetector detector = detector();
    BoundaryCrossingDetector.Decision decision = detector.classify("server-a", -1, 0);

    assertEquals(WITHHOLD_CROSSING, decision.action());
    assertEquals("west", decision.sourcePartitionId().orElseThrow());
    assertEquals("east", decision.remotePartitionId().orElseThrow());
    assertEquals("server-b", decision.remoteOwner().orElseThrow());
  }

  @Test
  void withholdsRemoteMovementWhenTheOriginIsNotYetKnown() throws Exception {
    BoundaryCrossingDetector.Decision decision = detector().classifyUnknownOrigin("server-a", 0);

    assertEquals(WITHHOLD_CROSSING, decision.action());
    assertEquals("west", decision.sourcePartitionId().orElseThrow());
    assertEquals("east", decision.remotePartitionId().orElseThrow());
    assertEquals("server-b", decision.remoteOwner().orElseThrow());
  }

  @Test
  void floorsNegativeCoordinatesIntoMinecraftChunks() {
    assertEquals(-1, BoundaryCrossingDetector.blockToChunk(-0.1));
    assertEquals(-1, BoundaryCrossingDetector.blockToChunk(-16));
    assertEquals(-2, BoundaryCrossingDetector.blockToChunk(-16.1));
  }

  private BoundaryCrossingDetector detector() throws Exception {
    Path config = tempDir.resolve("worldline.toml");
    Files.writeString(config, """
        [world]
        level-name = "world"
        dimension = "minecraft:overworld"
        compatibility-id = "test-v1"

        [servers.server-a]
        control-address = "127.0.0.1:25576"

        [servers.server-b]
        control-address = "127.0.0.1:25577"

        [[partitions]]
        id = "west"
        owner = "server-a"
        epoch = 1
        chunk-x-max = -1

        [[partitions]]
        id = "east"
        owner = "server-b"
        epoch = 1
        chunk-x-min = 0
        """);
    return new BoundaryCrossingDetector(StaticPartitionMap.read(config), "world",
        "minecraft:overworld", 1);
  }
}
