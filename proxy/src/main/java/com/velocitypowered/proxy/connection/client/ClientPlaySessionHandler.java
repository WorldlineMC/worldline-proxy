/*
 * Copyright (C) 2018-2023 Velocity Contributors
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

import static com.velocitypowered.proxy.protocol.util.PluginMessageUtil.constructChannelsPacket;

import com.google.common.collect.ImmutableList;
import com.mojang.brigadier.suggestion.Suggestion;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.CookieReceiveEvent;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.player.PlayerChannelUnregisterEvent;
import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.event.player.configuration.PlayerEnteredConfigurationEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.ConnectionTypes;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.backend.BackendConnectionPhases;
import com.velocitypowered.proxy.connection.backend.BungeeCordMessageResponder;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.forge.legacy.LegacyForgeConstants;
import com.velocitypowered.proxy.connection.player.resourcepack.ResourcePackResponseBundle;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.packet.BossBarPacket;
import com.velocitypowered.proxy.protocol.packet.ClientSettingsPacket;
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackResponsePacket;
import com.velocitypowered.proxy.protocol.packet.RespawnPacket;
import com.velocitypowered.proxy.protocol.packet.ServerboundClientTickEndPacket;
import com.velocitypowered.proxy.protocol.packet.ServerboundCookieResponsePacket;
import com.velocitypowered.proxy.protocol.packet.ServerboundMovePlayerPacket;
import com.velocitypowered.proxy.protocol.packet.TabCompleteRequestPacket;
import com.velocitypowered.proxy.protocol.packet.TabCompleteResponsePacket;
import com.velocitypowered.proxy.protocol.packet.TabCompleteResponsePacket.Offer;
import com.velocitypowered.proxy.protocol.packet.chat.ChatAcknowledgementPacket;
import com.velocitypowered.proxy.protocol.packet.chat.ChatHandler;
import com.velocitypowered.proxy.protocol.packet.chat.ChatTimeKeeper;
import com.velocitypowered.proxy.protocol.packet.chat.CommandHandler;
import com.velocitypowered.proxy.protocol.packet.chat.ComponentHolder;
import com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedChatHandler;
import com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedCommandHandler;
import com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedPlayerChatPacket;
import com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedPlayerCommandPacket;
import com.velocitypowered.proxy.protocol.packet.chat.legacy.LegacyChatHandler;
import com.velocitypowered.proxy.protocol.packet.chat.legacy.LegacyChatPacket;
import com.velocitypowered.proxy.protocol.packet.chat.legacy.LegacyCommandHandler;
import com.velocitypowered.proxy.protocol.packet.chat.session.ChatSessionUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionChatHandler;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionCommandHandler;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerChatPacket;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerCommandPacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.title.GenericTitlePacket;
import com.velocitypowered.proxy.protocol.util.PluginMessageUtil;
import com.velocitypowered.proxy.util.CharacterUtil;
import com.velocitypowered.proxy.util.except.QuietRuntimeException;
import com.velocitypowered.proxy.worldline.BoundaryCrossingDetector;
import com.velocitypowered.proxy.worldline.BackendSessionBinding;
import com.velocitypowered.proxy.worldline.ControlEnvelope;
import com.velocitypowered.proxy.worldline.HandoffControlPlane;
import com.velocitypowered.proxy.worldline.HandoffPhase;
import com.velocitypowered.proxy.worldline.HandoffReplayBuffer;
import com.velocitypowered.proxy.worldline.HandoffReplayGate;
import com.velocitypowered.proxy.worldline.LivePlayerSession;
import com.velocitypowered.proxy.worldline.LivePlayerSessionStore;
import com.velocitypowered.proxy.worldline.PrepareTarget;
import com.velocitypowered.proxy.worldline.ServerboundHandoffTraffic;
import com.velocitypowered.proxy.worldline.ServerboundMovementRouter;
import com.velocitypowered.proxy.worldline.WorldlineResumeContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Handles communication with the connected Minecraft client. This is effectively the primary nerve
 * center that joins backend servers with players.
 */
public class ClientPlaySessionHandler implements MinecraftSessionHandler {
  private static final boolean BACKPRESSURE_LOG =
      Boolean.getBoolean("velocity.log-server-backpressure");

  // Caps the per-connection queue used while the FML/login phases are not yet "complete". Without
  // these caps, a client that never completes its handshake phase can spam plugin messages (each up
  // to ~32 KiB serverbound) and grow the queue without bound.
  private static final long MAX_QUEUED_LOGIN_PLUGIN_MESSAGE_BYTES =
      Long.getLong("velocity.max-queued-login-plugin-message-bytes", 4L * 1024 * 1024);
  private static final int MAX_QUEUED_LOGIN_PLUGIN_MESSAGES =
      Integer.getInteger("velocity.max-queued-login-plugin-messages", 1024);

  private static final Logger logger = LogManager.getLogger(ClientPlaySessionHandler.class);
  private static final int WORLDLINE_POST_COMMIT_TIMEOUT_SECONDS =
      boundedIntegerProperty("worldline.m5.post-commit-timeout-seconds", 10, 1, 60);

  private final ConnectedPlayer player;
  private boolean spawned = false;
  private final List<UUID> serverBossBars = new ArrayList<>();
  private final Queue<PluginMessagePacket> loginPluginMessages = new ConcurrentLinkedQueue<>();
  private final AtomicLong loginPluginMessagesBytes = new AtomicLong();
  private final AtomicInteger loginPluginMessagesCount = new AtomicInteger();
  private volatile boolean loginPluginMessagesOverflowed;
  private final VelocityServer server;
  private @Nullable TabCompleteRequestPacket outstandingTabComplete;
  private final ChatHandler<? extends MinecraftPacket> chatHandler;
  private final CommandHandler<? extends MinecraftPacket> commandHandler;
  private final ChatTimeKeeper timeKeeper = new ChatTimeKeeper();
  private final @Nullable ServerboundMovementRouter worldlineMovementRouter;
  private final UUID worldlineClientConnectionId = UUID.randomUUID();
  private @Nullable UUID worldlineTransferId;
  private @Nullable ControlEnvelope worldlineEnvelope;
  private @Nullable CompletableFuture<LivePlayerSessionStore.TransitionResult>
      worldlinePrepareFuture;
  private @Nullable CompletableFuture<LivePlayerSessionStore.TransitionResult>
      worldlineStageFuture;
  private @Nullable CompletableFuture<?> worldlinePostCommitFuture;
  private @Nullable HandoffReplayBuffer<ServerboundMovePlayerPacket> worldlineReplayBuffer;
  private final AtomicInteger worldlineClientTransitionPackets = new AtomicInteger();
  private @Nullable ScheduledFuture<?> worldlinePostCommitDeadline;
  private @Nullable VelocityServerConnection worldlineSourceConnection;
  private @Nullable VelocityServerConnection worldlineDestinationConnection;
  private boolean worldlinePostCommitTerminated;
  private boolean worldlineFreezeInProgress;

  private CompletableFuture<Void> configSwitchFuture;

  private int failedTabCompleteAttempts;

  /**
   * Constructs a client play session handler.
   *
   * @param server the Velocity server instance
   * @param player the player
   */
  public ClientPlaySessionHandler(VelocityServer server, ConnectedPlayer player) {
    this.player = player;
    this.server = server;

    if (this.player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_19_3)) {
      this.chatHandler = new SessionChatHandler(this.player, this.server);
      this.commandHandler = new SessionCommandHandler(this.player, this.server);
    } else if (this.player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_19)) {
      this.chatHandler = new KeyedChatHandler(this.server, this.player);
      this.commandHandler = new KeyedCommandHandler(this.player, this.server);
    } else {
      this.chatHandler = new LegacyChatHandler(this.server, this.player);
      this.commandHandler = new LegacyCommandHandler(this.player, this.server);
    }
    this.worldlineMovementRouter = ServerboundMovementRouter.create(
        this.server.getWorldlineBoundaryDetector()).orElse(null);
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private boolean updateTimeKeeper(@Nullable Instant instant) {
    if (instant == null) {
      return true;
    }
    if (!this.timeKeeper.update(instant)) {
      player.disconnect(Component.translatable("multiplayer.disconnect.out_of_order_chat"));
      return false;
    }
    return true;
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private boolean validateChat(String message) {
    if (CharacterUtil.containsIllegalCharacters(message)) {
      player.disconnect(
          Component.translatable("velocity.error.illegal-chat-characters", NamedTextColor.RED));
      return false;
    }
    return true;
  }

  @Override
  public void activated() {
    configSwitchFuture = new CompletableFuture<>();
    Collection<ChannelIdentifier> channels =
        server.getChannelRegistrar().getChannelsForProtocol(player.getProtocolVersion());
    if (!channels.isEmpty()) {
      PluginMessagePacket register = constructChannelsPacket(player.getProtocolVersion(), channels);
      player.getConnection().write(register);
    }
  }

  @Override
  public void deactivated() {
    player.discardChatQueue();
    PluginMessagePacket message;
    while ((message = loginPluginMessages.poll()) != null) {
      ReferenceCountUtil.release(message);
    }
    loginPluginMessagesBytes.set(0);
    loginPluginMessagesCount.set(0);
  }

  /**
   * Adds a retained plugin message to the queue used while the FML/login phases are still in
   * progress, enforcing the per-connection byte and count caps. Returns {@code true} if queued,
   * {@code false} if the packet was released (and the player disconnected on overflow).
   */
  private boolean enqueueLoginPluginMessage(PluginMessagePacket packet) {
    if (loginPluginMessagesOverflowed) {
      ReferenceCountUtil.release(packet);
      return false;
    }
    int packetSize = packet.content().readableBytes();
    long newBytes = loginPluginMessagesBytes.addAndGet(packetSize);
    int newCount = loginPluginMessagesCount.incrementAndGet();
    if (newBytes > MAX_QUEUED_LOGIN_PLUGIN_MESSAGE_BYTES
        || newCount > MAX_QUEUED_LOGIN_PLUGIN_MESSAGES) {
      loginPluginMessagesOverflowed = true;
      ReferenceCountUtil.release(packet);
      logger.warn("Disconnecting {}: pre-join plugin-message queue exceeded its limits "
              + "({} messages, {} bytes).", player, newCount, newBytes);
      player.disconnect(Component.translatable("velocity.error.plugin-message-overflow"));
      return false;
    }
    loginPluginMessages.add(packet);
    return true;
  }

  @Override
  public boolean handle(KeepAlivePacket packet) {
    player.forwardKeepAlive(packet);
    return true;
  }

  @Override
  public boolean handle(ClientSettingsPacket packet) {
    player.setClientSettings(packet);
    if (abortFrozenGameplayInput(packet)) {
      return true;
    }
    VelocityServerConnection serverConnection = player.getConnectedServer();
    if (serverConnection == null) {
      // No server connection yet, probably transitioning.
      return true;
    }
    player.getConnectedServer().ensureConnected().write(packet);
    return true; // will forward onto the server
  }

  @Override
  public boolean handle(SessionPlayerCommandPacket packet) {
    if (abortFrozenGameplayInput(packet)) {
      return true;
    }
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    if (!updateTimeKeeper(packet.getTimeStamp())) {
      return true;
    }

    if (!validateChat(packet.getCommand())) {
      return true;
    }

    return this.commandHandler.handlePlayerCommand(packet);
  }

  @Override
  public boolean handle(SessionPlayerChatPacket packet) {
    if (abortFrozenGameplayInput(packet)) {
      return true;
    }
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    if (!updateTimeKeeper(packet.getTimestamp())) {
      return true;
    }

    if (!validateChat(packet.getMessage())) {
      return true;
    }

    return this.chatHandler.handlePlayerChat(packet);
  }

  @Override
  public boolean handle(KeyedPlayerCommandPacket packet) {
    if (abortFrozenGameplayInput(packet)) {
      return true;
    }
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    if (!updateTimeKeeper(packet.getTimestamp())) {
      return true;
    }

    if (!validateChat(packet.getCommand())) {
      return true;
    }

    return this.commandHandler.handlePlayerCommand(packet);
  }

  @Override
  public boolean handle(KeyedPlayerChatPacket packet) {
    if (abortFrozenGameplayInput(packet)) {
      return true;
    }
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    if (!updateTimeKeeper(packet.getExpiry())) {
      return true;
    }

    if (!validateChat(packet.getMessage())) {
      return true;
    }

    return this.chatHandler.handlePlayerChat(packet);
  }

  @Override
  public boolean handle(LegacyChatPacket packet) {
    if (abortFrozenGameplayInput(packet)) {
      return true;
    }
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    String msg = packet.getMessage();
    if (!validateChat(msg)) {
      return true;
    }

    if (msg.startsWith("/")) {
      this.commandHandler.handlePlayerCommand(packet);
    } else {
      this.chatHandler.handlePlayerChat(packet);
    }
    return true;
  }

  @Override
  public boolean handle(TabCompleteRequestPacket packet) {
    boolean isCommand = !packet.isAssumeCommand() && packet.getCommand().startsWith("/");

    if (isCommand) {
      return this.handleCommandTabComplete(packet);
    } else {
      return this.handleRegularTabComplete(packet);
    }
  }

  @Override
  public boolean handle(PluginMessagePacket packet) {
    if (abortFrozenGameplayInput(packet)) {
      return true;
    }
    // Handling edge case when packet with FML client handshake (state COMPLETE)
    // arrives after JoinGame packet from destination server
    VelocityServerConnection serverConn =
        (player.getConnectedServer() == null
            && packet.getChannel().equals(
            LegacyForgeConstants.FORGE_LEGACY_HANDSHAKE_CHANNEL))
            ? player.getConnectionInFlight() : player.getConnectedServer();

    MinecraftConnection backendConn = serverConn != null ? serverConn.getConnection() : null;
    if (serverConn != null && backendConn != null) {
      if (backendConn.getState() != StateRegistry.PLAY) {
        logger.warn("A plugin message was received while the backend server was not "
            + "ready. Channel: {}. Packet discarded.", packet.getChannel());
      } else if (PluginMessageUtil.isRegister(packet)) {
        List<ChannelIdentifier> channels =
            PluginMessageUtil.getChannels(this.player.getClientsideChannels().size(), packet,
                this.player.getProtocolVersion());
        player.getClientsideChannels().addAll(channels);
        server.getEventManager()
            .fireAndForget(
                new PlayerChannelRegisterEvent(player, ImmutableList.copyOf(channels)));
        backendConn.write(packet.retain());
      } else if (PluginMessageUtil.isUnregister(packet)) {
        List<ChannelIdentifier> channels =
            PluginMessageUtil.getChannels(0, packet, this.player.getProtocolVersion());
        player.getClientsideChannels().removeAll(channels);
        server.getEventManager()
            .fireAndForget(
                new PlayerChannelUnregisterEvent(player, ImmutableList.copyOf(channels)));
        backendConn.write(packet.retain());
      } else if (PluginMessageUtil.isMcBrand(packet)) {
        String brand = PluginMessageUtil.readBrandMessage(packet.content());
        server.getEventManager().fireAndForget(new PlayerClientBrandEvent(player, brand));
        player.setClientBrand(brand);
        backendConn.write(packet.retain());
      } else if (BungeeCordMessageResponder.isBungeeCordMessage(packet)) {
        return true;
      } else {
        if (serverConn.getPhase() == BackendConnectionPhases.IN_TRANSITION) {
          // We must bypass the currently-connected server when forwarding Forge packets.
          VelocityServerConnection inFlight = player.getConnectionInFlight();
          if (inFlight != null) {
            player.getPhase().handle(player, packet, inFlight);
          }
          return true;
        }

        if (!player.getPhase().handle(player, packet, serverConn)) {
          ChannelIdentifier id = server.getChannelRegistrar().getFromId(packet.getChannel());
          if (id == null) {
            // We don't have any plugins listening on this channel, process the packet now.
            if (!player.getPhase().consideredComplete() || !serverConn.getPhase()
                .consideredComplete()) {
              // The client is trying to send messages too early. This is primarily caused by mods,
              // but further aggravated by Velocity. To work around these issues, we will queue any
              // non-FML handshake messages to be sent once the FML handshake has completed or the
              // JoinGame packet has been received by the proxy, whichever comes first.
              //
              // We also need to make sure to retain these packets, so they can be flushed
              // appropriately.
              enqueueLoginPluginMessage(packet.retain());
            } else {
              // The connection is ready, send the packet now.
              backendConn.write(packet.retain());
            }
          } else {
            byte[] copy = ByteBufUtil.getBytes(packet.content());
            PluginMessageEvent event = new PluginMessageEvent(player, serverConn, id, copy);
            server.getEventManager().fire(event).thenAcceptAsync(pme -> {
              if (pme.getResult().isAllowed()) {
                PluginMessagePacket message = new PluginMessagePacket(packet.getChannel(),
                    Unpooled.wrappedBuffer(copy));
                if (!player.getPhase().consideredComplete() || !serverConn.getPhase()
                    .consideredComplete()) {
                  // We're still processing the connection (see above), enqueue the packet for now.
                  enqueueLoginPluginMessage(message.retain());
                } else {
                  backendConn.write(message);
                }
              }
            }, backendConn.eventLoop()).exceptionally((ex) -> {
              logger.error("Exception while handling plugin message packet for {}", player, ex);
              return null;
            });
          }
        }
      }
    }

    return true;
  }

  @Override
  public boolean handle(ResourcePackResponsePacket packet) {
    return player.resourcePackHandler().onResourcePackResponse(
        new ResourcePackResponseBundle(packet.getId(),
            packet.getHash(),
            packet.getStatus()));
  }

  @Override
  public boolean handle(FinishedUpdatePacket packet) {
    if (!player.getConnection().pendingConfigurationSwitch) {
      throw new QuietRuntimeException("Not expecting reconfiguration");
    }
    // Complete client switch
    player.getConnection().setActiveSessionHandler(StateRegistry.CONFIG);
    VelocityServerConnection serverConnection = player.getConnectedServer();
    server.getEventManager()
        .fireAndForget(new PlayerEnteredConfigurationEvent(player, serverConnection));
    if (serverConnection != null) {
      MinecraftConnection smc = serverConnection.ensureConnected();
      CompletableFuture.runAsync(() -> {
        smc.write(packet);
        smc.setActiveSessionHandler(StateRegistry.CONFIG);
        smc.setAutoReading(true);
      }, smc.eventLoop()).exceptionally((ex) -> {
        logger.error("Error forwarding config state acknowledgement to server:", ex);
        return null;
      });
    }
    configSwitchFuture.complete(null);
    return true;
  }

  @Override
  public boolean handle(ChatAcknowledgementPacket packet) {
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }
    player.getChatQueue().handleAcknowledgement(packet.offset());
    return true;
  }

  @Override
  public boolean handle(ServerboundCookieResponsePacket packet) {
    server.getEventManager()
        .fire(new CookieReceiveEvent(player, packet.getKey(), packet.getPayload()))
        .thenAcceptAsync(event -> {
          if (event.getResult().isAllowed()) {
            final VelocityServerConnection serverConnection = player.getConnectedServer();
            if (serverConnection != null) {
              final Key resultedKey = event.getResult().getKey() == null
                  ? event.getOriginalKey() : event.getResult().getKey();
              final byte[] resultedData = event.getResult().getData() == null
                  ? event.getOriginalData() : event.getResult().getData();

              serverConnection.ensureConnected()
                  .write(new ServerboundCookieResponsePacket(resultedKey, resultedData));
            }
          }
        }, player.getConnection().eventLoop());

    return true;
  }

  @Override
  public boolean handle(ChatSessionUpdatePacket packet) {
    if (!System.getProperty("worldline.splice-target", "").isEmpty()) {
      // With the splice armed, no backend may ever install the client's chat session: a backend
      // holding the session broadcasts the player's chat signed, which advances the client's
      // last-seen acknowledgement window, and the next backend after a splice would then kick the
      // player for acknowledging messages it never sent. Dropping the session here keeps chat
      // unsigned cluster-wide so acknowledgement state stays empty and consistent everywhere.
      logger.info("Worldline dropped chat session update from {}; the slice runs unsigned chat",
          player.getUsername());
      return true;
    }
    return false;
  }

  @Override
  public boolean handle(ServerboundMovePlayerPacket packet) {
    if (worldlineMovementRouter == null) {
      return false;
    }
    if (HandoffReplayGate.mustBuffer(worldlineCurrentPhase(), worldlineReplayBuffer != null)) {
      HandoffReplayBuffer.AppendResult appended = worldlineReplayBuffer.append(packet);
      if (appended != HandoffReplayBuffer.AppendResult.APPENDED && worldlineEnvelope != null) {
        failWorldlinePostCommit(worldlineEnvelope, "movement replay buffer " + appended);
      }
      return true;
    }
    VelocityServerConnection serverConnection = player.getConnectedServer();
    if (serverConnection == null) {
      return false;
    }
    String serverId = serverConnection.getServer().getServerInfo().getName();
    BoundaryCrossingDetector.Decision decision = worldlineMovementRouter.route(serverId, packet);
    if (decision.action() == BoundaryCrossingDetector.Action.PREPARE
        || decision.action() == BoundaryCrossingDetector.Action.PREPARE_AND_WITHHOLD) {
      beginWorldlinePreparation(serverId, decision, packet);
      if (decision.action() == BoundaryCrossingDetector.Action.PREPARE_AND_WITHHOLD) {
        logWorldlineCrossingWithheld(serverId, decision);
        return true;
      }
      return false;
    }
    if (decision.action() == BoundaryCrossingDetector.Action.WITHHOLD_CROSSING) {
      if (worldlineMovementRouter.bufferedPackets().size() == 1) {
        logWorldlineCrossingWithheld(serverId, decision);
      }
      if (worldlinePrepareFuture == null && worldlinePostCommitFuture == null
          && worldlineEnvelope != null) {
        beginWorldlineSnapshotStage(worldlineEnvelope);
      }
      return true;
    }
    if (decision.action() == BoundaryCrossingDetector.Action.BUFFER_LIMIT_EXCEEDED
        || decision.action() == BoundaryCrossingDetector.Action.PREPARE_TIMEOUT) {
      abortWorldlineHandoff(true);
      logger.warn("Worldline {} for {} crossing from {} to {}; dropping input",
          switch (decision.action()) {
            case PREPARE_TIMEOUT -> "prepare timed out";
            default -> "movement buffer full";
          },
          player, serverId, decision.remoteOwner().orElse("unknown"));
      return true;
    }
    if (decision.action() == BoundaryCrossingDetector.Action.FORWARD
        && worldlineTransferId != null && !worldlineMovementRouter.hasBufferedInput()) {
      abortWorldlineHandoff();
      logger.info("Worldline discarded preparation for {} after leaving the boundary", player);
    }
    return false;
  }

  @Override
  public boolean handle(ServerboundClientTickEndPacket packet) {
    if (HandoffReplayGate.mustBuffer(worldlineCurrentPhase(), worldlineReplayBuffer != null)) {
      // Tick pacing carries no replayable state; elide it during the post-commit fence so the
      // client's per-tick cadence cannot race the replay drain. While the source is merely
      // frozen it still owns the connection, so tick pacing forwards to it like any other
      // connection bookkeeping.
      return true;
    }
    VelocityServerConnection serverConnection = player.getConnectedServer();
    if (serverConnection == null) {
      return true;
    }
    MinecraftConnection smc = serverConnection.getConnection();
    if (smc != null && !smc.isClosed() && serverConnection.getPhase().consideredComplete()
        && smc.getState() == StateRegistry.PLAY) {
      smc.write(packet);
    }
    return true;
  }

  @Override
  public boolean handle(JoinGamePacket packet) {
    // Forward the packet as normal, but discard any chat state we have queued - the client will do this too
    player.discardChatQueue();
    return false;
  }

  private void beginWorldlinePreparation(final String serverId,
      final BoundaryCrossingDetector.Decision decision,
      final ServerboundMovePlayerPacket packet) {
    if (worldlineTransferId != null) {
      return;
    }
    LivePlayerSessionStore sessions = server.getWorldlineLiveSessions();
    if (sessions.get(player.getUniqueId()).isEmpty()) {
      sessions.putActive(player.getUniqueId(), worldlineClientConnectionId, serverId);
    }
    LivePlayerSession liveSession = sessions.get(player.getUniqueId()).orElseThrow();
    VelocityServerConnection sourceConnection = player.getConnectedServer();
    if (sourceConnection == null) {
      return;
    }
    BackendSessionBinding steadyBinding = new BackendSessionBinding(player.getUniqueId(),
        liveSession.clientConnectionId(), serverId, liveSession.playerSessionEpoch(),
        liveSession.routeGeneration(), null);
    sourceConnection.getWorldlineBinding().ifPresentOrElse(existing ->
        com.google.common.base.Preconditions.checkState(existing.matchesAuthority(
            steadyBinding.playerUuid(), steadyBinding.clientConnectionId(),
            steadyBinding.backendServerId(), steadyBinding.playerSessionEpoch(),
            steadyBinding.routeGeneration()),
            "Existing Worldline source binding does not match live authority"),
        () -> sourceConnection.installWorldlineBinding(steadyBinding));
    UUID transferId = UUID.randomUUID();
    String destinationServerId = decision.remoteOwner().orElse("unknown");
    ControlEnvelope envelope = worldlineEnvelope(transferId, serverId, destinationServerId,
        decision, liveSession);
    double targetX = decision.action() == BoundaryCrossingDetector.Action.PREPARE
        ? decision.remoteEntryChunkX() * 16.0 + 8.0 : packet.getX();
    final PrepareTarget target = server.getWorldlineControlPlane().prepareTarget(player.getUsername(),
        targetX, packet.getY(), packet.getZ());
    CompletableFuture<LivePlayerSessionStore.TransitionResult> future = new CompletableFuture<>();
    worldlineTransferId = transferId;
    worldlineClientTransitionPackets.set(0);
    worldlineEnvelope = envelope;
    worldlinePrepareFuture = future;
    logger.info("Worldline preparing {} handoff from {} to {}", player, serverId,
        destinationServerId);
    Thread.startVirtualThread(() -> {
      try {
        future.complete(server.getWorldlineControlPlane().prepare(envelope, target));
      } catch (Throwable throwable) {
        future.completeExceptionally(throwable);
      }
    });
    future.whenCompleteAsync((result, failure) -> {
      if (worldlinePrepareFuture != future || worldlineEnvelope != envelope) {
        return;
      }
      worldlinePrepareFuture = null;
      if (failure == null && (result.status() == LivePlayerSessionStore.Status.APPLIED
          || result.status() == LivePlayerSessionStore.Status.ALREADY_APPLIED)) {
        worldlineMovementRouter.markDestinationReady();
        logger.info("Worldline destination ready for {} from {} to {}", player, serverId,
            destinationServerId);
        if (worldlineMovementRouter.hasBufferedInput()) {
          beginWorldlineSnapshotStage(envelope);
        }
        return;
      }
      worldlineTransferId = null;
      worldlineEnvelope = null;
      if (worldlineMovementRouter.hasBufferedInput()) {
        worldlineMovementRouter.blockCrossing();
      } else {
        worldlineMovementRouter.clearBuffer();
      }
      logger.warn("Worldline prepare rejected for {} from {} to {}: {}", player, serverId,
          destinationServerId, failure == null ? result.status() : failure.getMessage());
    }, player.getConnection().eventLoop());
  }

  private void beginWorldlineSnapshotStage(final ControlEnvelope envelope) {
    if (worldlineStageFuture != null) {
      return;
    }
    CompletableFuture<LivePlayerSessionStore.TransitionResult> future = new CompletableFuture<>();
    worldlineStageFuture = future;
    worldlineFreezeInProgress = true;
    Thread.startVirtualThread(() -> {
      try {
        HandoffControlPlane.SnapshotResult frozen =
            server.getWorldlineControlPlane().freezeSource(envelope);
        LivePlayerSessionStore.TransitionResult result = frozen.transition();
        if (result.status() != LivePlayerSessionStore.Status.APPLIED
            && result.status() != LivePlayerSessionStore.Status.ALREADY_APPLIED) {
          future.complete(result);
          return;
        }
        future.complete(server.getWorldlineControlPlane().stageSnapshot(envelope,
            frozen.snapshot()));
      } catch (Throwable throwable) {
        future.completeExceptionally(throwable);
      }
    });
    future.whenCompleteAsync((result, failure) -> {
      if (worldlineStageFuture != future || worldlineEnvelope != envelope) {
        return;
      }
      worldlineStageFuture = null;
      worldlineFreezeInProgress = false;
      if (failure == null && (result.status() == LivePlayerSessionStore.Status.APPLIED
          || result.status() == LivePlayerSessionStore.Status.ALREADY_APPLIED)) {
        logger.info("Worldline staged snapshot for {} transfer {}; starting M5 commit",
            player, envelope.transferId());
        beginWorldlineCommit(envelope);
        return;
      }
      abortWorldlineHandoff(true);
      logger.warn("Worldline snapshot staging failed for {} transfer {}: {}", player,
          envelope.transferId(), failure == null ? result.status() : failure.getMessage());
    }, player.getConnection().eventLoop());
  }

  private void beginWorldlineCommit(final ControlEnvelope envelope) {
    if (worldlinePostCommitFuture != null || worldlineMovementRouter == null) {
      return;
    }
    VelocityServerConnection source = player.getConnectedServer();
    if (source == null || source.getEntityId() == null) {
      abortWorldlineHandoff(true);
      return;
    }
    HandoffReplayBuffer<ServerboundMovePlayerPacket> replay = new HandoffReplayBuffer<>(
        envelope.transferId(), envelope.playerSessionEpoch() + 1,
        envelope.routeGeneration() + 1, 64);
    for (ServerboundMovePlayerPacket packet : worldlineMovementRouter.drainBuffer()) {
      if (replay.append(packet) != HandoffReplayBuffer.AppendResult.APPENDED) {
        abortWorldlineHandoff(true);
        return;
      }
    }
    worldlineReplayBuffer = replay;
    worldlineSourceConnection = source;
    CompletableFuture<HandoffControlPlane.CommitBarrierResult> commit = new CompletableFuture<>();
    worldlinePostCommitFuture = commit;
    Thread.startVirtualThread(() -> {
      try {
        commit.complete(server.getWorldlineControlPlane().commit(envelope,
            () -> worldlinePostCommitDeadline = player.getConnection().eventLoop().schedule(
                () -> failWorldlinePostCommit(envelope, "post-commit deadline expired"),
                WORLDLINE_POST_COMMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)));
      } catch (Throwable throwable) {
        commit.completeExceptionally(throwable);
      }
    });
    commit.whenCompleteAsync((barrier, failure) -> {
      if (worldlinePostCommitFuture != commit || worldlineEnvelope != envelope
          || worldlinePostCommitTerminated) {
        return;
      }
      if (failure != null || barrier.status()
          != HandoffControlPlane.CommitBarrierStatus.COMPLETE) {
        if (worldlineCommitted(envelope)) {
          failWorldlinePostCommit(envelope, "commit barrier did not complete");
        } else {
          worldlinePostCommitFuture = null;
          worldlineReplayBuffer = null;
          abortWorldlineHandoff(true);
        }
        return;
      }
      WorldlineResumeContext context = new WorldlineResumeContext(
          HandoffControlPlane.PROTOCOL_VERSION, envelope.transferId(), envelope.playerUuid(),
          envelope.clientConnectionId(), envelope.sourceServerId(),
          envelope.destinationServerId(), envelope.sourcePartitionId(),
          envelope.sourcePartitionEpoch(), envelope.destinationPartitionId(),
          envelope.destinationPartitionEpoch(), envelope.playerSessionEpoch(),
          envelope.playerSessionEpoch() + 1, envelope.playerStateVersion(),
          envelope.routeGeneration() + 1, source.getEntityId());
      player.requestWorldlineDestination(context).whenCompleteAsync((destination, connectFailure) -> {
        if (worldlinePostCommitTerminated || worldlineEnvelope != envelope) {
          if (destination != null) {
            destination.disconnect();
          }
          return;
        }
        if (connectFailure != null) {
          failWorldlinePostCommit(envelope, "destination connection failed");
          return;
        }
        try {
          worldlineDestinationConnection = destination;
          worldlineSourceConnection = player.installWorldlineDestination(destination);
        } catch (RuntimeException routeFailure) {
          failWorldlinePostCommit(envelope, "destination route installation failed");
          return;
        }
        beginWorldlineActivation(envelope, destination);
      }, player.getConnection().eventLoop());
    }, player.getConnection().eventLoop());
  }

  private void beginWorldlineActivation(final ControlEnvelope envelope,
      final VelocityServerConnection destination) {
    CompletableFuture<LivePlayerSessionStore.TransitionResult> activation =
        new CompletableFuture<>();
    worldlinePostCommitFuture = activation;
    Thread.startVirtualThread(() -> {
      try {
        activation.complete(server.getWorldlineControlPlane().activateDestination(envelope));
      } catch (Throwable throwable) {
        activation.completeExceptionally(throwable);
      }
    });
    activation.whenCompleteAsync((result, failure) -> {
      if (worldlinePostCommitFuture != activation || worldlineEnvelope != envelope
          || worldlinePostCommitTerminated) {
        return;
      }
      if (failure != null || !successful(result)) {
        failWorldlinePostCommit(envelope, "destination activation failed");
        return;
      }
      HandoffReplayBuffer<ServerboundMovePlayerPacket> replay = worldlineReplayBuffer;
      if (replay == null) {
        failWorldlinePostCommit(envelope, "replay state disappeared");
        return;
      }
      try {
        destination.ensureConnected().setAutoReading(true);
        for (HandoffReplayBuffer.Entry<ServerboundMovePlayerPacket> entry : replay.drain(
            envelope.transferId(), envelope.playerSessionEpoch() + 1,
            envelope.routeGeneration() + 1)) {
          ServerboundMovePlayerPacket movement = entry.value();
          logger.info("Worldline replay transfer={} sequence={} has_position={} x={} y={} z={}",
              envelope.transferId(), entry.sequence(), movement.hasPosition(),
              movement.hasPosition() ? movement.getX() : Double.NaN,
              movement.hasPosition() ? movement.getY() : Double.NaN,
              movement.hasPosition() ? movement.getZ() : Double.NaN);
          destination.ensureConnected().write(entry.value());
        }
        destination.ensureConnected().flush();
      } catch (RuntimeException replayFailure) {
        failWorldlinePostCommit(envelope,
            "movement replay failed: " + replayFailure.getMessage());
        return;
      }
      worldlineReplayBuffer = null;
      beginWorldlineSourceCleanup(envelope);
    }, player.getConnection().eventLoop());
  }

  private void beginWorldlineSourceCleanup(final ControlEnvelope envelope) {
    CompletableFuture<LivePlayerSessionStore.TransitionResult> cleanup =
        new CompletableFuture<>();
    worldlinePostCommitFuture = cleanup;
    Thread.startVirtualThread(() -> {
      LivePlayerSessionStore.TransitionResult last = null;
      try {
        for (int attempt = 0; attempt < 3; attempt++) {
          last = server.getWorldlineControlPlane().cleanSource(envelope);
          if (successful(last)) {
            break;
          }
        }
        cleanup.complete(last);
      } catch (Throwable throwable) {
        cleanup.completeExceptionally(throwable);
      }
    });
    cleanup.whenCompleteAsync((result, failure) -> {
      if (worldlinePostCommitFuture != cleanup || worldlineEnvelope != envelope
          || worldlinePostCommitTerminated) {
        return;
      }
      if (failure != null || !successful(result)) {
        failWorldlinePostCommit(envelope, "source cleanup failed");
        return;
      }
      if (worldlineSourceConnection != null) {
        player.disconnectWorldlineSourceAfterCleanup(worldlineSourceConnection);
      }
      LivePlayerSessionStore.TransitionResult retired = server.getWorldlineLiveSessions()
          .retireTransfer(envelope.playerUuid(), envelope.playerSessionEpoch() + 1,
              envelope.transferId(), envelope.destinationServerId());
      if (!successful(retired)) {
        failWorldlinePostCommit(envelope, "proxy transfer retirement failed");
        return;
      }
      finishWorldlinePostCommit(envelope);
    }, player.getConnection().eventLoop());
  }

  private void finishWorldlinePostCommit(final ControlEnvelope envelope) {
    if (worldlinePostCommitDeadline != null) {
      worldlinePostCommitDeadline.cancel(false);
    }
    worldlinePostCommitDeadline = null;
    worldlinePostCommitFuture = null;
    worldlineSourceConnection = null;
    worldlineDestinationConnection = null;
    worldlineTransferId = null;
    worldlineEnvelope = null;
    worldlinePostCommitTerminated = false;
    logger.info("Worldline M5 completed transfer {} for {} epoch={} route_generation={} "
            + "client_transition_packets={}",
        envelope.transferId(), player, envelope.playerSessionEpoch() + 1,
        envelope.routeGeneration() + 1, worldlineClientTransitionPackets.get());
  }

  /** Records a backend packet that would have exposed the internal splice to the client. */
  public void recordWorldlineClientTransitionPacket() {
    worldlineClientTransitionPackets.incrementAndGet();
  }

  private void failWorldlinePostCommit(final ControlEnvelope envelope, final String reason) {
    if (worldlinePostCommitTerminated) {
      return;
    }
    worldlinePostCommitTerminated = true;
    if (worldlinePostCommitDeadline != null) {
      worldlinePostCommitDeadline.cancel(false);
      worldlinePostCommitDeadline = null;
    }
    if (worldlineReplayBuffer != null) {
      worldlineReplayBuffer.discard();
      worldlineReplayBuffer = null;
    }
    logger.error("Worldline terminal post-commit failure for {} transfer={}: {}", player,
        envelope.transferId(), reason);
    Thread.startVirtualThread(() -> {
      try {
        server.getWorldlineControlPlane().retireDestination(envelope);
      } catch (RuntimeException retirementFailure) {
        logger.error("Worldline destination retirement failed for {} transfer={}", player,
            envelope.transferId(), retirementFailure);
      }
      boolean sourceCleaned = false;
      for (int attempt = 0; attempt < 3; attempt++) {
        try {
          LivePlayerSessionStore.TransitionResult cleaned =
              server.getWorldlineControlPlane().cleanSource(envelope);
          if (successful(cleaned)) {
            sourceCleaned = true;
            break;
          }
        } catch (RuntimeException cleanupFailure) {
          logger.warn("Worldline source cleanup attempt {} failed for {} transfer={}",
              attempt + 1, player, envelope.transferId(), cleanupFailure);
        }
      }
      final boolean cleanupCompleted = sourceCleaned;
      if (!cleanupCompleted) {
        logger.error("Worldline retained pending source cleanup for {} transfer={}", player,
            envelope.transferId());
      }
      player.getConnection().eventLoop().execute(() -> {
        if (cleanupCompleted && worldlineSourceConnection != null) {
          player.disconnectWorldlineSourceAfterCleanup(worldlineSourceConnection);
        }
        player.disconnect(Component.translatable("velocity.error.player-connection-error",
            NamedTextColor.RED));
        if (cleanupCompleted) {
          player.teardown();
        }
      });
    });
  }

  private boolean worldlineCommitted(final ControlEnvelope envelope) {
    return server.getWorldlineLiveSessions().get(envelope.playerUuid())
        .map(session -> session.playerSessionEpoch() == envelope.playerSessionEpoch() + 1
            && envelope.transferId().equals(session.activeTransferId()))
        .orElse(false);
  }

  private static boolean successful(final LivePlayerSessionStore.TransitionResult result) {
    return result != null && (result.status() == LivePlayerSessionStore.Status.APPLIED
        || result.status() == LivePlayerSessionStore.Status.ALREADY_APPLIED);
  }

  private static int boundedIntegerProperty(final String name, final int defaultValue,
      final int minimum, final int maximum) {
    int value = Integer.getInteger(name, defaultValue);
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(name + " must be between " + minimum + " and "
          + maximum);
    }
    return value;
  }

  private void abortWorldlineHandoff() {
    abortWorldlineHandoff(false);
  }

  private void abortWorldlineHandoff(final boolean blockCrossing) {
    final ControlEnvelope envelope = worldlineEnvelope;
    final CompletableFuture<LivePlayerSessionStore.TransitionResult> prepareFuture =
        worldlinePrepareFuture;
    final CompletableFuture<LivePlayerSessionStore.TransitionResult> stageFuture =
        worldlineStageFuture;
    worldlineTransferId = null;
    worldlineEnvelope = null;
    worldlinePrepareFuture = null;
    worldlineStageFuture = null;
    worldlineFreezeInProgress = false;
    if (worldlineMovementRouter != null) {
      if (blockCrossing) {
        worldlineMovementRouter.blockCrossing();
      } else {
        worldlineMovementRouter.clearBuffer();
      }
    }
    if (envelope == null) {
      return;
    }
    Runnable abort = () -> {
      LivePlayerSessionStore.TransitionResult result =
          server.getWorldlineControlPlane().abort(envelope);
      if (result.status() != LivePlayerSessionStore.Status.APPLIED
          && result.status() != LivePlayerSessionStore.Status.ALREADY_APPLIED
          && result.status() != LivePlayerSessionStore.Status.MISSING_SESSION) {
        logger.warn("Worldline abort rejected for {} transfer {}: {}", player,
            envelope.transferId(), result.status());
      }
    };
    CompletableFuture<LivePlayerSessionStore.TransitionResult> pending =
        stageFuture != null ? stageFuture : prepareFuture;
    if (pending == null) {
      Thread.startVirtualThread(abort);
    } else {
      // Preserve command order so a late prepare or stage cannot recreate state after abort.
      pending.whenComplete((ignored, failure) -> Thread.startVirtualThread(abort));
    }
  }

  private ControlEnvelope worldlineEnvelope(final UUID transferId, final String sourceServerId,
      final String destinationServerId, final BoundaryCrossingDetector.Decision decision,
      final LivePlayerSession session) {
    return new ControlEnvelope(HandoffControlPlane.PROTOCOL_VERSION, transferId,
        player.getUniqueId(), session.clientConnectionId(), sourceServerId, destinationServerId,
        decision.sourcePartitionId().orElse("unknown"),
        decision.remotePartitionId().orElse("unknown"), decision.sourcePartitionEpoch(),
        decision.remotePartitionEpoch(), session.playerSessionEpoch(), 1,
        session.routeGeneration());
  }

  private void logWorldlineCrossingWithheld(final String serverId,
      final BoundaryCrossingDetector.Decision decision) {
    logger.info("Worldline withheld crossing movement for {} from {} to {}", player, serverId,
        decision.remoteOwner().orElse("unknown"));
  }

  @Override
  public void handleGeneric(MinecraftPacket packet) {
    if (abortFrozenGameplayInput(packet)) {
      return;
    }
    VelocityServerConnection serverConnection = player.getConnectedServer();
    if (serverConnection == null) {
      // No server connection yet, probably transitioning.
      return;
    }

    MinecraftConnection smc = serverConnection.getConnection();
    final boolean stateAllowsForward = smc != null
        && !smc.isClosed()
        && serverConnection.getPhase().consideredComplete()
        && smc.getState() == StateRegistry.PLAY;
    if (stateAllowsForward) {
      if (packet instanceof PluginMessagePacket) {
        ((PluginMessagePacket) packet).retain();
      }
      smc.write(packet);
    }
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    final int packetIdIndex = buf.readerIndex();
    final int packetId = ProtocolUtils.readVarInt(buf);
    buf.readerIndex(packetIdIndex);
    if (ServerboundHandoffTraffic.isConnectionBookkeeping(player.getProtocolVersion(),
        packetId)) {
      if (HandoffReplayGate.mustBuffer(worldlineCurrentPhase(), worldlineReplayBuffer != null)) {
        // Bookkeeping for the fenced-out source is dropped until the destination owns the route.
        return;
      }
    } else if (abortFrozenGameplayInput(String.format("unknown packet id=0x%02X", packetId))) {
      return;
    }
    VelocityServerConnection serverConnection = player.getConnectedServer();
    if (serverConnection == null) {
      // No server connection yet, probably transitioning.
      return;
    }

    MinecraftConnection smc = serverConnection.getConnection();
    final boolean stateAllowsForward = smc != null
        && !smc.isClosed()
        && serverConnection.getPhase().consideredComplete()
        && smc.getState() == StateRegistry.PLAY;
    if (stateAllowsForward) {
      smc.write(buf.retain());
    }
  }

  private boolean worldlineSourceFrozen() {
    if (worldlineFreezeInProgress) {
      return true;
    }
    if (worldlineTransferId == null) {
      return false;
    }
    return server.getWorldlineLiveSessions().get(player.getUniqueId())
        .map(session -> session.handoffPhase() == HandoffPhase.SOURCE_FROZEN
            || session.handoffPhase() == HandoffPhase.SNAPSHOT_STAGED)
        .orElse(false);
  }

  private boolean abortFrozenGameplayInput(MinecraftPacket packet) {
    return abortFrozenGameplayInput(packet.getClass().getSimpleName());
  }

  private boolean abortFrozenGameplayInput(String input) {
    if (HandoffReplayGate.rejectNonReplayable(worldlineCurrentPhase(),
        worldlineEnvelope != null)) {
      if (worldlineEnvelope != null) {
        failWorldlinePostCommit(worldlineEnvelope, "non-replayable post-commit gameplay input");
      }
      return true;
    }
    if (!worldlineSourceFrozen()) {
      return false;
    }
    abortWorldlineHandoff(true);
    logger.warn("Worldline aborted frozen transfer for {} on non-replayable gameplay input: {}",
        player, input);
    return true;
  }

  private @Nullable HandoffPhase worldlineCurrentPhase() {
    return server.getWorldlineLiveSessions().get(player.getUniqueId())
        .map(LivePlayerSession::handoffPhase).orElse(null);
  }

  @Override
  public void disconnected() {
    if (worldlineEnvelope != null && worldlineCommitted(worldlineEnvelope)) {
      failWorldlinePostCommit(worldlineEnvelope, "client disconnected after commit");
      return;
    } else {
      abortWorldlineHandoff();
      server.getWorldlineLiveSessions().remove(player.getUniqueId(), worldlineClientConnectionId);
    }
    player.teardown();
  }

  @Override
  public void exception(Throwable throwable) {
    player.disconnect(Component.translatable("velocity.error.player-connection-error", NamedTextColor.RED));
    if (MinecraftDecoder.DEBUG) {
      logger.info("Exception while handling packet for {}", player, throwable);
    }
  }

  @Override
  public void writabilityChanged() {
    boolean writable = player.getConnection().getChannel().isWritable();

    if (BACKPRESSURE_LOG) {
      if (writable) {
        logger.info("{} is writable, will auto-read backend connection data", player);
      } else {
        logger.info("{} is not writable, not auto-reading backend connection data", player);
      }
    }

    if (!writable) {
      // We might have packets queued from the server, so flush them now to free up memory. Make
      // sure to do it on a future invocation of the event loop, otherwise while the issue will
      // fix itself, we'll still disable auto-reading and instead of backpressure resolution, we
      // get client timeouts.
      player.getConnection().eventLoop().execute(() -> player.getConnection().flush());
    }

    VelocityServerConnection serverConn = player.getConnectedServer();
    if (serverConn != null) {
      MinecraftConnection smc = serverConn.getConnection();
      if (smc != null) {
        smc.setAutoReading(writable);
      }
    }
  }

  /**
   * Handles switching stages for swapping between servers.
   *
   * @return a future that completes when the switch is complete
   */
  public CompletableFuture<Void> doSwitch() {
    final VelocityServerConnection existingConnection = player.getConnectedServer();

    if (existingConnection != null) {
      // Shut down the existing server connection.
      player.setConnectedServer(null);
      existingConnection.disconnect();

      // Send keep alive to try to avoid timeouts
      player.sendKeepAlive();

      // Config state clears everything in the client. No need to clear later.
      spawned = false;
      player.clearPlayerListHeaderAndFooterSilent();
      player.getTabList().clearAllSilent();
      if (player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
        player.getBossBarManager().dropPackets();
      } else {
        serverBossBars.clear();
      }
    }

    player.switchToConfigState();

    return configSwitchFuture;
  }

  /**
   * Handles the {@code JoinGame} packet. This function is responsible for handling the client-side
   * switching servers in Velocity.
   *
   * @param joinGame    the join game packet
   * @param destination the new server we are connecting to
   */
  public void handleBackendJoinGame(JoinGamePacket joinGame, VelocityServerConnection destination) {
    final MinecraftConnection serverMc = destination.ensureConnected();

    if (!spawned) {
      // The player wasn't spawned in yet, so we don't need to do anything special. Just send
      // JoinGame.
      spawned = true;
      player.getConnection().delayedWrite(joinGame);
      // Required for Legacy Forge
      player.getPhase().onFirstJoin(player);
    } else {
      // Clear tab list to avoid duplicate entries
      player.getTabList().clearAll();

      // The player is switching from a server already, so we need to tell the client to change
      // entity IDs and send new dimension information.
      if (player.getConnection().getType() == ConnectionTypes.LEGACY_FORGE) {
        this.doSafeClientServerSwitch(joinGame);
      } else {
        this.doFastClientServerSwitch(joinGame);
      }
    }

    destination.setEntityId(joinGame.getEntityId()); // used for sound api
    if (player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
      player.getBossBarManager().sendBossBars();
    } else {
      // Remove previous boss bars. These don't get cleared when sending JoinGame (up until 1.20.2),
      // thus the need to track them.
      for (UUID serverBossBar : serverBossBars) {
        BossBarPacket deletePacket = new BossBarPacket();
        deletePacket.setUuid(serverBossBar);
        deletePacket.setAction(BossBarPacket.REMOVE);
        player.getConnection().delayedWrite(deletePacket);
      }
      serverBossBars.clear();
    }

    // Tell the server about the proxy's plugin message channels.
    ProtocolVersion serverVersion = serverMc.getProtocolVersion();
    final Collection<ChannelIdentifier> channels = server.getChannelRegistrar()
        .getChannelsForProtocol(serverMc.getProtocolVersion());
    if (!channels.isEmpty()) {
      serverMc.delayedWrite(constructChannelsPacket(serverVersion, channels));
    }
    // Tell the server about this client's plugin message channels.
    if (!player.getClientsideChannels().isEmpty()) {
      serverMc.delayedWrite(constructChannelsPacket(serverVersion, player.getClientsideChannels()));
    }

    // If we had plugin messages queued during login/FML handshake, send them now.
    PluginMessagePacket pm;
    while ((pm = loginPluginMessages.poll()) != null) {
      serverMc.delayedWrite(pm);
    }
    loginPluginMessagesBytes.set(0);
    loginPluginMessagesCount.set(0);

    // Clear any title from the previous server.
    if (player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_8)) {
      player.getConnection().delayedWrite(
          GenericTitlePacket.constructTitlePacket(GenericTitlePacket.ActionType.RESET,
              player.getProtocolVersion()));
    }

    // Flush everything
    player.getConnection().flush();
    serverMc.flush();
    destination.completeJoin();
  }

  private void doFastClientServerSwitch(JoinGamePacket joinGame) {
    // In order to handle switching to another server, you will need to send two packets:
    //
    // - The join game packet from the backend server, with a different dimension
    // - A respawn with the correct dimension
    //
    // Most notably, by having the client accept the join game packet, we can work around the need
    // to perform entity ID rewrites, eliminating potential issues from rewriting packets and
    // improving compatibility with mods.
    final RespawnPacket respawn = RespawnPacket.fromJoinGame(joinGame);

    if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_16)) {
      // Before Minecraft 1.16, we could not switch to the same dimension without sending an
      // additional respawn. On older versions of Minecraft this forces the client to perform
      // garbage collection which adds additional latency.
      joinGame.setDimension(joinGame.getDimension() == 0 ? -1 : 0);
    }
    player.getConnection().delayedWrite(joinGame);
    player.getConnection().delayedWrite(respawn);
  }

  private void doSafeClientServerSwitch(JoinGamePacket joinGame) {
    // Some clients do not behave well with the "fast" respawn sequence. In this case we will use
    // a "safe" respawn sequence that involves sending three packets to the client. They have the
    // same effect but tend to work better with buggier clients (Forge 1.8 in particular).

    // Send the JoinGame packet itself, unmodified.
    player.getConnection().delayedWrite(joinGame);

    // Send a respawn packet in a different dimension.
    final RespawnPacket fakeSwitchPacket = RespawnPacket.fromJoinGame(joinGame);
    fakeSwitchPacket.setDimension(joinGame.getDimension() == 0 ? -1 : 0);
    player.getConnection().delayedWrite(fakeSwitchPacket);

    // Now send a respawn packet in the correct dimension.
    final RespawnPacket correctSwitchPacket = RespawnPacket.fromJoinGame(joinGame);
    player.getConnection().delayedWrite(correctSwitchPacket);
  }

  public List<UUID> getServerBossBars() {
    return serverBossBars;
  }

  private boolean handleCommandTabComplete(TabCompleteRequestPacket packet) {
    // In 1.13+, we need to do additional work for the richer suggestions available.
    String command = packet.getCommand().substring(1);
    int commandEndPosition = command.indexOf(' ');
    if (commandEndPosition == -1) {
      commandEndPosition = command.length();
    }

    String commandLabel = command.substring(0, commandEndPosition);
    if (!server.getCommandManager().hasCommand(commandLabel, player)) {
      if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_13)) {
        // Outstanding tab completes are recorded for use with 1.12 clients and below to provide
        // additional tab completion support.
        outstandingTabComplete = packet;
      }
      return false;
    }

    if (!server.getTabCompleteRateLimiter().attempt(player.getUniqueId())) {
      if (server.getConfiguration().isKickOnTabCompleteRateLimit()
              && failedTabCompleteAttempts++ >= server.getConfiguration().getKickAfterRateLimitedTabCompletes()) {
        player.disconnect(Component.translatable("velocity.kick.tab-complete-rate-limit"));
      }

      return true;
    }

    failedTabCompleteAttempts = 0;

    server.getCommandManager().offerBrigadierSuggestions(player, command)
        .thenAcceptAsync(suggestions -> {
          if (suggestions.isEmpty()) {
            return;
          }

          int startPos = -1;
          for (var suggestion : suggestions.getList()) {
            if (startPos == -1 || startPos > suggestion.getRange().getStart()) {
              startPos = suggestion.getRange().getStart();
            }
          }

          if (startPos > 0) {
            List<Offer> offers = new ArrayList<>();
            for (Suggestion suggestion : suggestions.getList()) {
              String offer;
              if (suggestion.getRange().getStart() == startPos) {
                offer = suggestion.getText();
              } else {
                offer = command.substring(startPos, suggestion.getRange().getStart()) + suggestion.getText();
              }
              ComponentHolder tooltip = null;
              if (suggestion.getTooltip() instanceof ComponentLike componentLike) {
                tooltip = new ComponentHolder(player.getProtocolVersion(), componentLike.asComponent());
              } else if (suggestion.getTooltip() != null) {
                tooltip = new ComponentHolder(player.getProtocolVersion(), Component.text(suggestion.getTooltip().getString()));
              }
              offers.add(new Offer(offer, tooltip));
            }

            TabCompleteResponsePacket resp = new TabCompleteResponsePacket();
            resp.setTransactionId(packet.getTransactionId());
            resp.setStart(startPos + 1);
            resp.setLength(packet.getCommand().length() - startPos - 1);
            resp.getOffers().addAll(offers);
            player.getConnection().write(resp);
          }
        }, player.getConnection().eventLoop()).exceptionally((ex) -> {
          logger.error("Exception while handling command tab completion for player {} executing {}",
              player, command, ex);
          return null;
        });
    return true; // Sorry, handler; we're just gonna have to lie to you here.
  }

  private boolean handleRegularTabComplete(TabCompleteRequestPacket packet) {
    if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_13)) {
      // Outstanding tab completes are recorded for use with 1.12 clients and below to provide
      // additional tab completion support.
      outstandingTabComplete = packet;
    }
    return false;
  }

  /**
   * Handles additional tab complete.
   *
   * @param response the tab complete response from the backend
   */
  public void handleTabCompleteResponse(TabCompleteResponsePacket response) {
    if (outstandingTabComplete != null && !outstandingTabComplete.isAssumeCommand()) {
      if (outstandingTabComplete.getCommand().startsWith("/")) {
        this.finishCommandTabComplete(outstandingTabComplete, response);
      } else {
        this.finishRegularTabComplete(outstandingTabComplete, response);
      }
      outstandingTabComplete = null;
    } else {
      // Nothing to do
      player.getConnection().write(response);
    }
  }

  private void finishCommandTabComplete(TabCompleteRequestPacket request,
                                        TabCompleteResponsePacket response) {
    String command = request.getCommand().substring(1);
    server.getCommandManager().offerBrigadierSuggestions(player, command)
        .thenAcceptAsync(offers -> {
          boolean legacy =
              player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_13);
          try {
            for (Suggestion suggestion : offers.getList()) {
              String offer = suggestion.getText();
              offer = legacy && !offer.startsWith("/") ? "/" + offer : offer;
              if (legacy && offer.startsWith(command)) {
                offer = offer.substring(command.length());
              }
              ComponentHolder tooltip = null;
              if (suggestion.getTooltip() instanceof ComponentLike componentLike) {
                tooltip = new ComponentHolder(player.getProtocolVersion(), componentLike.asComponent());
              } else if (suggestion.getTooltip() != null) {
                tooltip = new ComponentHolder(player.getProtocolVersion(), Component.text(suggestion.getTooltip().getString()));
              }
              response.getOffers().add(new Offer(offer, tooltip));
            }
            response.getOffers().sort(null);
            player.getConnection().write(response);
          } catch (Exception e) {
            logger.error("Unable to provide tab list completions for {} for command '{}'",
                player.getUsername(), command,
                e);
          }
        }, player.getConnection().eventLoop()).exceptionally((ex) -> {
          logger.error(
              "Exception while finishing command tab completion,"
                  + " with request {} and response {}",
              request, response, ex);
          return null;
        });
  }

  private void finishRegularTabComplete(TabCompleteRequestPacket request,
                                        TabCompleteResponsePacket response) {
    List<String> offers = new ArrayList<>();
    for (Offer offer : response.getOffers()) {
      offers.add(offer.getText());
    }
    server.getEventManager().fire(new TabCompleteEvent(player, request.getCommand(), offers))
        .thenAcceptAsync(e -> {
          response.getOffers().clear();
          for (String s : e.getSuggestions()) {
            response.getOffers().add(new Offer(s));
          }
          player.getConnection().write(response);
        }, player.getConnection().eventLoop()).exceptionally((ex) -> {
          logger.error(
              "Exception while finishing regular tab completion,"
                  + " with request {} and response{}",
              request, response, ex);
          return null;
        });
  }

  /**
   * Immediately send any queued messages to the server.
   */
  public void flushQueuedMessages() {
    VelocityServerConnection serverConnection = player.getConnectedServer();
    if (serverConnection != null) {
      MinecraftConnection connection = serverConnection.getConnection();
      if (connection != null) {
        PluginMessagePacket pm;
        while ((pm = loginPluginMessages.poll()) != null) {
          connection.write(pm);
        }
        loginPluginMessagesBytes.set(0);
        loginPluginMessagesCount.set(0);
      }
    }
  }
}
