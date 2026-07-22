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
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Slice-only static partition ownership map loaded from {@code worldline.toml}.
 */
public final class StaticPartitionMap {

  private final String levelName;
  private final String dimension;
  private final String compatibilityId;
  private final Map<String, InetSocketAddress> controlAddresses;
  private final List<Partition> partitions;

  private StaticPartitionMap(final String levelName, final String dimension,
      final String compatibilityId,
      final Map<String, InetSocketAddress> controlAddresses, final List<Partition> partitions) {
    this.levelName = levelName;
    this.dimension = dimension;
    this.compatibilityId = compatibilityId;
    this.controlAddresses = Map.copyOf(controlAddresses);
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
      String compatibilityId = require(config, "world.compatibility-id");
      Config serverConfigs = require(config, "servers");
      Map<String, InetSocketAddress> controlAddresses = new HashMap<>();
      for (Map.Entry<String, Object> entry : serverConfigs.valueMap().entrySet()) {
        if (!(entry.getValue() instanceof Config server)) {
          throw new IllegalArgumentException("Invalid server " + entry.getKey());
        }
        controlAddresses.put(entry.getKey(), parseAddress(require(server, "control-address")));
      }
      List<? extends Config> partitionConfigs = require(config, "partitions");
      List<Partition> partitions = new ArrayList<>();
      for (Config partition : partitionConfigs) {
        partitions.add(new Partition(require(partition, "id"), require(partition, "owner"),
            requireNumber(partition, "epoch").longValue(), partition.get("chunk-x-min"),
            partition.get("chunk-x-max")));
      }
      return new StaticPartitionMap(levelName, dimension, compatibilityId, controlAddresses,
          partitions);
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

  /** Returns the nearest configured partition owned by a backend. */
  public Optional<Partition> nearestPartitionOwnedBy(final String serverId, final int chunkX) {
    return partitions.stream()
        .filter(partition -> partition.owner().equals(serverId))
        .min(Comparator.comparingLong(partition -> partition.distanceFrom(chunkX)));
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

  /**
   * Returns the operator-verified registry and client-facing configuration fence.
   */
  public String compatibilityId() {
    return compatibilityId;
  }

  /**
   * Returns the experimental control endpoint for a server.
   */
  public Optional<InetSocketAddress> controlAddress(final String serverId) {
    return Optional.ofNullable(controlAddresses.get(serverId));
  }

  /**
   * Checks a partition ownership fence from a handoff envelope.
   */
  public boolean owns(final String partitionId, final String serverId, final long epoch) {
    return partitions.stream().anyMatch(partition -> partition.id().equals(partitionId)
        && partition.owner().equals(serverId) && partition.epoch() == epoch);
  }

  private static InetSocketAddress parseAddress(final String value) {
    int separator = value.lastIndexOf(':');
    if (separator <= 0 || separator == value.length() - 1) {
      throw new IllegalArgumentException("Invalid control address " + value);
    }
    int port = Integer.parseInt(value.substring(separator + 1));
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("Invalid control port " + port);
    }
    return new InetSocketAddress(value.substring(0, separator), port);
  }

  private static <T> T require(final Config config, final String path) {
    T value = config.get(path);
    if (value == null) {
      throw new IllegalArgumentException("Missing " + path);
    }
    return value;
  }

  private static Number requireNumber(final Config config, final String path) {
    Object value = require(config, path);
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException("Invalid number " + path);
    }
    return number;
  }

  /** Static slice partition and its ownership fence. */
  public record Partition(String id, String owner, long epoch, @Nullable Integer chunkMin,
                          @Nullable Integer chunkMax) {
    boolean contains(final int chunkX) {
      return (chunkMin == null || chunkX >= chunkMin)
          && (chunkMax == null || chunkX <= chunkMax);
    }

    private long distanceFrom(final int chunkX) {
      if (contains(chunkX)) {
        return 0;
      }
      if (chunkMin != null && chunkX < chunkMin) {
        return (long) chunkMin - chunkX;
      }
      return (long) chunkX - Objects.requireNonNull(chunkMax);
    }
  }
}
