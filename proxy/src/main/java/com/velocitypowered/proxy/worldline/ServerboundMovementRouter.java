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

import com.velocitypowered.proxy.protocol.packet.ServerboundMovePlayerPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Routes decoded serverbound movement for the vertical slice.
 */
public final class ServerboundMovementRouter {

  private final BoundaryCrossingDetector detector;
  // ponytail: fixed slice cap; tune from measured M8 buffer budgets.
  private final int maxBufferedPackets;
  // ponytail: fixed slice timeout; tune from measured M8 phase timings.
  private final long prepareTimeoutNanos;
  private final LongSupplier ticker;
  private final List<ServerboundMovePlayerPacket> bufferedPackets = new ArrayList<>();
  private Double lastX;
  private long prepareDeadlineNanos;
  private boolean destinationReady;
  private boolean crossingBlocked;
  private BoundaryCrossingDetector.Decision bufferedDecision = BoundaryCrossingDetector.Decision.forward();

  /**
   * Creates a movement router using shared partition-boundary logic.
   */
  public ServerboundMovementRouter(final BoundaryCrossingDetector detector) {
    this(detector, 64, TimeUnit.SECONDS.toNanos(2), System::nanoTime);
  }

  /**
   * Creates a movement router with an explicit buffer limit.
   */
  public ServerboundMovementRouter(final BoundaryCrossingDetector detector,
      final int maxBufferedPackets) {
    this(detector, maxBufferedPackets, TimeUnit.SECONDS.toNanos(2), System::nanoTime);
  }

  /**
   * Creates a movement router with explicit slice limits.
   */
  ServerboundMovementRouter(final BoundaryCrossingDetector detector, final int maxBufferedPackets,
      final long prepareTimeoutNanos, final LongSupplier ticker) {
    if (maxBufferedPackets < 1) {
      throw new IllegalArgumentException("maxBufferedPackets must be positive");
    }
    if (prepareTimeoutNanos < 1) {
      throw new IllegalArgumentException("prepareTimeoutNanos must be positive");
    }
    this.detector = detector;
    this.maxBufferedPackets = maxBufferedPackets;
    this.prepareTimeoutNanos = prepareTimeoutNanos;
    this.ticker = ticker;
  }

  /**
   * Classifies a movement packet. Packets without coordinates always forward.
   */
  public BoundaryCrossingDetector.Decision route(final String currentServerId,
      final ServerboundMovePlayerPacket packet) {
    if (!packet.hasPosition()) {
      if (!bufferedPackets.isEmpty()) {
        if (prepareDeadlineNanos != 0 && ticker.getAsLong() > prepareDeadlineNanos) {
          return BoundaryCrossingDetector.Decision.prepareTimeout(bufferedDecision);
        }
        if (bufferedPackets.size() >= maxBufferedPackets) {
          return BoundaryCrossingDetector.Decision.bufferLimitExceeded(bufferedDecision);
        }
        bufferedPackets.add(packet);
        return bufferedDecision;
      }
      return BoundaryCrossingDetector.Decision.forward();
    }
    BoundaryCrossingDetector.Decision decision = lastX == null
        ? detector.classifyUnknownOrigin(currentServerId, packet.getX())
        : detector.classify(currentServerId, lastX, packet.getX());
    if (crossingBlocked) {
      if (decision.action() == BoundaryCrossingDetector.Action.WITHHOLD_CROSSING) {
        return decision;
      }
      crossingBlocked = false;
      lastX = packet.getX();
      return BoundaryCrossingDetector.Decision.forward();
    }
    if (decision.action() == BoundaryCrossingDetector.Action.PREPARE) {
      if (!destinationReady && prepareDeadlineNanos == 0) {
        prepareDeadlineNanos = ticker.getAsLong() + prepareTimeoutNanos;
      }
      lastX = packet.getX();
      return decision;
    }
    if (decision.action() == BoundaryCrossingDetector.Action.WITHHOLD_CROSSING) {
      long now = ticker.getAsLong();
      if (!destinationReady && prepareDeadlineNanos == 0) {
        prepareDeadlineNanos = now + prepareTimeoutNanos;
        if (bufferedPackets.size() >= maxBufferedPackets) {
          return BoundaryCrossingDetector.Decision.bufferLimitExceeded(decision);
        }
        bufferedPackets.add(packet);
        bufferedDecision = BoundaryCrossingDetector.Decision.withholdCrossing(decision);
        return BoundaryCrossingDetector.Decision.prepareAndWithhold(decision);
      }
      if (prepareDeadlineNanos != 0 && now > prepareDeadlineNanos) {
        return BoundaryCrossingDetector.Decision.prepareTimeout(decision);
      }
      if (destinationReady && prepareDeadlineNanos == 0) {
        prepareDeadlineNanos = now + prepareTimeoutNanos;
      }
      if (bufferedPackets.size() >= maxBufferedPackets) {
        return BoundaryCrossingDetector.Decision.bufferLimitExceeded(decision);
      }
      bufferedPackets.add(packet);
      bufferedDecision = decision;
      return decision;
    }
    prepareDeadlineNanos = 0;
    lastX = packet.getX();
    return decision;
  }

  /**
   * Returns the ordered movement buffer held for the destination.
   */
  public List<ServerboundMovePlayerPacket> bufferedPackets() {
    return List.copyOf(bufferedPackets);
  }

  /**
   * Returns whether input is currently being held for destination replay.
   */
  public boolean hasBufferedInput() {
    return !bufferedPackets.isEmpty();
  }

  /**
   * Returns metadata for the held crossing input.
   */
  public BoundaryCrossingDetector.Decision bufferedDecision() {
    return bufferedDecision;
  }

  /** Stops the preparation timer after the destination has proved ready. */
  public void markDestinationReady() {
    destinationReady = true;
    prepareDeadlineNanos = bufferedPackets.isEmpty()
        ? 0 : ticker.getAsLong() + prepareTimeoutNanos;
  }

  /**
   * Returns and clears held input for destination replay.
   */
  public List<ServerboundMovePlayerPacket> drainBuffer() {
    List<ServerboundMovePlayerPacket> packets = List.copyOf(bufferedPackets);
    clearBuffer();
    return packets;
  }

  /**
   * Clears held input after an abort or completed replay.
   */
  public void clearBuffer() {
    bufferedPackets.clear();
    prepareDeadlineNanos = 0;
    destinationReady = false;
    crossingBlocked = false;
    bufferedDecision = BoundaryCrossingDetector.Decision.forward();
  }

  /** Drops remote movement after abort until the client returns to source-owned space. */
  public void blockCrossing() {
    clearBuffer();
    crossingBlocked = true;
  }

  /**
   * Creates a router when boundary detection is enabled.
   */
  public static Optional<ServerboundMovementRouter> create(
      final Optional<BoundaryCrossingDetector> detector) {
    return detector.map(ServerboundMovementRouter::new);
  }
}
