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

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Slice-only static partition ownership map loaded from {@code worldline.toml}.
 */
public final class StaticPartitionMap {

  private final String levelName;
  private final String dimension;
  private final List<Partition> partitions;

  private StaticPartitionMap(final String levelName, final String dimension,
      final List<Partition> partitions) {
    this.levelName = levelName;
    this.dimension = dimension;
    this.partitions = List.copyOf(partitions);
  }

  /**
   * Loads a static partition map from disk.
   */
  public static StaticPartitionMap read(final Path path) throws IOException {
    try (CommentedFileConfig config = CommentedFileConfig.builder(path).build()) {
      config.load();
      String levelName = require(config, "world.level-name");
      String dimension = require(config, "world.dimension");
      List<? extends Config> partitionConfigs = require(config, "partitions");
      List<Partition> partitions = new ArrayList<>();
      for (Config partition : partitionConfigs) {
        partitions.add(new Partition(require(partition, "id"), require(partition, "owner"),
            partition.get("chunk-x-min"), partition.get("chunk-x-max")));
      }
      return new StaticPartitionMap(levelName, dimension, partitions);
    } catch (RuntimeException e) {
      throw new IOException("Invalid Worldline partition map: " + path, e);
    }
  }

  /**
   * Returns the owner for a chunk if the world, dimension, and bounds match.
   */
  public Optional<String> ownerFor(final String levelName, final String dimension,
      final int chunkX) {
    return partitionFor(levelName, dimension, chunkX).map(Partition::owner);
  }

  /**
   * Returns the partition for a chunk if the world, dimension, and bounds match.
   */
  public Optional<Partition> partitionFor(final String levelName, final String dimension,
      final int chunkX) {
    if (!this.levelName.equals(levelName) || !this.dimension.equals(dimension)) {
      return Optional.empty();
    }
    return partitions.stream()
        .filter(partition -> partition.contains(chunkX))
        .findFirst();
  }

  /**
   * Returns the configured level name.
   */
  public String levelName() {
    return levelName;
  }

  /**
   * Returns the configured dimension key.
   */
  public String dimension() {
    return dimension;
  }

  private static <T> T require(final Config config, final String path) {
    T value = config.get(path);
    if (value == null) {
      throw new IllegalArgumentException("Missing " + path);
    }
    return value;
  }

  public record Partition(String id, String owner, @Nullable Integer chunkMin,
                          @Nullable Integer chunkMax) {
    boolean contains(final int chunkX) {
      return (chunkMin == null || chunkX >= chunkMin)
          && (chunkMax == null || chunkX <= chunkMax);
    }
  }
}
