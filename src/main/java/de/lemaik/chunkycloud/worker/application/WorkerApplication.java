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

package de.lemaik.chunkycloud.worker.application;

import de.lemaik.chunkycloud.worker.Main;
import de.lemaik.chunkycloud.worker.api.WorkerApiClient;
import de.lemaik.chunkycloud.worker.merge.WorkerThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.llbit.chunky.JsonSettings;
import se.llbit.chunky.PersistentSettings;
import se.llbit.chunky.main.Version;

import java.nio.file.Path;
import java.nio.file.Paths;

public class WorkerApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerApplication.class);

    private final WorkerApiClient api;

    private WorkerThread worker;

    public WorkerApplication(WorkerSettings settings) {
        api = new WorkerApiClient(
                settings.getApiUrl(), settings.getApiKey(),
                settings.getCacheDirectory().orElse(Paths.get(System.getProperty("user.dir"), "cc_cache").toFile()),
                settings.getMaxCacheSize().orElse(512L)
        );
    }

    public void start() {
        LOGGER.info("Worker node version: " + Main.VERSION + " (version code " + Main.VERSION_CODE + ")");
        LOGGER.info("Chunky version: " + Version.getVersion());

        Path chunkyHome = Paths.get(System.getProperty("user.dir"), "cc_chunky");
        chunkyHome.toFile().mkdirs();

        PersistentSettings.changeSettingsDirectory(chunkyHome.toFile());
        PersistentSettings.settings = new JsonSettings();
        PersistentSettings.setDisableDefaultTextures(true);
        PersistentSettings.save();

        LOGGER.info("Chunky home: " + chunkyHome);

        worker = new WorkerThread(api);
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            LOGGER.warn("Worker interrupted", e);
        }
        System.exit(0);
    }

    public void stop() {
        try {
            LOGGER.info("Waiting for worker to stop...");
            worker.interrupt();
            worker.join();
            LOGGER.info("Worker stopped");
        } catch (InterruptedException e) {
            LOGGER.error("Could not gracefully stop the worker");
        }
    }
}
