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

  public static final int PROTOCOL_VERSION = 4;
  private static final int MAX_ABORT_ATTEMPTS = 3;
  private static final int MAX_COMMIT_ATTEMPTS = 3;
  private static final Logger logger = LogManager.getLogger(HandoffControlPlane.class);

  private final LivePlayerSessionStore sessions;
  private @Nullable StaticPartitionMap partitions;
  private @Nullable ControlCommandSender sender;

  /**
   * Creates a control plane over the supplied live session store.
   */
  public HandoffControlPlane(final LivePlayerSessionStore sessions) {
    this.sessions = sessions;
  }

  HandoffControlPlane(final LivePlayerSessionStore sessions,
      final ControlCommandSender sender) {
    this.sessions = sessions;
    this.sender = sender;
  }

  /**
   * Enables fenced proxy-to-server control messages from the loaded slice map.
   */
  public void configure(final StaticPartitionMap partitions) {
    this.partitions = partitions;
    WorldlineControlTransport transport = new WorldlineControlTransport(partitions);
    this.sender = transport::send;
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
    boolean sourceAcknowledged = false;
    for (int attempt = 0; attempt < MAX_ABORT_ATTEMPTS; attempt++) {
      if (send(envelope.sourceServerId(), "ABORT_SOURCE", envelope, null, new byte[0]) != null) {
        sourceAcknowledged = true;
        break;
      }
    }
    if (!sourceAcknowledged) {
      return sessions.controlUnavailable(envelope.playerUuid());
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
  public SnapshotResult freezeSource(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    LivePlayerSessionStore.TransitionResult ownership = validateOwnership(envelope);
    if (ownership != null) {
      return new SnapshotResult(ownership, new byte[0]);
    }
    byte[] snapshot = send(envelope.sourceServerId(), "FREEZE_SOURCE", envelope,
        null, new byte[0]);
    if (snapshot == null) {
      return new SnapshotResult(sessions.controlUnavailable(envelope.playerUuid()), new byte[0]);
    }
    LivePlayerSessionStore.TransitionResult result = transition(HandoffPhase.SOURCE_FROZEN,
        envelope,
        () -> sessions.markSourceFrozen(envelope.playerUuid(), envelope.playerSessionEpoch(),
            envelope.transferId()));
    return new SnapshotResult(result, snapshot);
  }

  /**
   * Records that the destination staged the source snapshot.
   */
  public LivePlayerSessionStore.TransitionResult stageSnapshot(final ControlEnvelope envelope,
      final byte[] snapshot) {
    validateProtocol(envelope);
    LivePlayerSessionStore.TransitionResult ownership = validateOwnership(envelope);
    if (ownership != null) {
      return ownership;
    }
    if (send(envelope.destinationServerId(), "STAGE_SNAPSHOT", envelope,
        null, snapshot) == null) {
      return sessions.controlUnavailable(envelope.playerUuid());
    }
    return transition(HandoffPhase.SNAPSHOT_STAGED, envelope,
        () -> sessions.markSnapshotStaged(envelope.playerUuid(), envelope.playerSessionEpoch(),
            envelope.transferId()));
  }

  /**
   * Commits authority to the destination.
   */
  public CommitBarrierResult commit(final ControlEnvelope envelope) {
    return commit(envelope, () -> { });
  }

  /** Commits locally, starts the caller's terminal deadline, then drives the remote barrier. */
  public CommitBarrierResult commit(final ControlEnvelope envelope,
      final Runnable afterLocalCommit) {
    validateProtocol(envelope);
    Objects.requireNonNull(afterLocalCommit, "afterLocalCommit");
    LivePlayerSessionStore.TransitionResult ownership = validateOwnership(envelope);
    if (ownership != null) {
      return new CommitBarrierResult(CommitBarrierStatus.REJECTED_BEFORE_COMMIT, ownership,
          envelope, false, false);
    }
    LivePlayerSessionStore.TransitionResult result = transition(HandoffPhase.COMMITTED,
        envelope, () -> sessions.commit(envelope.playerUuid(),
            envelope.playerSessionEpoch(), envelope.transferId(), envelope.sourceServerId(),
            envelope.destinationServerId()));
    if (result.status() != LivePlayerSessionStore.Status.APPLIED
        && result.status() != LivePlayerSessionStore.Status.ALREADY_APPLIED) {
      return new CommitBarrierResult(CommitBarrierStatus.REJECTED_BEFORE_COMMIT, result,
          envelope, false, false);
    }
    afterLocalCommit.run();
    ControlEnvelope committed = committedEnvelope(envelope);
    boolean destinationAcknowledged = false;
    boolean sourceAcknowledged = false;
    for (int attempt = 0; attempt < MAX_COMMIT_ATTEMPTS; attempt++) {
      CommandOutcome destination = sendCommit(envelope.destinationServerId(),
          "COMMIT_DESTINATION", committed);
      CommandOutcome source = sendCommit(envelope.sourceServerId(), "COMMIT_SOURCE", committed);
      destinationAcknowledged |= destination == CommandOutcome.ACKNOWLEDGED;
      sourceAcknowledged |= source == CommandOutcome.ACKNOWLEDGED;
      if (destination == CommandOutcome.REJECTED || source == CommandOutcome.REJECTED) {
        return new CommitBarrierResult(CommitBarrierStatus.REJECTED_AFTER_COMMIT, result,
            committed, destinationAcknowledged, sourceAcknowledged);
      }
      if (destinationAcknowledged && sourceAcknowledged) {
        return new CommitBarrierResult(CommitBarrierStatus.COMPLETE, result, committed, true, true);
      }
    }
    return new CommitBarrierResult(CommitBarrierStatus.RETRYABLE_INCOMPLETE, result, committed,
        destinationAcknowledged, sourceAcknowledged);
  }

  /**
   * Records that the destination activated the staged snapshot.
   */
  public LivePlayerSessionStore.TransitionResult activateDestination(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    ControlEnvelope committed = committedEnvelope(envelope);
    LivePlayerSessionStore.TransitionResult rejection = beforeServerCommand(
        envelope.destinationServerId(), "ACTIVATE_DESTINATION", committed);
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
    ControlEnvelope committed = committedEnvelope(envelope);
    LivePlayerSessionStore.TransitionResult rejection = beforeServerCommand(
        envelope.sourceServerId(), "CLEAN_SOURCE", committed);
    if (rejection != null) {
      return rejection;
    }
    return transition(HandoffPhase.SOURCE_CLEANED, envelope,
        () -> sessions.markSourceCleaned(envelope.playerUuid(), envelope.playerSessionEpoch() + 1,
            envelope.transferId()));
  }

  /** Releases destination resources after a terminal post-commit failure. */
  public LivePlayerSessionStore.TransitionResult retireDestination(
      final ControlEnvelope envelope) {
    validateProtocol(envelope);
    ControlEnvelope committed = committedEnvelope(envelope);
    LivePlayerSessionStore.TransitionResult rejection = beforeServerCommand(
        envelope.destinationServerId(), "RETIRE_DESTINATION", committed);
    if (rejection != null) {
      return rejection;
    }
    return sessions.get(envelope.playerUuid())
        .map(LivePlayerSessionStore.TransitionResult::alreadyApplied)
        .orElseGet(LivePlayerSessionStore.TransitionResult::missing);
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
    return send(serverId, command, envelope, target, new byte[0]) != null;
  }

  private @Nullable byte[] send(final String serverId, final String command,
      final ControlEnvelope envelope, final @Nullable PrepareTarget target,
      final byte[] payload) {
    ControlCommandSender current = sender;
    if (current == null) {
      return new byte[0];
    }
    try {
      return current.send(serverId, command, envelope, target, payload);
    } catch (IOException e) {
      logger.warn("Worldline control command {} transfer={} server={} failed: {}", command,
          envelope.transferId(), serverId, e.getMessage());
      return null;
    }
  }

  private CommandOutcome sendCommit(final String serverId, final String command,
      final ControlEnvelope envelope) {
    ControlCommandSender current = sender;
    if (current == null) {
      return CommandOutcome.ACKNOWLEDGED;
    }
    try {
      current.send(serverId, command, envelope, null, new byte[0]);
      return CommandOutcome.ACKNOWLEDGED;
    } catch (WorldlineControlTransport.ControlRejectedException e) {
      logger.warn("Worldline commit command {} transfer={} server={} rejected: {}", command,
          envelope.transferId(), serverId, e.getMessage());
      return CommandOutcome.REJECTED;
    } catch (IOException e) {
      logger.warn("Worldline commit command {} transfer={} server={} ambiguous: {}", command,
          envelope.transferId(), serverId, e.getMessage());
      return CommandOutcome.RETRYABLE;
    }
  }

  static ControlEnvelope committedEnvelope(final ControlEnvelope envelope) {
    if (envelope.playerSessionEpoch() == Long.MAX_VALUE
        || envelope.routeGeneration() == Long.MAX_VALUE) {
      throw new IllegalArgumentException("Worldline epoch or generation overflow");
    }
    return new ControlEnvelope(envelope.protocolVersion(), envelope.transferId(),
        envelope.playerUuid(), envelope.clientConnectionId(), envelope.sourceServerId(),
        envelope.destinationServerId(), envelope.sourcePartitionId(),
        envelope.destinationPartitionId(), envelope.sourcePartitionEpoch(),
        envelope.destinationPartitionEpoch(), envelope.playerSessionEpoch() + 1,
        envelope.playerStateVersion(), envelope.routeGeneration() + 1);
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

  /** Result of freezing source authority and capturing its exact player snapshot. */
  public record SnapshotResult(LivePlayerSessionStore.TransitionResult transition,
                               byte[] snapshot) {
    public SnapshotResult {
      snapshot = snapshot.clone();
    }

    @Override
    public byte[] snapshot() {
      return snapshot.clone();
    }
  }

  /** Result of the post-local-commit two-server acknowledgement barrier. */
  public record CommitBarrierResult(CommitBarrierStatus status,
                                    LivePlayerSessionStore.TransitionResult transition,
                                    ControlEnvelope committedEnvelope,
                                    boolean destinationAcknowledged,
                                    boolean sourceAcknowledged) {
  }

  /** Exhaustive outcomes of attempting the two-server commit barrier. */
  public enum CommitBarrierStatus {
    COMPLETE,
    RETRYABLE_INCOMPLETE,
    REJECTED_BEFORE_COMMIT,
    REJECTED_AFTER_COMMIT
  }

  private enum CommandOutcome {
    ACKNOWLEDGED,
    RETRYABLE,
    REJECTED
  }

  /** Injectable command seam used to prove partial commit permutations. */
  @FunctionalInterface
  interface ControlCommandSender {
    byte[] send(String serverId, String command, ControlEnvelope envelope,
        @Nullable PrepareTarget target, byte[] payload) throws IOException;
  }
}
