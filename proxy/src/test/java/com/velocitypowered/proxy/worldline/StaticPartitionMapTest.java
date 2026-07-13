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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the slice static partition map.
 */
public class StaticPartitionMapTest {

  @TempDir
  Path tempDir;

  @Test
  void resolvesChunkOwnership() throws Exception {
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

    StaticPartitionMap map = StaticPartitionMap.read(config);

    assertEquals("server-a", map.ownerFor("world", "minecraft:overworld", -1).orElseThrow());
    assertEquals("server-b", map.ownerFor("world", "minecraft:overworld", 0).orElseThrow());
    assertEquals("west", map.partitionFor("world", "minecraft:overworld", -1).orElseThrow().id());
    assertEquals("east", map.partitionFor("world", "minecraft:overworld", 0).orElseThrow().id());
    assertTrue(map.ownerFor("other", "minecraft:overworld", 0).isEmpty());
  }
}
