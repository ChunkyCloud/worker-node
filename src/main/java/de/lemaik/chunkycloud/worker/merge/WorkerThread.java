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
import de.lemaik.chunkycloud.worker.util.ImageUtil;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.llbit.chunky.renderer.postprocessing.PostProcessingFilter;
import se.llbit.chunky.renderer.postprocessing.PostProcessingFilters;
import se.llbit.chunky.renderer.renderdump.FloatingPointCompressorDumpFormat;
import se.llbit.chunky.renderer.renderdump.RenderDump;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.chunky.resources.BitmapImage;
import se.llbit.json.JsonObject;
import se.llbit.json.JsonParser;
import se.llbit.util.Configurable;
import se.llbit.util.TaskTracker;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

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

                boolean hasOverlappingTiles = false;

                // Merge tile dumps, if applicable
                RenderDump resultDump = null;
                if (task.getTiles().get(0).getDump().isPresent()) {
                    DumpCanvas result = new DumpCanvas(task.getJob().getWidth(), task.getJob().getHeight(), task.getJob().getSpp());
                    for (Tile tile : task.getTiles()) {
                        if (tile.getDump().isPresent()) {
                            try (ResponseBody body = apiClient.downloadFile(tile.getDump().get().getUrl())) {
                                RenderDump dump = RenderDump.load(body.byteStream(), TaskTracker.NONE);
                                result.pasteDumpAt(tile.getX(), tile.getY(), dump);

                                if (dump.getMetadata().spp() < task.getJob().getSpp()) {
                                    hasOverlappingTiles = true;
                                }
                            }
                        }
                    }
                    resultDump = result.asDump(0);
                }

                // Merge tile images
                BufferedImage resultImage;
                if (hasOverlappingTiles) {
                    // create image from the dump (required if we have overlapping tiles, fewer downloads if we already have the dump)
                    JsonObject scene;
                    try (ResponseBody body = apiClient.downloadFile(task.getScene().getUrl());
                         JsonParser parser = new JsonParser(body.byteStream())) {
                        scene = parser.parse().asObject();
                    } catch (JsonParser.SyntaxError e) {
                        throw new IOException("Could not parse scene for postprocessing", e);
                    }
                    PostProcessingFilter postprocess = PostProcessingFilters.getPostProcessingFilterFromId(
                                    scene.get("postprocess").stringValue(Scene.DEFAULT_POSTPROCESSING_FILTER.getId()))
                            .orElse(Scene.DEFAULT_POSTPROCESSING_FILTER);
                    if (postprocess instanceof Configurable configurable) {
                        configurable.loadConfiguration(scene.get("postprocessSettings").asObject());
                    }
                    double exposure = scene.get("exposure").doubleValue(1.0);
                    BitmapImage output = new BitmapImage(task.getJob().getWidth(), task.getJob().getHeight());
                    postprocess.processFrame(task.getJob().getWidth(), task.getJob().getHeight(), resultDump.getSampleBuffer(), output, exposure, TaskTracker.Task.NONE);
                    resultImage = ImageUtil.bitmapImageToBufferedImage(output);

                    if (scene.get("transparentSky").boolValue(false)) {
                        // if the scene has transparent sky, use the transparency from the tile images
                        Map<Rectangle, Tile> tiles = new HashMap<>();
                        for (Tile tile : task.getTiles()) {
                            tiles.put(new Rectangle(tile.getX(), tile.getY(), tile.getWidth(), tile.getHeight()), tile);
                        }
                        for (Tile tile : tiles.values()) {
                            BufferedImage tileImage;
                            try (ResponseBody body = apiClient.downloadFile(tile.getImage().getUrl())) {
                                tileImage = ImageIO.read(body.byteStream());
                                if (tileImage == null) {
                                    throw new IOException("Could not decode tile image at (" + tile.getX() + ", " + tile.getY() + ")");
                                }
                                tileImage = ImageUtil.toIntArgb(tileImage);
                            }

                            // apply the tile's alpha channel to the result image
                            // this works because both images are of type TYPE_INT_ARGB
                            int[] dst = ((DataBufferInt) resultImage.getRaster()
                                    .getDataBuffer()).getData();
                            int dstWidth = resultImage.getWidth();

                            int[] src = ((DataBufferInt) tileImage.getRaster()
                                    .getDataBuffer()).getData();
                            int srcWidth = tileImage.getWidth();

                            for (int y = 0; y < tileImage.getHeight(); y++) {
                                int srcOffset = y * srcWidth;
                                int dstOffset = (tile.getY() + y) * dstWidth + tile.getX();

                                for (int x = 0; x < srcWidth; x++) {
                                    int i = dstOffset + x;
                                    dst[i] = (dst[i] & 0x00FFFFFF)
                                            | (src[srcOffset + x] & 0xFF000000);
                                }
                            }
                        }
                    }
                } else {
                    resultImage = new BufferedImage(task.getJob().getWidth(), task.getJob().getHeight(), BufferedImage.TYPE_INT_ARGB);
                    for (Tile tile : task.getTiles()) {
                        BufferedImage tileImage;
                        try (ResponseBody body = apiClient.downloadFile(tile.getImage().getUrl())) {
                            tileImage = ImageIO.read(body.byteStream());
                            if (tileImage == null) {
                                throw new IOException("Could not decode tile image at (" + tile.getX() + ", " + tile.getY() + ")");
                            }
                        }
                        Graphics2D graphics = resultImage.createGraphics();
                        graphics.setComposite(AlphaComposite.Src);
                        try {
                            graphics.drawImage(tileImage, tile.getX(), tile.getY(), null);
                        } finally {
                            graphics.dispose();
                        }
                    }
                }

                // Create thumbnail
                BufferedImage thumbnailImage = ImageUtil.createThumbnail(resultImage, 512, 512);

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
