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

package com.velocitypowered.proxy.connection.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.ServerboundMovePlayerPacket;
import com.velocitypowered.proxy.worldline.AsyncHandoffFence;
import com.velocitypowered.proxy.worldline.BoundaryCrossingDetector;
import com.velocitypowered.proxy.worldline.ControlEnvelope;
import com.velocitypowered.proxy.worldline.HandoffControlPlane;
import com.velocitypowered.proxy.worldline.HandoffPhase;
import com.velocitypowered.proxy.worldline.HandoffReplayBuffer;
import com.velocitypowered.proxy.worldline.LivePlayerSession;
import com.velocitypowered.proxy.worldline.LivePlayerSessionStore;
import com.velocitypowered.proxy.worldline.PrepareTarget;
import com.velocitypowered.proxy.worldline.ServerboundMovementRouter;
import com.velocitypowered.proxy.worldline.StaticPartitionMap;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoop;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

public class ClientPlaySessionHandlerWorldlineTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000091");
  private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000092");

  @Test
  void delayedCallbackCannotWriteOrEnqueueAfterItsFenceChanges() {
    LivePlayerSession before = new LivePlayerSession(PLAYER, CLIENT, "server-a", 0, 0, null,
        HandoffPhase.ACTIVE_SOURCE);
    AsyncHandoffFence fence = AsyncHandoffFence.capture(CLIENT, before, "server-a");
    LivePlayerSession after = new LivePlayerSession(PLAYER, CLIENT, "server-b", 1, 1,
        UUID.randomUUID(), HandoffPhase.ACTIVE_DESTINATION);
    AtomicBoolean wrote = new AtomicBoolean();
    AtomicBoolean enqueued = new AtomicBoolean();

    assertFalse(ClientPlaySessionHandler.runFencedWorldlineCallback(fence, CLIENT, after,
        "server-b", false, () -> wrote.set(true)));
    assertFalse(ClientPlaySessionHandler.runFencedWorldlineCallback(fence, CLIENT, before,
        "server-a", false, () -> enqueued.set(true)));
    assertFalse(wrote.get());
    assertFalse(enqueued.get());
  }

  @Test
  void unchangedCallbackFenceRunsExactlyOnce() {
    LivePlayerSession session = new LivePlayerSession(PLAYER, CLIENT, "server-a", 0, 0, null,
        HandoffPhase.ACTIVE_SOURCE);
    AsyncHandoffFence fence = AsyncHandoffFence.capture(CLIENT, session, "server-a");
    AtomicBoolean invoked = new AtomicBoolean();

    assertTrue(ClientPlaySessionHandler.runFencedWorldlineCallback(fence, CLIENT, session,
        "server-a", true, () -> invoked.set(true)));
    assertTrue(invoked.get());
  }

  @Test
  void M5AndLegacySpliceModesUseUnsignedChat() {
    assertTrue(ClientPlaySessionHandler.usesUnsignedWorldlineChat(true, ""));
    assertTrue(ClientPlaySessionHandler.usesUnsignedWorldlineChat(false, "server-b"));
    assertFalse(ClientPlaySessionHandler.usesUnsignedWorldlineChat(false, ""));
  }

  @Test
  void resourcePackHandlingIsStoppedDuringFrozenAndCommittedPhases() {
    assertTrue(ClientPlaySessionHandler.blocksConnectionSensitiveInput(
        HandoffPhase.SOURCE_FROZEN, true, true));
    assertTrue(ClientPlaySessionHandler.blocksConnectionSensitiveInput(
        HandoffPhase.COMMITTED, true, false));
    assertFalse(ClientPlaySessionHandler.blocksConnectionSensitiveInput(
        HandoffPhase.ACTIVE_SOURCE, false, false));
  }

  @Test
  void abortStateIsRetainedUntilTheOrderedAbortConverges() throws Exception {
    VelocityServer server = mock(VelocityServer.class);
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    MinecraftConnection connection = mock(MinecraftConnection.class);
    HandoffControlPlane control = mock(HandoffControlPlane.class);
    DefaultEventLoop eventLoop = new DefaultEventLoop();
    UUID transfer = UUID.randomUUID();
    ControlEnvelope envelope = new ControlEnvelope(HandoffControlPlane.PROTOCOL_VERSION, transfer,
        PLAYER, CLIENT, "server-a", "server-b", "west", "east", 0, 0, 0, 1, 0);
    LivePlayerSession retained = new LivePlayerSession(PLAYER, CLIENT, "server-a", 0, 0,
        transfer, HandoffPhase.SNAPSHOT_STAGED);
    LivePlayerSession active = new LivePlayerSession(PLAYER, CLIENT, "server-a", 0, 0,
        null, HandoffPhase.ACTIVE_SOURCE);
    LivePlayerSessionStore.TransitionResult unavailable = new LivePlayerSessionStore.TransitionResult(
        LivePlayerSessionStore.Status.CONTROL_UNAVAILABLE, Optional.of(retained),
        Optional.of(retained), 0);
    LivePlayerSessionStore.TransitionResult applied = new LivePlayerSessionStore.TransitionResult(
        LivePlayerSessionStore.Status.APPLIED, Optional.of(retained), Optional.of(active), 0);
    CompletableFuture<LivePlayerSessionStore.TransitionResult> prepare = new CompletableFuture<>();

    when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_26_1);
    when(player.getConnection()).thenReturn(connection);
    when(connection.eventLoop()).thenReturn(eventLoop);
    when(server.getWorldlineBoundaryDetector()).thenReturn(Optional.empty());
    when(server.getWorldlineControlPlane()).thenReturn(control);
    when(control.abort(envelope)).thenReturn(unavailable, applied);

    try {
      ClientPlaySessionHandler handler = new ClientPlaySessionHandler(server, player);
      setField(handler, "worldlineTransferId", transfer);
      setField(handler, "worldlineEnvelope", envelope);
      setField(handler, "worldlinePrepareFuture", prepare);

      invokeAbort(handler);
      assertSame(envelope, getField(handler, "worldlineEnvelope"));
      assertSame(transfer, getField(handler, "worldlineTransferId"));

      prepare.complete(unavailable);
      verify(control, timeout(1_000)).abort(envelope);
      drain(eventLoop);
      assertSame(envelope, getField(handler, "worldlineEnvelope"));
      assertSame(transfer, getField(handler, "worldlineTransferId"));

      invokeAbort(handler);
      verify(control, timeout(1_000).times(2)).abort(envelope);
      awaitCleared(handler, eventLoop);
      assertNull(getField(handler, "worldlineEnvelope"));
      assertNull(getField(handler, "worldlineTransferId"));
    } finally {
      eventLoop.shutdownGracefully().syncUninterruptibly();
    }
  }

  @TempDir
  Path tempDir;

  @Test
  void handlerWithholdsSourceReturnAndRotationWhileSnapshotIsPending() throws Exception {
    try (MovementFixture fixture = new MovementFixture(false)) {
      fixture.router.route("server-a", position(-0.25));
      fixture.router.markDestinationReady();
      setField(fixture.handler, "worldlineStageFuture", new CompletableFuture<>());
      var crossing = position(0.25);
      var returning = position(-0.10);
      var rotation = new ServerboundMovePlayerPacket.Rotation();
      assertTrue(fixture.handler.handle(crossing));
      assertTrue(fixture.handler.handle(returning));
      assertTrue(fixture.handler.handle(rotation));
      assertEquals(List.of(crossing, returning, rotation), fixture.router.bufferedPackets());
    }
  }

  @Test
  void activationReplaysInOrderAndResetsPositionForImmediateReverseCrossing() throws Exception {
    try (MovementFixture fixture = new MovementFixture()) {
      fixture.router.route("server-a", position(-0.25));
      fixture.router.markDestinationReady();
      var crossing = position(0.25);
      fixture.router.route("server-a", crossing);
      HandoffReplayBuffer<ServerboundMovePlayerPacket> replay = new HandoffReplayBuffer<>(
          fixture.transfer, 1, 1, 64);
      fixture.router.drainBuffer().forEach(replay::append);
      setField(fixture.handler, "worldlineReplayBuffer", replay);
      when(fixture.control.activateDestination(fixture.envelope)).thenAnswer(ignored ->
          fixture.sessions.markActiveDestination(PLAYER, 1, fixture.transfer));
      when(fixture.control.cleanSource(fixture.envelope)).thenAnswer(ignored ->
          fixture.sessions.markSourceCleaned(PLAYER, 1, fixture.transfer));
      CountDownLatch cleanup = new CountDownLatch(1);
      doAnswer(ignored -> { cleanup.countDown(); return null; })
          .when(fixture.player).disconnectWorldlineSourceAfterCleanup(fixture.source);
      Method activate = ClientPlaySessionHandler.class.getDeclaredMethod(
          "beginWorldlineActivation", ControlEnvelope.class, VelocityServerConnection.class);
      activate.setAccessible(true);
      fixture.eventLoop.submit(() -> {
        try {
          activate.invoke(fixture.handler, fixture.envelope, fixture.destination);
        } catch (ReflectiveOperationException failure) {
          throw new AssertionError(failure);
        }
      }).syncUninterruptibly();
      assertTrue(cleanup.await(5, TimeUnit.SECONDS));
      drain(fixture.eventLoop);
      assertNull(getField(fixture.handler, "worldlineEnvelope"));
      verify(fixture.destination.ensureConnected()).write(crossing);
      verify(fixture.destination.ensureConnected()).flush();
      assertEquals(HandoffPhase.ACTIVE_SOURCE,
          fixture.sessions.get(PLAYER).orElseThrow().handoffPhase());
      assertEquals("server-b", fixture.sessions.get(PLAYER).orElseThrow().authoritativeServerId());
      when(fixture.player.getConnectedServer()).thenReturn(fixture.destination);
      // Hold the next preparation callback: this assertion is about handler routing, not transport.
      setField(fixture.handler, "worldlineTransferId", UUID.randomUUID());
      assertTrue(fixture.handler.handle(position(-0.10)));
      assertEquals("server-a", fixture.router.bufferedDecision().remoteOwner().orElseThrow());
    }
  }

  @Test
  void reverseCrossingDuringCleanupStartsAfterThePreviousTransferRetires() throws Exception {
    checkMovementDuringCleanup(true);
  }

  @Test
  void ordinaryMovementDuringCleanupDoesNotAbortCommittedTransfer() throws Exception {
    checkMovementDuringCleanup(false);
  }

  private void checkMovementDuringCleanup(boolean reverse) throws Exception {
    try (MovementFixture fixture = new MovementFixture()) {
      fixture.router.route("server-a", position(-0.25));
      HandoffReplayBuffer<ServerboundMovePlayerPacket> replay = new HandoffReplayBuffer<>(
          fixture.transfer, 1, 1, 64);
      replay.append(position(0.25));
      setField(fixture.handler, "worldlineReplayBuffer", replay);
      when(fixture.player.getConnectedServer()).thenReturn(fixture.destination);
      when(fixture.control.activateDestination(fixture.envelope)).thenAnswer(ignored ->
          fixture.sessions.markActiveDestination(PLAYER, 1, fixture.transfer));
      CountDownLatch cleanupEntered = new CountDownLatch(1);
      CountDownLatch releaseCleanup = new CountDownLatch(1);
      when(fixture.control.cleanSource(fixture.envelope)).thenAnswer(ignored -> {
        cleanupEntered.countDown();
        if (!releaseCleanup.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("cleanup test latch timed out");
        }
        return fixture.sessions.markSourceCleaned(PLAYER, 1, fixture.transfer);
      });
      when(fixture.control.prepareTarget(any(),
          anyDouble(), anyDouble(),
          anyDouble())).thenReturn(
              new PrepareTarget(
                  "Test", "world", "minecraft:overworld", "test", -0.10, 64, 0, 1));
      when(fixture.control.prepare(any(),
          any())).thenReturn(new LivePlayerSessionStore.TransitionResult(
              LivePlayerSessionStore.Status.CONTROL_UNAVAILABLE, fixture.sessions.get(PLAYER),
              fixture.sessions.get(PLAYER), 0));
      Method activate = ClientPlaySessionHandler.class.getDeclaredMethod(
          "beginWorldlineActivation", ControlEnvelope.class, VelocityServerConnection.class);
      activate.setAccessible(true);
      try {
        fixture.eventLoop.submit(() -> {
          try {
            activate.invoke(fixture.handler, fixture.envelope, fixture.destination);
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        }).syncUninterruptibly();
        assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
        var result = fixture.eventLoop.submit(() -> fixture.handler.handle(
            position(reverse ? -0.10 : 32)));
        result.syncUninterruptibly();
        assertEquals(reverse, result.getNow());
        verify(fixture.control, never()).abort(fixture.envelope);
        verify(fixture.control, never()).prepare(
            any(), any());
      } finally {
        releaseCleanup.countDown();
      }
      if (reverse) {
        var next = ArgumentCaptor.forClass(ControlEnvelope.class);
        verify(fixture.control, timeout(5_000)).prepare(next.capture(),
            any());
        assertEquals("server-b", next.getValue().sourceServerId());
        assertEquals("server-a", next.getValue().destinationServerId());
        assertEquals(1, next.getValue().playerSessionEpoch());
        assertNotEquals(fixture.transfer, next.getValue().transferId());
      } else {
        verify(fixture.player, timeout(5_000)).disconnectWorldlineSourceAfterCleanup(fixture.source);
        drain(fixture.eventLoop);
        assertNull(getField(fixture.handler, "worldlineEnvelope"));
      }
    }
  }

  @Test
  void failedActivationCleanupRetiresRecordAndTearsDownExactlyOnce() throws Exception {
    checkFailedActivationCleanup(true);
  }

  @Test
  void unacknowledgedFailureCleanupKeepsCommittedRecoveryRecord() throws Exception {
    checkFailedActivationCleanup(false);
  }

  private void checkFailedActivationCleanup(boolean acknowledged) throws Exception {
    try (MovementFixture fixture = new MovementFixture()) {
      var current = fixture.sessions.get(PLAYER);
      var ack = new LivePlayerSessionStore.TransitionResult(
          LivePlayerSessionStore.Status.ALREADY_APPLIED, current, current, 0);
      var unavailable = new LivePlayerSessionStore.TransitionResult(
          LivePlayerSessionStore.Status.CONTROL_UNAVAILABLE, current, current, 0);
      when(fixture.control.retireDestination(fixture.envelope)).thenReturn(ack);
      when(fixture.control.cleanSourceAfterFailure(fixture.envelope))
          .thenReturn(acknowledged ? ack : unavailable);
      HandoffReplayBuffer<ServerboundMovePlayerPacket> replay = new HandoffReplayBuffer<>(
          fixture.transfer, 1, 1, 64);
      replay.append(position(0.25));
      setField(fixture.handler, "worldlineReplayBuffer", replay);
      Method fail = ClientPlaySessionHandler.class.getDeclaredMethod(
          "failWorldlinePostCommit", ControlEnvelope.class, String.class);
      fail.setAccessible(true);
      fixture.eventLoop.submit(() -> {
        try {
          fail.invoke(fixture.handler, fixture.envelope, "injected activation rejection");
          fail.invoke(fixture.handler, fixture.envelope, "racing deadline");
        } catch (ReflectiveOperationException failure) {
          throw new AssertionError(failure);
        }
      }).syncUninterruptibly();
      verify(fixture.player, timeout(5_000)).disconnect(any());
      drain(fixture.eventLoop);
      verify(fixture.control).retireDestination(fixture.envelope);
      verify(fixture.control).cleanSourceAfterFailure(fixture.envelope);
      verify(fixture.control, never()).abort(fixture.envelope);
      assertNull(getField(fixture.handler, "worldlineReplayBuffer"));
      assertEquals(0, replay.size());
      assertEquals(acknowledged ? HandoffPhase.RETIRED : HandoffPhase.COMMITTED,
          fixture.sessions.get(PLAYER).orElseThrow().handoffPhase());
      assertEquals("server-b", fixture.sessions.get(PLAYER).orElseThrow().authoritativeServerId());
      verify(fixture.player, times(acknowledged ? 1 : 0)).teardown();
      verify(fixture.player, times(acknowledged ? 1 : 0))
          .disconnectWorldlineSourceAfterCleanup(fixture.source);
    }
  }

  private final class MovementFixture implements AutoCloseable {
    final UUID transfer = UUID.randomUUID();
    final ControlEnvelope envelope = new ControlEnvelope(4, transfer, PLAYER, CLIENT,
        "server-a", "server-b", "west", "east", 1, 1, 0, 1, 0);
    final DefaultEventLoop eventLoop = new DefaultEventLoop();
    final VelocityServer server = mock(VelocityServer.class);
    final ConnectedPlayer player = mock(ConnectedPlayer.class);
    final MinecraftConnection connection = mock(MinecraftConnection.class);
    final HandoffControlPlane control = mock(HandoffControlPlane.class);
    final LivePlayerSessionStore sessions = new LivePlayerSessionStore();
    final VelocityServerConnection source = mock(VelocityServerConnection.class, RETURNS_DEEP_STUBS);
    final VelocityServerConnection destination =
        mock(VelocityServerConnection.class, RETURNS_DEEP_STUBS);
    final ClientPlaySessionHandler handler;
    final ServerboundMovementRouter router;

    MovementFixture() throws Exception {
      this(true);
    }

    MovementFixture(boolean committed) throws Exception {
      Path config = tempDir.resolve("worldline.toml");
      Files.writeString(config, """
          [world]
          level-name = "world"
          dimension = "minecraft:overworld"
          compatibility-id = "test"
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
      var detector = new BoundaryCrossingDetector(StaticPartitionMap.read(config),
          "world", "minecraft:overworld", 1);
      when(server.getWorldlineBoundaryDetector()).thenReturn(Optional.of(detector));
      when(server.getWorldlineControlPlane()).thenReturn(control);
      when(server.getWorldlineLiveSessions()).thenReturn(sessions);
      when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_26_1);
      when(player.getUniqueId()).thenReturn(PLAYER);
      when(player.getConnection()).thenReturn(connection);
      when(connection.eventLoop()).thenReturn(eventLoop);
      when(player.getConnectedServer()).thenReturn(source);
      when(source.getServer().getServerInfo().getName()).thenReturn("server-a");
      when(destination.getServer().getServerInfo().getName()).thenReturn("server-b");
      when(destination.getServerInfo().getName()).thenReturn("server-b");
      sessions.putActive(PLAYER, CLIENT, "server-a");
      sessions.beginTransfer(PLAYER, 0, "server-a", transfer);
      sessions.markDestinationReady(PLAYER, 0, transfer);
      sessions.markSourceFrozen(PLAYER, 0, transfer);
      sessions.markSnapshotStaged(PLAYER, 0, transfer);
      if (committed) {
        sessions.commit(PLAYER, 0, transfer, "server-a", "server-b");
      }
      handler = new ClientPlaySessionHandler(server, player);
      router = (ServerboundMovementRouter) getField(handler, "worldlineMovementRouter");
      setField(handler, "worldlineTransferId", transfer);
      setField(handler, "worldlineEnvelope", envelope);
      setField(handler, "worldlineSourceConnection", source);
    }

    @Override
    public void close() {
      eventLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }

  private static ServerboundMovePlayerPacket.Position position(final double x) {
    var buffer = Unpooled.buffer();
    try {
      buffer.writeDouble(x).writeDouble(64).writeDouble(0).writeByte(1);
      var packet = new ServerboundMovePlayerPacket.Position();
      packet.decode(buffer, ProtocolUtils.Direction.SERVERBOUND, ProtocolVersion.MINECRAFT_26_1);
      return packet;
    } finally {
      buffer.release();
    }
  }

  private static void invokeAbort(final ClientPlaySessionHandler handler) throws Exception {
    Method abort = ClientPlaySessionHandler.class.getDeclaredMethod(
        "abortWorldlineHandoff", boolean.class);
    abort.setAccessible(true);
    abort.invoke(handler, true);
  }

  private static void setField(final ClientPlaySessionHandler handler, final String name,
      final Object value) throws Exception {
    Field field = ClientPlaySessionHandler.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handler, value);
  }

  private static Object getField(final ClientPlaySessionHandler handler, final String name)
      throws Exception {
    Field field = ClientPlaySessionHandler.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(handler);
  }

  private static void drain(final DefaultEventLoop eventLoop) {
    eventLoop.submit(() -> { }).syncUninterruptibly();
  }

  private static void awaitCleared(final ClientPlaySessionHandler handler,
      final DefaultEventLoop eventLoop) throws Exception {
    for (int attempt = 0; attempt < 100; attempt++) {
      drain(eventLoop);
      if (getField(handler, "worldlineEnvelope") == null) {
        return;
      }
      Thread.onSpinWait();
    }
  }
}
