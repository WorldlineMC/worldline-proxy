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

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Minimal vertical-slice control coordinator backed by the live session table.
 */
public final class HandoffControlPlane {

  public static final int PROTOCOL_VERSION = 2;
  private static final Logger logger = LogManager.getLogger(HandoffControlPlane.class);

  private final LivePlayerSessionStore sessions;
  private @Nullable StaticPartitionMap partitions;
  private @Nullable WorldlineControlTransport transport;

  /**
   * Creates a control plane over the supplied live session store.
   */
  public HandoffControlPlane(final LivePlayerSessionStore sessions) {
    this.sessions = sessions;
  }

  /**
   * Enables fenced proxy-to-server control messages from the loaded slice map.
   */
  public void configure(final StaticPartitionMap partitions) {
    this.partitions = partitions;
    this.transport = new WorldlineControlTransport(partitions);
  }

  /** Builds the destination resources request from the loaded slice config. */
  public PrepareTarget prepareTarget(final String playerName, final double x, final double y,
      final double z) {
    StaticPartitionMap current = Objects.requireNonNull(partitions,
        "Worldline control plane is not configured");
    return new PrepareTarget(playerName, current.levelName(), current.dimension(),
        current.compatibilityId(), x, y, z, 1);
  }

  /** Runs destination preparation through destination-ready. */
  public LivePlayerSessionStore.TransitionResult prepare(final ControlEnvelope envelope,
      final PrepareTarget target) {
    validateProtocol(envelope);
    LivePlayerSessionStore.TransitionResult ownership = validateOwnership(envelope);
    if (ownership != null) {
      return ownership;
    }
    LivePlayerSessionStore.TransitionResult begin = transition(
        HandoffPhase.PREPARING_DESTINATION, envelope,
        () -> sessions.beginTransfer(envelope.playerUuid(), envelope.playerSessionEpoch(),
            envelope.sourceServerId(), envelope.transferId()));
    if (begin.status() != LivePlayerSessionStore.Status.APPLIED
        && begin.status() != LivePlayerSessionStore.Status.ALREADY_APPLIED) {
      return begin;
    }
    try {
      if (!send(envelope.sourceServerId(), "CHECK_PREPARE", envelope, target)
          || !send(envelope.destinationServerId(), "PREPARE", envelope, target)) {
        discardPreparation(envelope);
        return sessions.controlUnavailable(envelope.playerUuid());
      }
      LivePlayerSessionStore.TransitionResult ready = transition(HandoffPhase.DESTINATION_READY,
          envelope,
          () -> sessions.markDestinationReady(envelope.playerUuid(),
              envelope.playerSessionEpoch(), envelope.transferId()));
      if (ready.status() != LivePlayerSessionStore.Status.APPLIED
          && ready.status() != LivePlayerSessionStore.Status.ALREADY_APPLIED) {
        discardPreparation(envelope);
      }
      return ready;
    } catch (RuntimeException e) {
      discardPreparation(envelope);
      throw e;
    }
  }

  /**
   * Aborts an uncommitted transfer and returns the player to source authority.
   */
  public LivePlayerSessionStore.TransitionResult abort(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    LivePlayerSessionStore.TransitionResult ownership = validateOwnership(envelope);
    if (ownership != null) {
      return ownership;
    }
    LivePlayerSessionStore.TransitionResult result = transition(HandoffPhase.ACTIVE_SOURCE,
        envelope,
        () -> sessions.abortTransfer(envelope.playerUuid(), envelope.playerSessionEpoch(),
            envelope.transferId(), envelope.sourceServerId()));
    send(envelope.destinationServerId(), "ABORT", envelope);
    return result;
  }

  /**
   * Records that the source froze the player at the transfer boundary.
   */
  public LivePlayerSessionStore.TransitionResult freezeSource(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    LivePlayerSessionStore.TransitionResult rejection = beforeServerCommand(
        envelope.sourceServerId(), "FREEZE_SOURCE", envelope);
    if (rejection != null) {
      return rejection;
    }
    return transition(HandoffPhase.SOURCE_FROZEN, envelope,
        () -> sessions.markSourceFrozen(envelope.playerUuid(), envelope.playerSessionEpoch(),
            envelope.transferId()));
  }

  /**
   * Records that the destination staged the source snapshot.
   */
  public LivePlayerSessionStore.TransitionResult stageSnapshot(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    LivePlayerSessionStore.TransitionResult rejection = beforeServerCommand(
        envelope.destinationServerId(), "STAGE_SNAPSHOT", envelope);
    if (rejection != null) {
      return rejection;
    }
    return transition(HandoffPhase.SNAPSHOT_STAGED, envelope,
        () -> sessions.markSnapshotStaged(envelope.playerUuid(), envelope.playerSessionEpoch(),
            envelope.transferId()));
  }

  /**
   * Commits authority to the destination.
   */
  public LivePlayerSessionStore.TransitionResult commit(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    LivePlayerSessionStore.TransitionResult ownership = validateOwnership(envelope);
    if (ownership != null) {
      return ownership;
    }
    LivePlayerSessionStore.TransitionResult result = transition(HandoffPhase.COMMITTED,
        envelope, () -> sessions.commit(envelope.playerUuid(),
            envelope.playerSessionEpoch(), envelope.transferId(), envelope.sourceServerId(),
            envelope.destinationServerId()));
    if (result.status() == LivePlayerSessionStore.Status.APPLIED
        || result.status() == LivePlayerSessionStore.Status.ALREADY_APPLIED) {
      send(envelope.destinationServerId(), "COMMIT", envelope);
    }
    return result;
  }

  /**
   * Records that the destination activated the staged snapshot.
   */
  public LivePlayerSessionStore.TransitionResult activateDestination(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    LivePlayerSessionStore.TransitionResult rejection = beforeServerCommand(
        envelope.destinationServerId(), "ACTIVATE_DESTINATION", envelope);
    if (rejection != null) {
      return rejection;
    }
    return transition(HandoffPhase.ACTIVE_DESTINATION, envelope,
        () -> sessions.markActiveDestination(envelope.playerUuid(),
            envelope.playerSessionEpoch() + 1, envelope.transferId()));
  }

  /**
   * Records that the source cleaned up its old authority.
   */
  public LivePlayerSessionStore.TransitionResult cleanSource(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    LivePlayerSessionStore.TransitionResult rejection = beforeServerCommand(
        envelope.sourceServerId(), "CLEAN_SOURCE", envelope);
    if (rejection != null) {
      return rejection;
    }
    return transition(HandoffPhase.SOURCE_CLEANED, envelope,
        () -> sessions.markSourceCleaned(envelope.playerUuid(), envelope.playerSessionEpoch() + 1,
            envelope.transferId()));
  }

  private LivePlayerSessionStore.TransitionResult transition(final HandoffPhase phase,
      final ControlEnvelope envelope,
      final Supplier<LivePlayerSessionStore.TransitionResult> operation) {
    UUID playerUuid = envelope.playerUuid();
    String configured = System.getProperty(
        "worldline.failure." + phase.name().toLowerCase(Locale.ROOT), "");
    if (!configured.isEmpty() && !configured.equals("drop") && !configured.equals("duplicate")
        && !configured.equals("crash") && !configured.startsWith("delay:")) {
      throw new IllegalArgumentException("Invalid Worldline failure mode " + configured);
    }
    if (configured.equals("drop")) {
      logger.info("Worldline injected drop before phase={} player={}", phase, playerUuid);
      return sessions.injectedDrop(playerUuid);
    }
    if (configured.startsWith("delay:")) {
      long delayMillis = Long.parseLong(configured.substring("delay:".length()));
      if (delayMillis < 0 || delayMillis > 60_000) {
        throw new IllegalArgumentException("Invalid Worldline failure delay " + delayMillis);
      }
      logger.info("Worldline injected delay_ms={} before phase={} player={}", delayMillis,
          phase, playerUuid);
      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted Worldline failure delay", e);
      }
    }
    if (configured.equals("crash")) {
      logger.info("Worldline injected crash before phase={} player={}", phase, playerUuid);
      throw new IllegalStateException("Injected Worldline crash before " + phase);
    }
    LivePlayerSessionStore.TransitionResult first = operation.get();
    if (configured.equals("duplicate")
        && first.status() == LivePlayerSessionStore.Status.APPLIED) {
      LivePlayerSessionStore.TransitionResult duplicate = operation.get();
      if (duplicate.status() != LivePlayerSessionStore.Status.ALREADY_APPLIED) {
        throw new IllegalStateException("Injected duplicate was not idempotent for " + phase);
      }
      logger.info("Worldline injected duplicate phase={} player={}", phase, playerUuid);
    }
    if (first.status() == LivePlayerSessionStore.Status.APPLIED) {
      LivePlayerSession before = first.before().orElseThrow();
      LivePlayerSession after = first.after().orElseThrow();
      logger.info("Worldline handoff transfer_id={} player_uuid={} source={} destination={} "
              + "previous_phase={} next_phase={} epoch={} elapsed_phase_ms={} "
              + "source_partition={} source_partition_epoch={} destination_partition={} "
              + "destination_partition_epoch={}",
          envelope.transferId(), envelope.playerUuid(), envelope.sourceServerId(),
          envelope.destinationServerId(), before.handoffPhase(), after.handoffPhase(),
          after.playerSessionEpoch(), first.elapsedPhaseNanos() / 1_000_000.0,
          envelope.sourcePartitionId(), envelope.sourcePartitionEpoch(),
          envelope.destinationPartitionId(), envelope.destinationPartitionEpoch());
    }
    return first;
  }

  private LivePlayerSessionStore.TransitionResult beforeServerCommand(
      final String serverId, final String command, final ControlEnvelope envelope) {
    LivePlayerSessionStore.TransitionResult ownership = validateOwnership(envelope);
    if (ownership != null) {
      return ownership;
    }
    return send(serverId, command, envelope)
        ? null : sessions.controlUnavailable(envelope.playerUuid());
  }

  private LivePlayerSessionStore.TransitionResult validateOwnership(
      final ControlEnvelope envelope) {
    StaticPartitionMap current = partitions;
    if (current == null) {
      return null;
    }
    if (!current.owns(envelope.sourcePartitionId(), envelope.sourceServerId(),
        envelope.sourcePartitionEpoch())
        || !current.owns(envelope.destinationPartitionId(), envelope.destinationServerId(),
        envelope.destinationPartitionEpoch())) {
      return sessions.rejectedPartitionEpoch(envelope.playerUuid());
    }
    return null;
  }

  private boolean send(final String serverId, final String command,
      final ControlEnvelope envelope) {
    return send(serverId, command, envelope, null);
  }

  private boolean send(final String serverId, final String command,
      final ControlEnvelope envelope, final @Nullable PrepareTarget target) {
    WorldlineControlTransport current = transport;
    if (current == null) {
      return true;
    }
    try {
      current.send(serverId, command, envelope, target);
      return true;
    } catch (IOException e) {
      logger.warn("Worldline control command {} transfer={} server={} failed: {}", command,
          envelope.transferId(), serverId, e.getMessage());
      return false;
    }
  }

  private void discardPreparation(final ControlEnvelope envelope) {
    transition(HandoffPhase.ACTIVE_SOURCE, envelope,
        () -> sessions.abortTransfer(envelope.playerUuid(), envelope.playerSessionEpoch(),
            envelope.transferId(), envelope.sourceServerId()));
    send(envelope.destinationServerId(), "ABORT", envelope);
  }

  private static void validateProtocol(final ControlEnvelope envelope) {
    if (envelope.protocolVersion() != PROTOCOL_VERSION) {
      throw new IllegalArgumentException("Unsupported Worldline control protocol "
          + envelope.protocolVersion());
    }
  }
}
