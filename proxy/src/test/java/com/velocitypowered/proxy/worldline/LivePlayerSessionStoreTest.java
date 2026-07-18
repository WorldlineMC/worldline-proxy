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

import static com.velocitypowered.proxy.worldline.LivePlayerSessionStore.Status.ALREADY_APPLIED;
import static com.velocitypowered.proxy.worldline.LivePlayerSessionStore.Status.APPLIED;
import static com.velocitypowered.proxy.worldline.LivePlayerSessionStore.Status.REJECTED_MISMATCH;
import static com.velocitypowered.proxy.worldline.LivePlayerSessionStore.Status.REJECTED_STALE_EPOCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Worldline live player-session authority table.
 */
public class LivePlayerSessionStoreTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID TRANSFER = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Test
  void enforcesOneActiveTransfer() {
    LivePlayerSessionStore store = new LivePlayerSessionStore();
    store.putActive(PLAYER, CLIENT, "server-a");

    assertEquals(APPLIED, store.beginTransfer(PLAYER, 0, "server-a", TRANSFER).status());
    assertEquals(ALREADY_APPLIED, store.beginTransfer(PLAYER, 0, "server-a", TRANSFER).status());
    assertEquals(REJECTED_MISMATCH, store.beginTransfer(PLAYER, 0, "server-a",
        UUID.fromString("00000000-0000-0000-0000-000000000004")).status());
  }

  @Test
  void putActiveDoesNotOverwriteExistingSession() {
    UUID newerClient = UUID.fromString("00000000-0000-0000-0000-000000000004");
    LivePlayerSessionStore store = new LivePlayerSessionStore();
    store.putActive(PLAYER, CLIENT, "server-a");
    store.beginTransfer(PLAYER, 0, "server-a", TRANSFER);

    store.putActive(PLAYER, newerClient, "server-b");

    LivePlayerSession session = store.get(PLAYER).orElseThrow();
    assertEquals(CLIENT, session.clientConnectionId());
    assertEquals("server-a", session.authoritativeServerId());
    assertEquals(TRANSFER, session.activeTransferId());
    assertEquals(HandoffPhase.PREPARING_DESTINATION, session.handoffPhase());
  }

  @Test
  void putActiveReplacesIdleSourceSession() {
    UUID newerClient = UUID.fromString("00000000-0000-0000-0000-000000000004");
    LivePlayerSessionStore store = new LivePlayerSessionStore();
    store.putActive(PLAYER, CLIENT, "server-a");

    store.putActive(PLAYER, newerClient, "server-b");

    LivePlayerSession session = store.get(PLAYER).orElseThrow();
    assertEquals(newerClient, session.clientConnectionId());
    assertEquals("server-b", session.authoritativeServerId());
    assertEquals(0, session.routeGeneration());
    assertEquals(HandoffPhase.ACTIVE_SOURCE, session.handoffPhase());
  }

  @Test
  void commitsOnlyFromExpectedSourceEpochAndTransfer() {
    LivePlayerSessionStore store = new LivePlayerSessionStore();
    store.putActive(PLAYER, CLIENT, "server-a");
    store.beginTransfer(PLAYER, 0, "server-a", TRANSFER);
    store.markDestinationReady(PLAYER, 0, TRANSFER);
    store.markSourceFrozen(PLAYER, 0, TRANSFER);
    store.markSnapshotStaged(PLAYER, 0, TRANSFER);

    LivePlayerSessionStore.TransitionResult committed = store.commit(PLAYER, 0, TRANSFER,
        "server-a", "server-b");

    assertEquals(APPLIED, committed.status());
    assertEquals("server-b", committed.after().orElseThrow().authoritativeServerId());
    assertEquals(1, committed.after().orElseThrow().playerSessionEpoch());
    assertEquals(1, committed.after().orElseThrow().routeGeneration());
    assertEquals(HandoffPhase.COMMITTED, committed.after().orElseThrow().handoffPhase());

    LivePlayerSessionStore.TransitionResult duplicate = store.commit(PLAYER, 0, TRANSFER,
        "server-a", "server-b");
    assertEquals(ALREADY_APPLIED, duplicate.status());
    assertEquals(1, duplicate.after().orElseThrow().routeGeneration());
  }

  @Test
  void commitRejectsEveryChangedExpectationWithoutMutation() {
    LivePlayerSessionStore store = stagedStore();
    UUID wrongTransfer = UUID.fromString("00000000-0000-0000-0000-000000000004");

    assertEquals(REJECTED_MISMATCH,
        store.commit(PLAYER, 0, TRANSFER, "server-x", "server-b").status());
    assertEquals(REJECTED_MISMATCH,
        store.commit(PLAYER, 0, wrongTransfer, "server-a", "server-b").status());
    assertEquals(REJECTED_STALE_EPOCH,
        store.commit(PLAYER, 1, TRANSFER, "server-a", "server-b").status());

    LivePlayerSession unchanged = store.get(PLAYER).orElseThrow();
    assertEquals("server-a", unchanged.authoritativeServerId());
    assertEquals(0, unchanged.playerSessionEpoch());
    assertEquals(0, unchanged.routeGeneration());
    assertEquals(TRANSFER, unchanged.activeTransferId());
    assertEquals(HandoffPhase.SNAPSHOT_STAGED, unchanged.handoffPhase());
  }

  @Test
  void commitRejectsWrongPhase() {
    LivePlayerSessionStore store = new LivePlayerSessionStore();
    store.putActive(PLAYER, CLIENT, "server-a");
    store.beginTransfer(PLAYER, 0, "server-a", TRANSFER);
    store.markDestinationReady(PLAYER, 0, TRANSFER);

    assertEquals(REJECTED_MISMATCH,
        store.commit(PLAYER, 0, TRANSFER, "server-a", "server-b").status());
    assertEquals(HandoffPhase.DESTINATION_READY,
        store.get(PLAYER).orElseThrow().handoffPhase());
  }

  @Test
  void duplicatePhaseMessagesAreIdempotent() {
    LivePlayerSessionStore store = new LivePlayerSessionStore();
    store.putActive(PLAYER, CLIENT, "server-a");

    assertEquals(APPLIED, store.beginTransfer(PLAYER, 0, "server-a", TRANSFER).status());
    assertEquals(ALREADY_APPLIED, store.beginTransfer(PLAYER, 0, "server-a", TRANSFER).status());
    assertEquals(APPLIED, store.markDestinationReady(PLAYER, 0, TRANSFER).status());
    assertEquals(ALREADY_APPLIED, store.markDestinationReady(PLAYER, 0, TRANSFER).status());
    assertEquals(APPLIED, store.markSourceFrozen(PLAYER, 0, TRANSFER).status());
    assertEquals(ALREADY_APPLIED, store.markSourceFrozen(PLAYER, 0, TRANSFER).status());
    assertEquals(APPLIED, store.markSnapshotStaged(PLAYER, 0, TRANSFER).status());
    assertEquals(ALREADY_APPLIED, store.markDestinationReady(PLAYER, 0, TRANSFER).status());
    assertEquals(ALREADY_APPLIED, store.markSourceFrozen(PLAYER, 0, TRANSFER).status());
    assertEquals(ALREADY_APPLIED, store.markSnapshotStaged(PLAYER, 0, TRANSFER).status());
    assertEquals(APPLIED, store.commit(PLAYER, 0, TRANSFER, "server-a", "server-b").status());
    assertEquals(APPLIED, store.markActiveDestination(PLAYER, 1, TRANSFER).status());
    assertEquals(ALREADY_APPLIED, store.commit(PLAYER, 0, TRANSFER, "server-a", "server-b").status());
    assertEquals(ALREADY_APPLIED, store.markActiveDestination(PLAYER, 1, TRANSFER).status());
    assertEquals(APPLIED, store.markSourceCleaned(PLAYER, 1, TRANSFER).status());
    assertEquals(ALREADY_APPLIED, store.markSourceCleaned(PLAYER, 1, TRANSFER).status());
  }

  @Test
  void abortRestoresSourceBeforeCommit() {
    LivePlayerSessionStore store = new LivePlayerSessionStore();
    store.putActive(PLAYER, CLIENT, "server-a");
    store.beginTransfer(PLAYER, 0, "server-a", TRANSFER);
    store.markDestinationReady(PLAYER, 0, TRANSFER);

    LivePlayerSessionStore.TransitionResult aborted = store.abortTransfer(PLAYER, 0, TRANSFER,
        "server-a");

    assertEquals(APPLIED, aborted.status());
    assertEquals("server-a", aborted.after().orElseThrow().authoritativeServerId());
    assertEquals(0, aborted.after().orElseThrow().playerSessionEpoch());
    assertEquals(HandoffPhase.ACTIVE_SOURCE, aborted.after().orElseThrow().handoffPhase());
    assertEquals(ALREADY_APPLIED, store.abortTransfer(PLAYER, 0, TRANSFER, "server-a").status());
  }

  @Test
  void removesDisconnectedSession() {
    LivePlayerSessionStore store = new LivePlayerSessionStore();
    store.putActive(PLAYER, CLIENT, "server-a");

    assertEquals(PLAYER, store.remove(PLAYER).orElseThrow().playerUuid());
    assertTrue(store.get(PLAYER).isEmpty());
  }

  @Test
  void staleDisconnectDoesNotRemoveNewerSession() {
    UUID newerClient = UUID.fromString("00000000-0000-0000-0000-000000000004");
    LivePlayerSessionStore store = new LivePlayerSessionStore();
    store.putActive(PLAYER, newerClient, "server-a");

    assertTrue(store.remove(PLAYER, CLIENT).isEmpty());
    assertEquals(newerClient, store.get(PLAYER).orElseThrow().clientConnectionId());
    assertEquals(PLAYER, store.remove(PLAYER, newerClient).orElseThrow().playerUuid());
    assertTrue(store.get(PLAYER).isEmpty());
  }

  @Test
  void rejectsAbortAfterCommit() {
    LivePlayerSessionStore store = new LivePlayerSessionStore();
    store.putActive(PLAYER, CLIENT, "server-a");
    store.beginTransfer(PLAYER, 0, "server-a", TRANSFER);
    store.markDestinationReady(PLAYER, 0, TRANSFER);
    store.markSourceFrozen(PLAYER, 0, TRANSFER);
    store.markSnapshotStaged(PLAYER, 0, TRANSFER);
    store.commit(PLAYER, 0, TRANSFER, "server-a", "server-b");

    assertEquals(REJECTED_STALE_EPOCH, store.abortTransfer(PLAYER, 0, TRANSFER, "server-a").status());
  }

  @Test
  void transitionHookCanFailBeforeStateChanges() {
    RuntimeException failure = new RuntimeException("injected");
    LivePlayerSessionStore store = new LivePlayerSessionStore((transitionName, before, after) -> {
      throw failure;
    });
    store.putActive(PLAYER, CLIENT, "server-a");

    assertThrows(RuntimeException.class, () -> store.beginTransfer(PLAYER, 0, "server-a", TRANSFER));
    assertEquals(HandoffPhase.ACTIVE_SOURCE, store.get(PLAYER).orElseThrow().handoffPhase());
    assertEquals(0, store.get(PLAYER).orElseThrow().playerSessionEpoch());
  }

  @Test
  void duplicateMessagesDoNotInvokeTransitionHookAgain() {
    AtomicInteger transitions = new AtomicInteger();
    LivePlayerSessionStore store = new LivePlayerSessionStore((transitionName, before, after) ->
        transitions.incrementAndGet());
    store.putActive(PLAYER, CLIENT, "server-a");

    store.beginTransfer(PLAYER, 0, "server-a", TRANSFER);
    store.beginTransfer(PLAYER, 0, "server-a", TRANSFER);

    assertEquals(1, transitions.get());
  }

  @Test
  void rereadsCommittedSessionForLostAckDuplicate() {
    LivePlayerSessionStore store = stagedStore();
    store.commit(PLAYER, 0, TRANSFER, "server-a", "server-b");

    LivePlayerSessionStore.TransitionResult reread = store.commit(PLAYER, 0, TRANSFER,
        "server-a", "server-b");

    assertEquals(ALREADY_APPLIED, reread.status());
    assertEquals("server-b", reread.after().orElseThrow().authoritativeServerId());
    assertEquals(1, reread.after().orElseThrow().playerSessionEpoch());
    assertEquals(HandoffPhase.COMMITTED, reread.after().orElseThrow().handoffPhase());
  }

  @Test
  void rejectsStaleEpochAfterCommit() {
    LivePlayerSessionStore store = new LivePlayerSessionStore();
    store.putActive(PLAYER, CLIENT, "server-a");
    store.beginTransfer(PLAYER, 0, "server-a", TRANSFER);
    store.markDestinationReady(PLAYER, 0, TRANSFER);
    store.markSourceFrozen(PLAYER, 0, TRANSFER);
    store.markSnapshotStaged(PLAYER, 0, TRANSFER);
    store.commit(PLAYER, 0, TRANSFER, "server-a", "server-b");

    assertEquals(REJECTED_STALE_EPOCH, store.beginTransfer(PLAYER, 0, "server-b",
        UUID.fromString("00000000-0000-0000-0000-000000000005")).status());
  }

  private static LivePlayerSessionStore stagedStore() {
    LivePlayerSessionStore store = new LivePlayerSessionStore();
    store.putActive(PLAYER, CLIENT, "server-a");
    store.beginTransfer(PLAYER, 0, "server-a", TRANSFER);
    store.markDestinationReady(PLAYER, 0, TRANSFER);
    store.markSourceFrozen(PLAYER, 0, TRANSFER);
    store.markSnapshotStaged(PLAYER, 0, TRANSFER);
    return store;
  }
}
