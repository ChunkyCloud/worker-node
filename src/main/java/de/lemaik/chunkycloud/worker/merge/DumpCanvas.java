/*
 * Copyright (C) 2026 leMaik and contributors
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.lemaik.chunkycloud.worker.merge;

import se.llbit.chunky.renderer.renderdump.DumpMetadata;
import se.llbit.chunky.renderer.renderdump.RenderDump;

public class DumpCanvas {
    private final double[] buffer;
    private final int width;
    private final int height;
    private final int totalSpp;

    public DumpCanvas(int width, int height, int totalSpp) {
        this.width = width;
        this.height = height;
        this.totalSpp = totalSpp;
        this.buffer = new double[width * height * 3];
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void pasteDumpAt(int x, int y, RenderDump dump) {
        int dumpWidth = dump.getMetadata().width();
        int dumpHeight = dump.getMetadata().height();
        double[] dumpBuffer = dump.getSampleBuffer();

        if (dump.getMetadata().spp() == totalSpp) {
            for (int row = 0; row < dumpHeight; row++) {
                int srcOffset = row * dumpWidth * 3;
                int dstOffset = ((y + row) * width + x) * 3;

                System.arraycopy(dumpBuffer, srcOffset, buffer, dstOffset, dumpWidth * 3);
            }
        } else {
            // Dump buffers contain sample averages, so weight each tile by its SPP.
            double weight = (double) dump.getMetadata().spp() / totalSpp;
            for (int row = 0; row < dumpHeight; row++) {
                int srcOffset = row * dumpWidth * 3;
                int dstOffset = ((y + row) * width + x) * 3;

                for (int i = 0; i < dumpWidth * 3; i++) {
                    buffer[dstOffset + i] += dumpBuffer[srcOffset + i] * weight;
                }
            }
        }
    }

    public RenderDump asDump(long renderTime) {
        return new RenderDump(new DumpMetadata(getWidth(), getHeight(), totalSpp, renderTime), buffer);
    }
}
