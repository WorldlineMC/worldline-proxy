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
import static com.velocitypowered.proxy.worldline.LivePlayerSessionStore.Status.CONTROL_UNAVAILABLE;
import static com.velocitypowered.proxy.worldline.LivePlayerSessionStore.Status.INJECTED_DROP;
import static com.velocitypowered.proxy.worldline.LivePlayerSessionStore.Status.REJECTED_PARTITION_EPOCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the Worldline M2 control coordinator.
 */
public class HandoffControlPlaneTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000011");
  private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000012");
  private static final UUID TRANSFER = UUID.fromString("00000000-0000-0000-0000-000000000013");
  private static final PrepareTarget TARGET = new PrepareTarget("WorldlineTest", "world",
      "minecraft:overworld", "test-v1", 8, 64, 0, 1);

  @TempDir
  Path tempDir;

  @Test
  void envelopeCarriesClientConnectionAndRouteGeneration() {
    ControlEnvelope envelope = envelope();

    assertEquals(CLIENT, envelope.clientConnectionId());
    assertEquals(0, envelope.routeGeneration());
  }

  @Test
  void scriptedPrepareAbortRoundTrip() {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);

    assertEquals(APPLIED, control.prepare(envelope(), TARGET).status());
    assertEquals(ALREADY_APPLIED, control.prepare(envelope(), TARGET).status());
    assertEquals(HandoffPhase.DESTINATION_READY, sessions.get(PLAYER).orElseThrow().handoffPhase());
    assertEquals(APPLIED, control.abort(envelope()).status());
    assertEquals(HandoffPhase.ACTIVE_SOURCE, sessions.get(PLAYER).orElseThrow().handoffPhase());
    assertEquals(ALREADY_APPLIED, control.abort(envelope()).status());
  }

  @Test
  void scriptedFreezeStageAbortUnfreezesWithoutCommitting() {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    ControlEnvelope envelope = envelope();

    assertEquals(APPLIED, control.prepare(envelope, TARGET).status());
    HandoffControlPlane.SnapshotResult frozen = control.freezeSource(envelope);
    assertEquals(APPLIED, frozen.transition().status());
    assertEquals(APPLIED, control.stageSnapshot(envelope, frozen.snapshot()).status());
    assertEquals(APPLIED, control.abort(envelope).status());

    LivePlayerSession session = sessions.get(PLAYER).orElseThrow();
    assertEquals("server-a", session.authoritativeServerId());
    assertEquals(0, session.playerSessionEpoch());
    assertEquals(HandoffPhase.ACTIVE_SOURCE, session.handoffPhase());
    assertEquals(null, session.activeTransferId());
  }

  @Test
  void scriptedCommitRoundTrip() {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    ControlEnvelope envelope = envelope();

    assertEquals(APPLIED, control.prepare(envelope, TARGET).status());
    HandoffControlPlane.SnapshotResult frozen = control.freezeSource(envelope);
    assertEquals(APPLIED, frozen.transition().status());
    assertEquals(APPLIED, control.stageSnapshot(envelope, frozen.snapshot()).status());
    assertEquals(HandoffControlPlane.CommitBarrierStatus.COMPLETE,
        control.commit(envelope).status());
    assertEquals(APPLIED, control.activateDestination(envelope).status());
    assertEquals(APPLIED, control.cleanSource(envelope).status());

    LivePlayerSession session = sessions.get(PLAYER).orElseThrow();
    assertEquals("server-b", session.authoritativeServerId());
    assertEquals(1, session.playerSessionEpoch());
    assertEquals(HandoffPhase.SOURCE_CLEANED, session.handoffPhase());
  }

  @Test
  void commitUsesCommittedFencesAndBothCommands() {
    LivePlayerSessionStore sessions = stagedSessions();
    RecordingSender sender = new RecordingSender();
    HandoffControlPlane control = new HandoffControlPlane(sessions, sender);

    HandoffControlPlane.CommitBarrierResult result = control.commit(envelope());

    assertEquals(HandoffControlPlane.CommitBarrierStatus.COMPLETE, result.status());
    assertEquals(List.of("server-b:COMMIT_DESTINATION", "server-a:COMMIT_SOURCE"),
        sender.commands);
    assertTrue(sender.envelopes.stream().allMatch(sent -> sent.playerSessionEpoch() == 1));
    assertTrue(sender.envelopes.stream().allMatch(sent -> sent.routeGeneration() == 1));
    assertTrue(sender.envelopes.stream().allMatch(sent -> sent.transferId().equals(TRANSFER)));
    assertEquals(CLIENT, result.committedEnvelope().clientConnectionId());
  }

  @Test
  void startsPostCommitDeadlineAfterLocalCommitBeforeRemoteCommands() {
    LivePlayerSessionStore sessions = stagedSessions();
    AtomicBoolean deadlineStarted = new AtomicBoolean();
    HandoffControlPlane.ControlCommandSender sender =
        (serverId, command, envelope, target, payload) -> {
      assertTrue(deadlineStarted.get());
      assertEquals(HandoffPhase.COMMITTED,
          sessions.get(PLAYER).orElseThrow().handoffPhase());
      return new byte[0];
    };
    HandoffControlPlane control = new HandoffControlPlane(sessions, sender);

    assertEquals(HandoffControlPlane.CommitBarrierStatus.COMPLETE,
        control.commit(envelope(), () -> deadlineStarted.set(true)).status());
  }

  @Test
  void ambiguousAcknowledgementResendsBothSidesAndConverges() {
    LivePlayerSessionStore sessions = stagedSessions();
    RecordingSender sender = new RecordingSender(
        Outcome.ACK, Outcome.IO_FAILURE,
        Outcome.ACK, Outcome.ACK);
    HandoffControlPlane control = new HandoffControlPlane(sessions, sender);

    HandoffControlPlane.CommitBarrierResult result = control.commit(envelope());

    assertEquals(HandoffControlPlane.CommitBarrierStatus.COMPLETE, result.status());
    assertEquals(List.of(
        "server-b:COMMIT_DESTINATION", "server-a:COMMIT_SOURCE",
        "server-b:COMMIT_DESTINATION", "server-a:COMMIT_SOURCE"), sender.commands);
    assertEquals(TRANSFER, sessions.get(PLAYER).orElseThrow().activeTransferId());
    assertEquals(1, sessions.get(PLAYER).orElseThrow().playerSessionEpoch());
  }

  @Test
  void exhaustedAmbiguityIsRetryableAndNeverAbortsCommittedAuthority() {
    LivePlayerSessionStore sessions = stagedSessions();
    RecordingSender sender = new RecordingSender(
        Outcome.IO_FAILURE, Outcome.ACK,
        Outcome.IO_FAILURE, Outcome.ACK,
        Outcome.IO_FAILURE, Outcome.ACK);
    HandoffControlPlane control = new HandoffControlPlane(sessions, sender);

    HandoffControlPlane.CommitBarrierResult result = control.commit(envelope());

    assertEquals(HandoffControlPlane.CommitBarrierStatus.RETRYABLE_INCOMPLETE, result.status());
    assertEquals(HandoffPhase.COMMITTED, sessions.get(PLAYER).orElseThrow().handoffPhase());
    assertEquals("server-b", sessions.get(PLAYER).orElseThrow().authoritativeServerId());
    assertTrue(sender.commands.stream().noneMatch(command -> command.contains("ABORT")));
  }

  @Test
  void definitePostCommitRejectionNeverAborts() {
    LivePlayerSessionStore sessions = stagedSessions();
    RecordingSender sender = new RecordingSender(Outcome.REJECTED, Outcome.ACK);
    HandoffControlPlane control = new HandoffControlPlane(sessions, sender);

    HandoffControlPlane.CommitBarrierResult result = control.commit(envelope());

    assertEquals(HandoffControlPlane.CommitBarrierStatus.REJECTED_AFTER_COMMIT, result.status());
    assertEquals(HandoffPhase.COMMITTED, sessions.get(PLAYER).orElseThrow().handoffPhase());
    assertTrue(sender.commands.stream().noneMatch(command -> command.contains("ABORT")));
  }

  @Test
  void rejectsUnsupportedProtocolVersion() {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    ControlEnvelope envelope = new ControlEnvelope(2, TRANSFER, PLAYER, CLIENT,
        "server-a", "server-b", "west", "east", 0, 0, 0, 0, 0);

    assertThrows(IllegalArgumentException.class, () -> control.prepare(envelope, TARGET));
  }

  @Test
  void rejectsStaleSourcePartitionEpochBeforePrepare() throws Exception {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    control.configure(partitionMap());
    ControlEnvelope stale = new ControlEnvelope(HandoffControlPlane.PROTOCOL_VERSION, TRANSFER,
        PLAYER, CLIENT, "server-a", "server-b", "west", "east", 0, 1, 0, 0, 0);

    assertEquals(REJECTED_PARTITION_EPOCH, control.prepare(stale, TARGET).status());
    assertEquals(HandoffPhase.ACTIVE_SOURCE, sessions.get(PLAYER).orElseThrow().handoffPhase());
  }

  @Test
  void rejectsStaleDestinationPartitionEpochBeforePrepare() throws Exception {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    control.configure(partitionMap());
    ControlEnvelope stale = new ControlEnvelope(HandoffControlPlane.PROTOCOL_VERSION, TRANSFER,
        PLAYER, CLIENT, "server-a", "server-b", "west", "east", 1, 0, 0, 0, 0);

    assertEquals(REJECTED_PARTITION_EPOCH, control.prepare(stale, TARGET).status());
    assertEquals(HandoffPhase.ACTIVE_SOURCE, sessions.get(PLAYER).orElseThrow().handoffPhase());
  }

  @Test
  void controlFailureRestoresSourceAuthority() throws Exception {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    control.configure(partitionMap());

    assertEquals(CONTROL_UNAVAILABLE, control.prepare(envelopeWithEpochs(), TARGET).status());
    LivePlayerSession session = sessions.get(PLAYER).orElseThrow();
    assertEquals(HandoffPhase.ACTIVE_SOURCE, session.handoffPhase());
    assertEquals(null, session.activeTransferId());
    assertEquals(0, session.playerSessionEpoch());
  }

  @Test
  void prepareTargetCarriesTheConfiguredCompatibilityFence() throws Exception {
    HandoffControlPlane control = new HandoffControlPlane(new LivePlayerSessionStore());
    control.configure(partitionMap());

    PrepareTarget target = control.prepareTarget("WorldlineTest", 8, 64, 0);

    assertEquals("world", target.levelName());
    assertEquals("minecraft:overworld", target.dimension());
    assertEquals("test-v1", target.compatibilityId());
    assertEquals(1, target.visibilityRadiusChunks());
  }

  @Test
  void injectsDropDelayDuplicateAndCrash() {
    FailureRun dropped = prepareWithFailure("drop");
    assertEquals(INJECTED_DROP, dropped.result().status());
    assertEquals(HandoffPhase.ACTIVE_SOURCE, dropped.session().handoffPhase());
    assertEquals(null, dropped.session().activeTransferId());

    FailureRun duplicated = prepareWithFailure("duplicate");
    assertEquals(APPLIED, duplicated.result().status());
    assertEquals(HandoffPhase.DESTINATION_READY, duplicated.session().handoffPhase());

    long started = System.nanoTime();
    assertEquals(APPLIED, prepareWithFailure("delay:20").result().status());
    assertTrue(System.nanoTime() - started >= 15_000_000L);

    String property = "worldline.failure.preparing_destination";
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    System.setProperty(property, "crash");
    try {
      assertThrows(IllegalStateException.class,
          () -> new HandoffControlPlane(sessions).prepare(envelope(), TARGET));
    } finally {
      System.clearProperty(property);
    }
    assertEquals(HandoffPhase.ACTIVE_SOURCE, sessions.get(PLAYER).orElseThrow().handoffPhase());
  }

  private FailureRun prepareWithFailure(final String mode) {
    String property = "worldline.failure.preparing_destination";
    System.setProperty(property, mode);
    try {
      LivePlayerSessionStore sessions = new LivePlayerSessionStore();
      sessions.putActive(PLAYER, CLIENT, "server-a");
      LivePlayerSessionStore.TransitionResult result =
          new HandoffControlPlane(sessions).prepare(envelope(), TARGET);
      return new FailureRun(result, sessions.get(PLAYER).orElseThrow());
    } finally {
      System.clearProperty(property);
    }
  }

  private StaticPartitionMap partitionMap() throws Exception {
    Path config = tempDir.resolve("worldline.toml");
    Files.writeString(config, """
        [world]
        level-name = "world"
        dimension = "minecraft:overworld"
        compatibility-id = "test-v1"

        [servers.server-a]
        control-address = "127.0.0.1:1"

        [servers.server-b]
        control-address = "127.0.0.1:1"

        [[partitions]]
        id = "west"
        owner = "server-a"
        epoch = 1
        chunk-x-max = -1

        [[partitions]]
        id = "east"
        owner = "server-b"
        epoch = 1
        chunk-x-min = 0
        """);
    return StaticPartitionMap.read(config);
  }

  private static ControlEnvelope envelope() {
    return new ControlEnvelope(HandoffControlPlane.PROTOCOL_VERSION, TRANSFER, PLAYER,
        CLIENT, "server-a", "server-b", "west", "east", 0, 0, 0, 0, 0);
  }

  private static ControlEnvelope envelopeWithEpochs() {
    return new ControlEnvelope(HandoffControlPlane.PROTOCOL_VERSION, TRANSFER, PLAYER,
        CLIENT, "server-a", "server-b", "west", "east", 1, 1, 0, 0, 0);
  }

  private static LivePlayerSessionStore stagedSessions() {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    sessions.beginTransfer(PLAYER, 0, "server-a", TRANSFER);
    sessions.markDestinationReady(PLAYER, 0, TRANSFER);
    sessions.markSourceFrozen(PLAYER, 0, TRANSFER);
    sessions.markSnapshotStaged(PLAYER, 0, TRANSFER);
    return sessions;
  }

  private enum Outcome {
    ACK,
    IO_FAILURE,
    REJECTED
  }

  private static final class RecordingSender implements HandoffControlPlane.ControlCommandSender {
    private final Queue<Outcome> outcomes = new ArrayDeque<>();
    private final List<String> commands = new ArrayList<>();
    private final List<ControlEnvelope> envelopes = new ArrayList<>();

    private RecordingSender(final Outcome... outcomes) {
      this.outcomes.addAll(List.of(outcomes));
    }

    @Override
    public byte[] send(final String serverId, final String command,
        final ControlEnvelope sent, final PrepareTarget target, final byte[] payload)
        throws IOException {
      commands.add(serverId + ":" + command);
      envelopes.add(sent);
      Outcome outcome = outcomes.isEmpty() ? Outcome.ACK : outcomes.remove();
      if (outcome == Outcome.IO_FAILURE) {
        throw new IOException("lost acknowledgement");
      }
      if (outcome == Outcome.REJECTED) {
        throw new WorldlineControlTransport.ControlRejectedException("rejected");
      }
      return new byte[0];
    }
  }

  private record FailureRun(LivePlayerSessionStore.TransitionResult result,
                            LivePlayerSession session) {
  }
}
