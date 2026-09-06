/*
 * Copyright (C) 2016-2026 leMaik and contributors
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

import de.lemaik.chunkycloud.worker.api.FinishMergeTaskResponse;
import de.lemaik.chunkycloud.worker.api.MergeTask;
import de.lemaik.chunkycloud.worker.api.Tile;
import de.lemaik.chunkycloud.worker.api.WorkerApiClient;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.llbit.chunky.renderer.renderdump.FloatingPointCompressorDumpFormat;
import se.llbit.chunky.renderer.renderdump.RenderDump;
import se.llbit.util.TaskTracker;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * A worker node worker thread.
 */
public class WorkerThread extends Thread {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerThread.class);
    private final int MAX_RESTART_DELAY_SECONDS = 15 * 60; // 15 minutes
    private final WorkerApiClient apiClient;
    private int nextRestartDelaySeconds = 1;

    public WorkerThread(WorkerApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public void run() {
        while (!interrupted()) {
            LOGGER.info("Polling for new task");
            try {
                MergeTask task = apiClient.getNextTask();
                if (task == null) {
                    Thread.sleep(5000L);
                    continue;
                }

                LOGGER.info("Got merge task for job {}", task.getJob().getId());
                long startTime = System.currentTimeMillis();

                // Merge tile images
                BufferedImage resultImage = new BufferedImage(task.getJob().getWidth(), task.getJob().getHeight(), BufferedImage.TYPE_INT_ARGB);
                for (Tile tile : task.getTiles()) {
                    BufferedImage tileImage = ImageIO.read(new URL(tile.getImage().getUrl()));
                    Graphics2D graphics = resultImage.createGraphics();
                    try {
                        graphics.drawImage(tileImage, tile.getX(), tile.getY(), null);
                    } finally {
                        graphics.dispose();
                    }
                }

                // Create thumbnail
                double scale = Math.min(1.0, Math.min(512. / resultImage.getWidth(), 512. / resultImage.getHeight()));
                int width = (int) Math.round(resultImage.getWidth() * scale);
                int height = (int) Math.round(resultImage.getHeight() * scale);
                BufferedImage thumbnailImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = thumbnailImage.createGraphics();
                try {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(resultImage, 0, 0, width, height, null);
                } finally {
                    g.dispose();
                }

                // Merge tile dumps, if applicable
                RenderDump resultDump = null;
                if (task.getTiles().get(0).getDump().isPresent()) {
                    DumpCanvas result = new DumpCanvas(task.getJob().getWidth(), task.getJob().getHeight());
                    int spp = 0;
                    long renderTime = 0;
                    for (Tile tile : task.getTiles()) {
                        if (tile.getDump().isPresent()) {
                            try (InputStream inputStream = new URL(tile.getDump().get().getUrl()).openStream()) {
                                RenderDump dump = RenderDump.load(inputStream, TaskTracker.NONE);
                                if (spp == 0) {
                                    spp = dump.getMetadata().spp();
                                }
                                renderTime = Math.max(renderTime, dump.getMetadata().renderTime());
                                result.pasteDumpAt(tile.getX(), tile.getY(), dump);
                            }
                        }
                    }
                    resultDump = result.asDump(spp, renderTime);
                }

                FinishMergeTaskResponse finishMergeResponse = apiClient.getMergeTaskUploadUrls(task.getJob().getId());

                // Upload image
                try (Buffer buffer = new Buffer()) {
                    ImageIO.write(resultImage, "png", buffer.outputStream());
                    apiClient.uploadFile(finishMergeResponse.getUploadUrls().getImage(), buffer, "image/png");
                }

                // Upload image
                try (Buffer buffer = new Buffer()) {
                    ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
                    try (ImageOutputStream ios = ImageIO.createImageOutputStream(buffer.outputStream())) {
                        writer.setOutput(ios);
                        ImageWriteParam param = writer.getDefaultWriteParam();
                        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        param.setCompressionQuality(0.8f);
                        writer.write(null, new IIOImage(thumbnailImage, null, null), param);
                    }
                    apiClient.uploadFile(finishMergeResponse.getUploadUrls().getThumbnailImage(), buffer, "image/jpeg");
                }

                // Upload dump
                if (resultDump != null) {
                    try (Buffer buffer = new Buffer()) {
                        RenderDump.save(buffer.outputStream(), resultDump, TaskTracker.NONE, FloatingPointCompressorDumpFormat.INSTANCE);
                        apiClient.uploadFile(finishMergeResponse.getUploadUrls().getDump().orElseThrow(), buffer, "application/octet-stream");
                    }
                }

                apiClient.finishMergeTask(task.getJob().getId());

                long endTime = System.currentTimeMillis();
                LOGGER.info("Merge done for job {} (took {} ms)", task.getJob().getId(), endTime - startTime);
                nextRestartDelaySeconds = 1;
            } catch (InterruptedException e) {
                LOGGER.info("Interrupted", e);
                break;
            } catch (IOException e) {
                LOGGER.error("Error", e);
                try {
                    int delaySeconds = nextRestartDelaySeconds;
                    LOGGER.info("Waiting {} seconds before trying again", delaySeconds);
                    Thread.sleep(delaySeconds * 1000L);
                } catch (InterruptedException ex) {
                    LOGGER.info("Interrupted", e);
                    break;
                }
                nextRestartDelaySeconds = Math.min(nextRestartDelaySeconds * 2, MAX_RESTART_DELAY_SECONDS);
            }

            if (interrupted()) {
                break;
            }
        }
    }
}
