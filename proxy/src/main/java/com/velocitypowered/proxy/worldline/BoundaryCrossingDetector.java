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

import java.util.Optional;

/**
 * Decides whether decoded movement should be forwarded or held for handoff.
 */
public final class BoundaryCrossingDetector {

  private final StaticPartitionMap partitions;
  private final String levelName;
  private final String dimension;
  private final int prepareDistanceChunks;

  /**
   * Creates a movement-boundary detector.
   */
  public BoundaryCrossingDetector(final StaticPartitionMap partitions, final String levelName,
      final String dimension, final int prepareDistanceChunks) {
    if (prepareDistanceChunks < 0) {
      throw new IllegalArgumentException("prepareDistanceChunks must be non-negative");
    }
    this.partitions = partitions;
    this.levelName = levelName;
    this.dimension = dimension;
    this.prepareDistanceChunks = prepareDistanceChunks;
  }

  /**
   * Classifies a movement from one X coordinate to another.
   */
  public Decision classify(final String currentServerId, final double fromX, final double toX) {
    int fromChunk = blockToChunk(fromX);
    int toChunk = blockToChunk(toX);
    Optional<StaticPartitionMap.Partition> current =
        partitions.partitionFor(levelName, dimension, fromChunk);
    Optional<StaticPartitionMap.Partition> target =
        partitions.partitionFor(levelName, dimension, toChunk);
    if (current.isEmpty() || target.isEmpty()
        || !current.orElseThrow().owner().equals(currentServerId)) {
      return Decision.forward();
    }
    if (!target.orElseThrow().owner().equals(currentServerId)) {
      return Decision.withholdCrossing(current.orElseThrow(), target.orElseThrow());
    }
    return nearestRemotePartition(currentServerId, toChunk)
        .map(remote -> Decision.prepare(target.orElseThrow(), remote))
        .orElseGet(Decision::forward);
  }

  private Optional<StaticPartitionMap.Partition> nearestRemotePartition(
      final String currentServerId, final int chunk) {
    for (int offset = 1; offset <= prepareDistanceChunks; offset++) {
      Optional<StaticPartitionMap.Partition> west =
          partitions.partitionFor(levelName, dimension, chunk - offset);
      if (west.isPresent() && !west.orElseThrow().owner().equals(currentServerId)) {
        return west;
      }
      Optional<StaticPartitionMap.Partition> east =
          partitions.partitionFor(levelName, dimension, chunk + offset);
      if (east.isPresent() && !east.orElseThrow().owner().equals(currentServerId)) {
        return east;
      }
    }
    return Optional.empty();
  }

  static int blockToChunk(final double blockX) {
    return Math.floorDiv((int) Math.floor(blockX), 16);
  }

  /**
   * Movement routing decision.
   */
  public record Decision(Action action, Optional<String> sourcePartitionId,
                         Optional<String> remotePartitionId, Optional<String> remoteOwner,
                         long sourcePartitionEpoch, long remotePartitionEpoch) {

    static Decision forward() {
      return new Decision(Action.FORWARD, Optional.empty(), Optional.empty(), Optional.empty(),
          0, 0);
    }

    static Decision prepare(final StaticPartitionMap.Partition source,
        final StaticPartitionMap.Partition remote) {
      return new Decision(Action.PREPARE, Optional.of(source.id()), Optional.of(remote.id()),
          Optional.of(remote.owner()), source.epoch(), remote.epoch());
    }

    static Decision withholdCrossing(final StaticPartitionMap.Partition source,
        final StaticPartitionMap.Partition remote) {
      return new Decision(Action.WITHHOLD_CROSSING, Optional.of(source.id()),
          Optional.of(remote.id()), Optional.of(remote.owner()), source.epoch(), remote.epoch());
    }

    static Decision bufferLimitExceeded(final Decision decision) {
      return new Decision(Action.BUFFER_LIMIT_EXCEEDED, decision.sourcePartitionId(),
          decision.remotePartitionId(), decision.remoteOwner(), decision.sourcePartitionEpoch(),
          decision.remotePartitionEpoch());
    }

    static Decision prepareTimeout(final Decision decision) {
      return new Decision(Action.PREPARE_TIMEOUT, decision.sourcePartitionId(),
          decision.remotePartitionId(), decision.remoteOwner(), decision.sourcePartitionEpoch(),
          decision.remotePartitionEpoch());
    }

    static Decision prepareNotReady(final Decision decision) {
      return new Decision(Action.PREPARE_NOT_READY, decision.sourcePartitionId(),
          decision.remotePartitionId(), decision.remoteOwner(), decision.sourcePartitionEpoch(),
          decision.remotePartitionEpoch());
    }
  }

  /**
   * Serverbound movement action.
   */
  public enum Action {
    FORWARD,
    PREPARE,
    WITHHOLD_CROSSING,
    BUFFER_LIMIT_EXCEEDED,
    PREPARE_TIMEOUT,
    PREPARE_NOT_READY
  }
}
