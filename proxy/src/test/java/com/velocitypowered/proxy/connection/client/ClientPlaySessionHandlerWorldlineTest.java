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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.worldline.AsyncHandoffFence;
import com.velocitypowered.proxy.worldline.ControlEnvelope;
import com.velocitypowered.proxy.worldline.HandoffControlPlane;
import com.velocitypowered.proxy.worldline.HandoffPhase;
import com.velocitypowered.proxy.worldline.LivePlayerSession;
import com.velocitypowered.proxy.worldline.LivePlayerSessionStore;
import io.netty.channel.DefaultEventLoop;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

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
