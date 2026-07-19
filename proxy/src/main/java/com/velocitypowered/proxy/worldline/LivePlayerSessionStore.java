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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * In-memory live player-session authority table for the vertical slice.
 */
public final class LivePlayerSessionStore {

  private final Map<UUID, LivePlayerSession> sessions = new ConcurrentHashMap<>();
  private final Map<UUID, Long> phaseEnteredNanos = new ConcurrentHashMap<>();
  private final TransitionHook transitionHook;

  /**
   * Creates a store with no injected transition failures.
   */
  public LivePlayerSessionStore() {
    this((transitionName, before, after) -> {
    });
  }

  /**
   * Creates a store with a hook invoked before each real state transition.
   */
  public LivePlayerSessionStore(final TransitionHook transitionHook) {
    this.transitionHook = transitionHook;
  }

  /**
   * Registers a connected player as active on the supplied source server.
   */
  public void putActive(final UUID playerUuid, final UUID clientConnectionId,
      final String serverId) {
    sessions.compute(playerUuid, (ignored, current) -> {
      if (current != null && (current.activeTransferId() != null
          || current.handoffPhase() != HandoffPhase.ACTIVE_SOURCE)) {
        return current;
      }
      return new LivePlayerSession(playerUuid, clientConnectionId, serverId, 0, 0, null,
          HandoffPhase.ACTIVE_SOURCE);
    });
    phaseEnteredNanos.putIfAbsent(playerUuid, System.nanoTime());
  }

  /**
   * Returns the current live session for a player, if connected.
   */
  public Optional<LivePlayerSession> get(final UUID playerUuid) {
    return Optional.ofNullable(sessions.get(playerUuid));
  }

  /**
   * Returns whether one immutable backend connection currently owns clientbound gameplay output.
   * Control and resume-readiness traffic is consumed before a backend reaches the play handler and
   * therefore does not pass through this predicate.
   */
  public boolean mayForwardGameplay(final BackendSessionBinding binding) {
    LivePlayerSession current = sessions.get(binding.playerUuid());
    if (!matchesAuthority(current, binding)) {
      return false;
    }
    return switch (current.handoffPhase()) {
      case ACTIVE_SOURCE, PREPARING_DESTINATION, DESTINATION_READY -> true;
      case ACTIVE_DESTINATION, SOURCE_CLEANED ->
          current.activeTransferId() != null
              && current.activeTransferId().equals(binding.transferId());
      case SOURCE_FROZEN, SNAPSHOT_STAGED, COMMITTED -> false;
    };
  }

  /** Allows backend-driven client transitions only outside an active splice. */
  public boolean maySendClientTransition(final BackendSessionBinding binding) {
    LivePlayerSession current = sessions.get(binding.playerUuid());
    return matchesAuthority(current, binding)
        && current.handoffPhase() == HandoffPhase.ACTIVE_SOURCE;
  }

  private static boolean matchesAuthority(final @Nullable LivePlayerSession current,
      final BackendSessionBinding binding) {
    return current != null
        && current.clientConnectionId().equals(binding.clientConnectionId())
        && current.authoritativeServerId().equals(binding.backendServerId())
        && current.playerSessionEpoch() == binding.playerSessionEpoch()
        && current.routeGeneration() == binding.routeGeneration();
  }

  /**
   * Removes a disconnected player's live session record.
   */
  public Optional<LivePlayerSession> remove(final UUID playerUuid) {
    phaseEnteredNanos.remove(playerUuid);
    return Optional.ofNullable(sessions.remove(playerUuid));
  }

  /**
   * Removes a disconnected player's live session record when it still belongs to that connection.
   */
  public Optional<LivePlayerSession> remove(final UUID playerUuid, final UUID clientConnectionId) {
    AtomicReference<LivePlayerSession> removed = new AtomicReference<>();
    sessions.computeIfPresent(playerUuid, (ignored, current) -> {
      if (!current.clientConnectionId().equals(clientConnectionId)) {
        return current;
      }
      removed.set(current);
      phaseEnteredNanos.remove(playerUuid);
      return null;
    });
    return Optional.ofNullable(removed.get());
  }

  /**
   * Starts a transfer if the player is still active on the expected source and epoch.
   */
  public TransitionResult beginTransfer(final UUID playerUuid, final long expectedEpoch,
      final String expectedSourceServerId, final UUID transferId) {
    AtomicReference<TransitionResult> result = new AtomicReference<>();
    sessions.compute(playerUuid, (ignored, current) -> {
      if (current == null) {
        result.set(TransitionResult.missing());
        return null;
      }
      if (current.playerSessionEpoch() != expectedEpoch) {
        result.set(TransitionResult.rejectedStaleEpoch(current));
        return current;
      }
      if (current.activeTransferId() != null) {
        if (current.activeTransferId().equals(transferId)) {
          result.set(TransitionResult.alreadyApplied(current));
        } else {
          result.set(TransitionResult.rejectedMismatch(current));
        }
        return current;
      }
      if (!current.authoritativeServerId().equals(expectedSourceServerId)
          || !current.handoffPhase().canTransitionTo(HandoffPhase.PREPARING_DESTINATION)) {
        result.set(TransitionResult.rejectedMismatch(current));
        return current;
      }
      LivePlayerSession next = new LivePlayerSession(current.playerUuid(),
          current.clientConnectionId(), current.authoritativeServerId(), current.playerSessionEpoch(),
          current.routeGeneration(), transferId, HandoffPhase.PREPARING_DESTINATION);
      return apply("beginTransfer", current, next, result);
    });
    return result.get();
  }

  /**
   * Advances a prepared destination to ready.
   */
  public TransitionResult markDestinationReady(final UUID playerUuid, final long expectedEpoch,
      final UUID transferId) {
    return phase(playerUuid, expectedEpoch, transferId, HandoffPhase.PREPARING_DESTINATION,
        HandoffPhase.DESTINATION_READY);
  }

  /**
   * Records that the source froze the player at the transfer boundary.
   */
  public TransitionResult markSourceFrozen(final UUID playerUuid, final long expectedEpoch,
      final UUID transferId) {
    return phase(playerUuid, expectedEpoch, transferId, HandoffPhase.DESTINATION_READY,
        HandoffPhase.SOURCE_FROZEN);
  }

  /**
   * Records that the destination staged the final source snapshot.
   */
  public TransitionResult markSnapshotStaged(final UUID playerUuid, final long expectedEpoch,
      final UUID transferId) {
    return phase(playerUuid, expectedEpoch, transferId, HandoffPhase.SOURCE_FROZEN,
        HandoffPhase.SNAPSHOT_STAGED);
  }

  /**
   * Records that the destination activated the committed snapshot.
   */
  public TransitionResult markActiveDestination(final UUID playerUuid, final long expectedEpoch,
      final UUID transferId) {
    return phase(playerUuid, expectedEpoch, transferId, HandoffPhase.COMMITTED,
        HandoffPhase.ACTIVE_DESTINATION);
  }

  /**
   * Records that the source cleaned up the old epoch after commit.
   */
  public TransitionResult markSourceCleaned(final UUID playerUuid, final long expectedEpoch,
      final UUID transferId) {
    return phase(playerUuid, expectedEpoch, transferId, HandoffPhase.ACTIVE_DESTINATION,
        HandoffPhase.SOURCE_CLEANED);
  }

  /** Retires terminal transfer identity while preserving destination authority and fences. */
  public TransitionResult retireTransfer(final UUID playerUuid, final long expectedEpoch,
      final UUID transferId, final String expectedDestinationServerId) {
    AtomicReference<TransitionResult> result = new AtomicReference<>();
    sessions.compute(playerUuid, (ignored, current) -> {
      if (current == null) {
        result.set(TransitionResult.missing());
        return null;
      }
      if (current.playerSessionEpoch() != expectedEpoch) {
        result.set(TransitionResult.rejectedStaleEpoch(current));
        return current;
      }
      if (current.activeTransferId() == null
          && current.authoritativeServerId().equals(expectedDestinationServerId)
          && current.handoffPhase() == HandoffPhase.ACTIVE_SOURCE) {
        result.set(TransitionResult.alreadyApplied(current));
        return current;
      }
      if (!transferId.equals(current.activeTransferId())
          || !current.authoritativeServerId().equals(expectedDestinationServerId)
          || current.handoffPhase() != HandoffPhase.SOURCE_CLEANED
          || !current.handoffPhase().canTransitionTo(HandoffPhase.ACTIVE_SOURCE)) {
        result.set(TransitionResult.rejectedMismatch(current));
        return current;
      }
      LivePlayerSession next = new LivePlayerSession(current.playerUuid(),
          current.clientConnectionId(), current.authoritativeServerId(), current.playerSessionEpoch(),
          current.routeGeneration(), null, HandoffPhase.ACTIVE_SOURCE);
      return apply("retireTransfer", current, next, result);
    });
    return result.get();
  }

  /**
   * Aborts an uncommitted transfer and restores source authority at the same epoch.
   */
  public TransitionResult abortTransfer(final UUID playerUuid, final long expectedEpoch,
      final UUID transferId, final String expectedSourceServerId) {
    AtomicReference<TransitionResult> result = new AtomicReference<>();
    sessions.compute(playerUuid, (ignored, current) -> {
      if (current == null) {
        result.set(TransitionResult.missing());
        return null;
      }
      if (current.playerSessionEpoch() != expectedEpoch) {
        result.set(TransitionResult.rejectedStaleEpoch(current));
        return current;
      }
      if (current.activeTransferId() == null
          && current.authoritativeServerId().equals(expectedSourceServerId)
          && current.handoffPhase() == HandoffPhase.ACTIVE_SOURCE) {
        result.set(TransitionResult.alreadyApplied(current));
        return current;
      }
      if (!transferId.equals(current.activeTransferId())
          || !current.authoritativeServerId().equals(expectedSourceServerId)
          || !current.handoffPhase().canTransitionTo(HandoffPhase.ACTIVE_SOURCE)) {
        result.set(TransitionResult.rejectedMismatch(current));
        return current;
      }
      LivePlayerSession next = new LivePlayerSession(current.playerUuid(),
          current.clientConnectionId(), current.authoritativeServerId(), current.playerSessionEpoch(),
          current.routeGeneration(), null, HandoffPhase.ACTIVE_SOURCE);
      return apply("abortTransfer", current, next, result);
    });
    return result.get();
  }

  /**
   * Atomically moves authority from source to destination and increments the session epoch.
   */
  public TransitionResult commit(final UUID playerUuid, final long expectedEpoch,
      final UUID transferId, final String expectedSourceServerId,
      final String destinationServerId) {
    AtomicReference<TransitionResult> result = new AtomicReference<>();
    sessions.compute(playerUuid, (ignored, current) -> {
      if (current == null) {
        result.set(TransitionResult.missing());
        return null;
      }
      if (current.activeTransferId() != null && current.activeTransferId().equals(transferId)
          && current.playerSessionEpoch() == expectedEpoch + 1
          && current.authoritativeServerId().equals(destinationServerId)
          && (current.handoffPhase() == HandoffPhase.COMMITTED
              || current.handoffPhase() == HandoffPhase.ACTIVE_DESTINATION
              || current.handoffPhase() == HandoffPhase.SOURCE_CLEANED)) {
        result.set(TransitionResult.alreadyApplied(current));
        return current;
      }
      if (current.playerSessionEpoch() != expectedEpoch) {
        result.set(TransitionResult.rejectedStaleEpoch(current));
        return current;
      }
      if (!current.authoritativeServerId().equals(expectedSourceServerId)
          || !transferId.equals(current.activeTransferId())
          || !current.handoffPhase().canTransitionTo(HandoffPhase.COMMITTED)) {
        result.set(TransitionResult.rejectedMismatch(current));
        return current;
      }
      LivePlayerSession next = new LivePlayerSession(current.playerUuid(),
          current.clientConnectionId(), destinationServerId, expectedEpoch + 1,
          current.routeGeneration() + 1, transferId, HandoffPhase.COMMITTED);
      return apply("commit", current, next, result);
    });
    return result.get();
  }

  private TransitionResult phase(final UUID playerUuid, final long expectedEpoch,
      final UUID transferId, final HandoffPhase expectedPhase, final HandoffPhase nextPhase) {
    AtomicReference<TransitionResult> result = new AtomicReference<>();
    sessions.compute(playerUuid, (ignored, current) -> {
      if (current == null) {
        result.set(TransitionResult.missing());
        return null;
      }
      if (current.playerSessionEpoch() != expectedEpoch) {
        result.set(TransitionResult.rejectedStaleEpoch(current));
        return current;
      }
      if (transferId.equals(current.activeTransferId())
          && current.handoffPhase().ordinal() >= nextPhase.ordinal()) {
        result.set(TransitionResult.alreadyApplied(current));
        return current;
      }
      if (!transferId.equals(current.activeTransferId()) || current.handoffPhase() != expectedPhase
          || !current.handoffPhase().canTransitionTo(nextPhase)) {
        result.set(TransitionResult.rejectedMismatch(current));
        return current;
      }
      LivePlayerSession next = new LivePlayerSession(current.playerUuid(),
          current.clientConnectionId(), current.authoritativeServerId(), current.playerSessionEpoch(),
          current.routeGeneration(), transferId, nextPhase);
      return apply(nextPhase.name(), current, next, result);
    });
    return result.get();
  }

  private LivePlayerSession apply(final String transitionName, final LivePlayerSession before,
      final LivePlayerSession after, final AtomicReference<TransitionResult> result) {
    transitionHook.beforeTransition(transitionName, before, after);
    long now = System.nanoTime();
    long elapsedNanos = now - phaseEnteredNanos.getOrDefault(before.playerUuid(), now);
    phaseEnteredNanos.put(before.playerUuid(), now);
    result.set(TransitionResult.applied(before, after, elapsedNanos));
    return after;
  }

  TransitionResult rejectedPartitionEpoch(final UUID playerUuid) {
    return get(playerUuid).map(TransitionResult::rejectedPartitionEpoch)
        .orElseGet(TransitionResult::missing);
  }

  TransitionResult controlUnavailable(final UUID playerUuid) {
    return get(playerUuid).map(TransitionResult::controlUnavailable)
        .orElseGet(TransitionResult::missing);
  }

  TransitionResult injectedDrop(final UUID playerUuid) {
    return get(playerUuid).map(TransitionResult::injectedDrop)
        .orElseGet(TransitionResult::missing);
  }

  /**
   * Hook used by the vertical-slice failure matrix to inject transition failures.
   */
  @FunctionalInterface
  public interface TransitionHook {

    /**
     * Runs before the transition is committed to the in-memory session table.
     */
    void beforeTransition(String transitionName, LivePlayerSession before, LivePlayerSession after);
  }

  /**
   * Result of an attempted conditional live-session transition.
   */
  public record TransitionResult(Status status, Optional<LivePlayerSession> before,
                                 Optional<LivePlayerSession> after, long elapsedPhaseNanos) {
    static TransitionResult applied(final LivePlayerSession before, final LivePlayerSession after,
        final long elapsedPhaseNanos) {
      return new TransitionResult(Status.APPLIED, Optional.of(before), Optional.of(after),
          elapsedPhaseNanos);
    }

    static TransitionResult alreadyApplied(final LivePlayerSession current) {
      return unchanged(Status.ALREADY_APPLIED, current);
    }

    static TransitionResult rejectedStaleEpoch(final LivePlayerSession current) {
      return unchanged(Status.REJECTED_STALE_EPOCH, current);
    }

    static TransitionResult rejectedMismatch(final LivePlayerSession current) {
      return unchanged(Status.REJECTED_MISMATCH, current);
    }

    static TransitionResult rejectedPartitionEpoch(final LivePlayerSession current) {
      return unchanged(Status.REJECTED_PARTITION_EPOCH, current);
    }

    static TransitionResult controlUnavailable(final LivePlayerSession current) {
      return unchanged(Status.CONTROL_UNAVAILABLE, current);
    }

    static TransitionResult injectedDrop(final LivePlayerSession current) {
      return unchanged(Status.INJECTED_DROP, current);
    }

    static TransitionResult missing() {
      return new TransitionResult(Status.MISSING_SESSION, Optional.empty(), Optional.empty(), 0);
    }

    private static TransitionResult unchanged(final Status status,
        final LivePlayerSession current) {
      return new TransitionResult(status, Optional.of(current), Optional.of(current), 0);
    }
  }

  /**
   * Conditional transition outcome.
   */
  public enum Status {
    APPLIED,
    ALREADY_APPLIED,
    REJECTED_STALE_EPOCH,
    REJECTED_PARTITION_EPOCH,
    REJECTED_MISMATCH,
    CONTROL_UNAVAILABLE,
    INJECTED_DROP,
    MISSING_SESSION
  }
}
