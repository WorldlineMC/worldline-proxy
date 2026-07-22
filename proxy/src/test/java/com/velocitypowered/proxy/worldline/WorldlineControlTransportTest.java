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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the experimental control transport over real loopback sockets. */
public class WorldlineControlTransportTest {

  private static final UUID TRANSFER = UUID.fromString("00000000-0000-0000-0000-000000000031");
  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000032");
  private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000033");
  private static final PrepareTarget TARGET = new PrepareTarget("WorldlineTest", "world",
      "minecraft:overworld", "test-v1", 8, 64, 0, 1);

  @TempDir
  Path tempDir;

  @Test
  void allowsPaperPreparationToFinishBeforeTheSocketTimesOut() {
    assertEquals(3_000, WorldlineControlTransport.TIMEOUT_MILLIS);
  }

  @Test
  void framesAndCorrelatesCompleteEnvelope() throws Exception {
    try (FakeControlServer server = new FakeControlServer(1, Response.VALID)) {
      transport(server.port()).send("server-b", "PREPARE", envelope(), TARGET);

      assertEquals(List.of("PREPARE"), server.commands());
      assertEquals(List.of(envelope()), server.envelopes());
      assertEquals(List.of(TARGET), server.targets());
    }
  }

  @Test
  void duplicateDeliveryKeepsStableCorrelationIdentifiers() throws Exception {
    try (FakeControlServer server = new FakeControlServer(2, Response.VALID)) {
      WorldlineControlTransport transport = transport(server.port());

      transport.send("server-b", "PREPARE", envelope(), TARGET);
      transport.send("server-b", "PREPARE", envelope(), TARGET);

      assertEquals(List.of(envelope(), envelope()), server.envelopes());
    }
  }

  @Test
  void commitSourceUsesSourceOwnershipAcknowledgement() throws Exception {
    try (FakeControlServer server = new FakeControlServer(1, Response.VALID)) {
      transport(server.port()).send("server-a", "COMMIT_SOURCE", envelope());

      assertEquals(List.of("COMMIT_SOURCE"), server.commands());
    }
  }

  @Test
  void carriesBoundedRequestAndResponsePayloads() throws Exception {
    try (FakeControlServer server = new FakeControlServer(1, Response.VALID_PAYLOAD)) {
      byte[] response = transport(server.port()).send("server-b", "STAGE_SNAPSHOT", envelope(),
          null, new byte[]{1, 2, 3});

      assertEquals(List.of(List.of((byte) 1, (byte) 2, (byte) 3)), server.payloads());
      assertEquals(List.of((byte) 4, (byte) 5, (byte) 6), boxed(response));
    }
  }

  @Test
  void rejectsOversizedPayloadBeforeConnecting() throws Exception {
    byte[] oversized = new byte[WorldlineControlTransport.MAX_PAYLOAD_BYTES + 1];
    assertThrows(IOException.class, () -> transport(1).send("server-b", "STAGE_SNAPSHOT",
        envelope(), null, oversized));
  }

  @Test
  void rejectsMismatchedAcknowledgement() throws Exception {
    try (FakeControlServer server = new FakeControlServer(1, Response.WRONG_TRANSFER)) {
      assertThrows(IOException.class,
          () -> transport(server.port()).send("server-b", "PREPARE", envelope(), TARGET));
    }
  }

  @Test
  void rejectsMismatchedClientAndRouteAcknowledgements() throws Exception {
    try (FakeControlServer server = new FakeControlServer(1, Response.WRONG_CLIENT)) {
      assertThrows(IOException.class,
          () -> transport(server.port()).send("server-b", "PREPARE", envelope(), TARGET));
    }
    try (FakeControlServer server = new FakeControlServer(1, Response.WRONG_ROUTE)) {
      assertThrows(IOException.class,
          () -> transport(server.port()).send("server-b", "PREPARE", envelope(), TARGET));
    }
  }

  @Test
  void rejectsMalformedResponse() throws Exception {
    try (FakeControlServer server = new FakeControlServer(1, Response.MALFORMED)) {
      assertThrows(IOException.class,
          () -> transport(server.port()).send("server-b", "PREPARE", envelope(), TARGET));
    }
  }

  private WorldlineControlTransport transport(final int port) throws Exception {
    Path config = tempDir.resolve("worldline-" + port + ".toml");
    Files.writeString(config, """
        [world]
        level-name = "world"
        dimension = "minecraft:overworld"
        compatibility-id = "test-v1"

        [servers.server-a]
        control-address = "127.0.0.1:%1$d"

        [servers.server-b]
        control-address = "127.0.0.1:%1$d"

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
        """.formatted(port));
    return new WorldlineControlTransport(StaticPartitionMap.read(config));
  }

  private static ControlEnvelope envelope() {
    return new ControlEnvelope(HandoffControlPlane.PROTOCOL_VERSION, TRANSFER, PLAYER,
        CLIENT, "server-a", "server-b", "west", "east", 1, 1, 4, 9, 7);
  }

  private enum Response {
    VALID,
    VALID_PAYLOAD,
    WRONG_TRANSFER,
    WRONG_CLIENT,
    WRONG_ROUTE,
    MALFORMED
  }

  private static final class FakeControlServer implements AutoCloseable {

    private final ServerSocket listener = new ServerSocket();
    private final List<String> commands = new ArrayList<>();
    private final List<ControlEnvelope> envelopes = new ArrayList<>();
    private final List<PrepareTarget> targets = new ArrayList<>();
    private final List<List<Byte>> payloads = new ArrayList<>();
    private final Thread thread;
    private volatile Throwable failure;

    FakeControlServer(final int requestCount, final Response response) throws IOException {
      listener.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      thread = new Thread(() -> serve(requestCount, response), "Worldline transport test");
      thread.start();
    }

    int port() {
      return listener.getLocalPort();
    }

    List<String> commands() {
      return List.copyOf(commands);
    }

    List<ControlEnvelope> envelopes() {
      return List.copyOf(envelopes);
    }

    List<PrepareTarget> targets() {
      return List.copyOf(targets);
    }

    List<List<Byte>> payloads() {
      return List.copyOf(payloads);
    }

    private void serve(final int requestCount, final Response response) {
      try {
        for (int request = 0; request < requestCount; request++) {
          try (Socket socket = listener.accept()) {
            handle(socket, response);
          }
        }
      } catch (Throwable throwable) {
        if (!listener.isClosed()) {
          failure = throwable;
        }
      }
    }

    private void handle(final Socket socket, final Response response) throws IOException {
      DataInputStream input = new DataInputStream(socket.getInputStream());
      if (input.readInt() != WorldlineControlTransport.MAGIC) {
        throw new IOException("wrong request magic");
      }
      int protocolVersion = input.readInt();
      String command = input.readUTF();
      commands.add(command);
      UUID transferId = readUuid(input);
      UUID playerId = readUuid(input);
      UUID clientConnectionId = readUuid(input);
      String sourceServer = input.readUTF();
      String destinationServer = input.readUTF();
      String sourcePartition = input.readUTF();
      long sourcePartitionEpoch = input.readLong();
      String destinationPartition = input.readUTF();
      long destinationPartitionEpoch = input.readLong();
      ControlEnvelope received = new ControlEnvelope(protocolVersion, transferId, playerId,
          clientConnectionId, sourceServer, destinationServer, sourcePartition,
          destinationPartition, sourcePartitionEpoch, destinationPartitionEpoch,
          input.readLong(), input.readLong(), input.readLong());
      envelopes.add(received);
      if (input.readBoolean()) {
        targets.add(new PrepareTarget(input.readUTF(), input.readUTF(), input.readUTF(),
            input.readUTF(), input.readDouble(), input.readDouble(), input.readDouble(),
            input.readInt()));
      }
      int payloadLength = input.readInt();
      payloads.add(boxed(input.readNBytes(payloadLength)));

      DataOutputStream output = new DataOutputStream(socket.getOutputStream());
      output.writeInt(WorldlineControlTransport.MAGIC);
      if (response == Response.MALFORMED) {
        output.flush();
        return;
      }
      output.writeInt(HandoffControlPlane.PROTOCOL_VERSION);
      output.writeBoolean(true);
      output.writeUTF("accepted");
      byte[] responsePayload = response == Response.VALID_PAYLOAD
          ? new byte[]{4, 5, 6} : new byte[0];
      output.writeInt(responsePayload.length);
      output.write(responsePayload);
      ControlEnvelope echoed = switch (response) {
        case WRONG_TRANSFER -> changedEnvelope(received, UUID.randomUUID(),
            received.clientConnectionId(), received.routeGeneration());
        case WRONG_CLIENT -> changedEnvelope(received, received.transferId(), UUID.randomUUID(),
            received.routeGeneration());
        case WRONG_ROUTE -> changedEnvelope(received, received.transferId(),
            received.clientConnectionId(), received.routeGeneration() + 1);
        default -> received;
      };
      writeEnvelope(output, echoed);
      boolean sourceCommand = command.equals("CHECK_PREPARE")
          || command.equals("FREEZE_SOURCE") || command.equals("ABORT_SOURCE")
          || command.equals("COMMIT_SOURCE") || command.equals("CLEAN_SOURCE");
      output.writeUTF(sourceCommand ? "server-a" : "server-b");
      output.writeUTF(sourceCommand ? "west" : "east");
      output.writeLong(1);
      output.flush();
    }

    @Override
    public void close() throws Exception {
      listener.close();
      thread.join(2_000);
      if (thread.isAlive()) {
        throw new AssertionError("control test server did not stop");
      }
      if (failure != null) {
        throw new AssertionError("control test server failed", failure);
      }
    }

    private static UUID readUuid(final DataInputStream input) throws IOException {
      return new UUID(input.readLong(), input.readLong());
    }

    private static void writeUuid(final DataOutputStream output, final UUID value)
        throws IOException {
      output.writeLong(value.getMostSignificantBits());
      output.writeLong(value.getLeastSignificantBits());
    }

    private static void writeEnvelope(final DataOutputStream output,
        final ControlEnvelope envelope) throws IOException {
      output.writeInt(envelope.protocolVersion());
      writeUuid(output, envelope.transferId());
      writeUuid(output, envelope.playerUuid());
      writeUuid(output, envelope.clientConnectionId());
      output.writeUTF(envelope.sourceServerId());
      output.writeUTF(envelope.destinationServerId());
      output.writeUTF(envelope.sourcePartitionId());
      output.writeUTF(envelope.destinationPartitionId());
      output.writeLong(envelope.sourcePartitionEpoch());
      output.writeLong(envelope.destinationPartitionEpoch());
      output.writeLong(envelope.playerSessionEpoch());
      output.writeLong(envelope.playerStateVersion());
      output.writeLong(envelope.routeGeneration());
    }

    private static ControlEnvelope changedEnvelope(final ControlEnvelope received,
        final UUID transferId, final UUID clientId, final long routeGeneration) {
      return new ControlEnvelope(received.protocolVersion(), transferId,
          received.playerUuid(), clientId, received.sourceServerId(),
          received.destinationServerId(), received.sourcePartitionId(),
          received.destinationPartitionId(), received.sourcePartitionEpoch(),
          received.destinationPartitionEpoch(), received.playerSessionEpoch(),
          received.playerStateVersion(), routeGeneration);
    }
  }

  private static List<Byte> boxed(final byte[] bytes) {
    List<Byte> result = new ArrayList<>(bytes.length);
    for (byte value : bytes) {
      result.add(value);
    }
    return result;
  }
}
