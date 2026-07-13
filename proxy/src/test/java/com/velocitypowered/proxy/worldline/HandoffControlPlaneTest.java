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
import static com.velocitypowered.proxy.worldline.LivePlayerSessionStore.Status.INJECTED_DROP;
import static com.velocitypowered.proxy.worldline.LivePlayerSessionStore.Status.REJECTED_PARTITION_EPOCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the Worldline M2 control coordinator.
 */
public class HandoffControlPlaneTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000011");
  private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000012");
  private static final UUID TRANSFER = UUID.fromString("00000000-0000-0000-0000-000000000013");

  @TempDir
  Path tempDir;

  @Test
  void scriptedPrepareAbortRoundTrip() {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);

    assertEquals(APPLIED, control.prepare(envelope()).status());
    assertEquals(ALREADY_APPLIED, control.prepare(envelope()).status());
    assertEquals(HandoffPhase.DESTINATION_READY, sessions.get(PLAYER).orElseThrow().handoffPhase());
    assertEquals(APPLIED, control.abort(envelope()).status());
    assertEquals(HandoffPhase.ACTIVE_SOURCE, sessions.get(PLAYER).orElseThrow().handoffPhase());
    assertEquals(ALREADY_APPLIED, control.abort(envelope()).status());
  }

  @Test
  void scriptedCommitRoundTrip() {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    ControlEnvelope envelope = envelope();

    assertEquals(APPLIED, control.prepare(envelope).status());
    assertEquals(APPLIED, control.freezeSource(envelope).status());
    assertEquals(APPLIED, control.stageSnapshot(envelope).status());
    assertEquals(APPLIED, control.commit(envelope).status());
    assertEquals(APPLIED, control.activateDestination(envelope).status());
    assertEquals(APPLIED, control.cleanSource(envelope).status());

    LivePlayerSession session = sessions.get(PLAYER).orElseThrow();
    assertEquals("server-b", session.authoritativeServerId());
    assertEquals(1, session.playerSessionEpoch());
    assertEquals(HandoffPhase.SOURCE_CLEANED, session.handoffPhase());
  }

  @Test
  void rejectsUnsupportedProtocolVersion() {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    ControlEnvelope envelope = new ControlEnvelope(2, TRANSFER, PLAYER, "server-a", "server-b",
        "west", "east", 0, 0, 0, 0);

    assertThrows(IllegalArgumentException.class, () -> control.prepare(envelope));
  }

  @Test
  void rejectsStaleSourcePartitionEpochBeforePrepare() throws Exception {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    control.configure(partitionMap());
    ControlEnvelope stale = new ControlEnvelope(HandoffControlPlane.PROTOCOL_VERSION, TRANSFER,
        PLAYER, "server-a", "server-b", "west", "east", 0, 1, 0, 0);

    assertEquals(REJECTED_PARTITION_EPOCH, control.prepare(stale).status());
    assertEquals(HandoffPhase.ACTIVE_SOURCE, sessions.get(PLAYER).orElseThrow().handoffPhase());
  }

  @Test
  void rejectsStaleDestinationPartitionEpochBeforePrepare() throws Exception {
    LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    sessions.putActive(PLAYER, CLIENT, "server-a");
    HandoffControlPlane control = new HandoffControlPlane(sessions);
    control.configure(partitionMap());
    ControlEnvelope stale = new ControlEnvelope(HandoffControlPlane.PROTOCOL_VERSION, TRANSFER,
        PLAYER, "server-a", "server-b", "west", "east", 1, 0, 0, 0);

    assertEquals(REJECTED_PARTITION_EPOCH, control.prepare(stale).status());
    assertEquals(HandoffPhase.ACTIVE_SOURCE, sessions.get(PLAYER).orElseThrow().handoffPhase());
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
          () -> new HandoffControlPlane(sessions).prepare(envelope()));
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
          new HandoffControlPlane(sessions).prepare(envelope());
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

        [servers.server-a]
        control-address = "127.0.0.1:25576"

        [servers.server-b]
        control-address = "127.0.0.1:25577"

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
        "server-a", "server-b", "west", "east", 0, 0, 0, 0);
  }

  private record FailureRun(LivePlayerSessionStore.TransitionResult result,
                            LivePlayerSession session) {
  }
}
