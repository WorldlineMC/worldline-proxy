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

/** Resources the destination must prepare before answering ready. */
public record PrepareTarget(String playerName, String levelName, String dimension,
                            String compatibilityId, double x, double y, double z,
                            int visibilityRadiusChunks) {

  /** Validates the slice control payload before it reaches the network. */
  public PrepareTarget {
    requireText(playerName, "playerName", 16);
    requireText(levelName, "levelName", 256);
    requireText(dimension, "dimension", 256);
    requireText(compatibilityId, "compatibilityId", 256);
    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
      throw new IllegalArgumentException("Prepare coordinates must be finite");
    }
    if (visibilityRadiusChunks < 1 || visibilityRadiusChunks > 8) {
      throw new IllegalArgumentException("visibilityRadiusChunks must be between 1 and 8");
    }
  }

  private static void requireText(final String value, final String name, final int maxLength) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException("Invalid " + name);
    }
  }
}
