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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Slice-only blocking TCP transport for proxy-to-server handoff commands. Each request opens a
 * fresh connection; there are no hidden retries, so callers retry with the same envelope when the
 * authoritative session record says that is safe.
 */
public final class WorldlineControlTransport {

  static final int MAGIC = 0x574c4d32;
  private static final int TIMEOUT_MILLIS = 2_000;

  private final StaticPartitionMap partitions;

  public WorldlineControlTransport(final StaticPartitionMap partitions) {
    this.partitions = partitions;
  }

  /**
   * Sends one idempotent command and validates the server's fenced acknowledgement.
   */
  public void send(final String serverId, final String command,
      final ControlEnvelope envelope) throws IOException {
    send(serverId, command, envelope, null);
  }

  /**
   * Sends one idempotent command with an optional destination preparation target.
   */
  public void send(final String serverId, final String command,
      final ControlEnvelope envelope, final @Nullable PrepareTarget target) throws IOException {
    InetSocketAddress address = partitions.controlAddress(serverId)
        .orElseThrow(() -> new IOException("No control endpoint for " + serverId));
    try (Socket socket = new Socket()) {
      socket.connect(address, TIMEOUT_MILLIS);
      socket.setSoTimeout(TIMEOUT_MILLIS);
      DataOutputStream output = new DataOutputStream(socket.getOutputStream());
      output.writeInt(MAGIC);
      output.writeInt(envelope.protocolVersion());
      output.writeUTF(command);
      writeUuid(output, envelope.transferId());
      writeUuid(output, envelope.playerUuid());
      output.writeUTF(envelope.sourceServerId());
      output.writeUTF(envelope.destinationServerId());
      output.writeUTF(envelope.sourcePartitionId());
      output.writeLong(envelope.sourcePartitionEpoch());
      output.writeUTF(envelope.destinationPartitionId());
      output.writeLong(envelope.destinationPartitionEpoch());
      output.writeLong(envelope.playerSessionEpoch());
      output.writeLong(envelope.playerStateVersion());
      output.writeBoolean(target != null);
      if (target != null) {
        output.writeUTF(target.playerName());
        output.writeUTF(target.levelName());
        output.writeUTF(target.dimension());
        output.writeUTF(target.compatibilityId());
        output.writeDouble(target.x());
        output.writeDouble(target.y());
        output.writeDouble(target.z());
        output.writeInt(target.visibilityRadiusChunks());
      }
      output.flush();

      DataInputStream input = new DataInputStream(socket.getInputStream());
      if (input.readInt() != MAGIC || input.readInt() != HandoffControlPlane.PROTOCOL_VERSION) {
        throw new IOException("Invalid Worldline control response from " + serverId);
      }
      boolean accepted = input.readBoolean();
      String detail = input.readUTF();
      ControlEnvelope responseEnvelope = new ControlEnvelope(input.readInt(), readUuid(input),
          readUuid(input), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(),
          input.readLong(), input.readLong(), input.readLong(), input.readLong());
      String responseServer = input.readUTF();
      String responsePartition = input.readUTF();
      long responseEpoch = input.readLong();
      if (!accepted) {
        throw new IOException(serverId + " rejected " + command + ": " + detail);
      }
      if (!responseEnvelope.equals(envelope) || !responseServer.equals(serverId)) {
        throw new IOException("Mismatched Worldline control acknowledgement from " + serverId);
      }
      boolean sourceCommand = command.equals("CHECK_PREPARE")
          || command.equals("FREEZE_SOURCE") || command.equals("CLEAN_SOURCE");
      String expectedPartition = sourceCommand
          ? envelope.sourcePartitionId() : envelope.destinationPartitionId();
      long expectedEpoch = sourceCommand
          ? envelope.sourcePartitionEpoch() : envelope.destinationPartitionEpoch();
      if (!responsePartition.equals(expectedPartition) || responseEpoch != expectedEpoch) {
        throw new IOException("Stale Worldline ownership acknowledgement from " + serverId);
      }
    }
  }

  private static void writeUuid(final DataOutputStream output, final UUID value)
      throws IOException {
    output.writeLong(value.getMostSignificantBits());
    output.writeLong(value.getLeastSignificantBits());
  }

  private static UUID readUuid(final DataInputStream input) throws IOException {
    return new UUID(input.readLong(), input.readLong());
  }
}
