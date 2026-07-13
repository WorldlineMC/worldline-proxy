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

/**
 * Minimal vertical-slice control coordinator backed by the live session table.
 */
public final class HandoffControlPlane {

  public static final int PROTOCOL_VERSION = 1;

  private final LivePlayerSessionStore sessions;

  /**
   * Creates a control plane over the supplied live session store.
   */
  public HandoffControlPlane(final LivePlayerSessionStore sessions) {
    this.sessions = sessions;
  }

  /**
   * Runs the placeholder prepare path through destination-ready.
   */
  public LivePlayerSessionStore.TransitionResult prepare(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    LivePlayerSessionStore.TransitionResult begin = sessions.beginTransfer(envelope.playerUuid(),
        envelope.playerSessionEpoch(), envelope.sourceServerId(), envelope.transferId());
    if (begin.status() != LivePlayerSessionStore.Status.APPLIED
        && begin.status() != LivePlayerSessionStore.Status.ALREADY_APPLIED) {
      return begin;
    }
    return sessions.markDestinationReady(envelope.playerUuid(), envelope.playerSessionEpoch(),
        envelope.transferId());
  }

  /**
   * Aborts an uncommitted transfer and returns the player to source authority.
   */
  public LivePlayerSessionStore.TransitionResult abort(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    return sessions.abortTransfer(envelope.playerUuid(), envelope.playerSessionEpoch(),
        envelope.transferId(), envelope.sourceServerId());
  }

  /**
   * Records that the source froze the player at the transfer boundary.
   */
  public LivePlayerSessionStore.TransitionResult freezeSource(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    return sessions.markSourceFrozen(envelope.playerUuid(), envelope.playerSessionEpoch(),
        envelope.transferId());
  }

  /**
   * Records that the destination staged the source snapshot.
   */
  public LivePlayerSessionStore.TransitionResult stageSnapshot(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    return sessions.markSnapshotStaged(envelope.playerUuid(), envelope.playerSessionEpoch(),
        envelope.transferId());
  }

  /**
   * Commits authority to the destination.
   */
  public LivePlayerSessionStore.TransitionResult commit(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    return sessions.commit(envelope.playerUuid(), envelope.playerSessionEpoch(),
        envelope.transferId(), envelope.sourceServerId(), envelope.destinationServerId());
  }

  /**
   * Records that the destination activated the staged snapshot.
   */
  public LivePlayerSessionStore.TransitionResult activateDestination(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    return sessions.markActiveDestination(envelope.playerUuid(), envelope.playerSessionEpoch() + 1,
        envelope.transferId());
  }

  /**
   * Records that the source cleaned up its old authority.
   */
  public LivePlayerSessionStore.TransitionResult cleanSource(final ControlEnvelope envelope) {
    validateProtocol(envelope);
    return sessions.markSourceCleaned(envelope.playerUuid(), envelope.playerSessionEpoch() + 1,
        envelope.transferId());
  }

  private static void validateProtocol(final ControlEnvelope envelope) {
    if (envelope.protocolVersion() != PROTOCOL_VERSION) {
      throw new IllegalArgumentException("Unsupported Worldline control protocol "
          + envelope.protocolVersion());
    }
  }
}
