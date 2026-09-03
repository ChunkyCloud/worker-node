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

import java.io.File;
import java.util.Optional;

/**
 * Settings for a {@link WorkerApplication}.
 */
public class WorkerSettings {
    private String apiUrl;
    private File cacheDirectory;
    private Long maxCacheSize;
    private final String apiKey;

    public WorkerSettings(String apiUrl, File cacheDirectory, Long maxCacheSize, String apiKey) {
        this.apiUrl = apiUrl;
        this.cacheDirectory = cacheDirectory;
        this.maxCacheSize = maxCacheSize;
        this.apiKey = apiKey;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public Optional<File> getCacheDirectory() {
        return Optional.ofNullable(cacheDirectory);
    }

    public Optional<Long> getMaxCacheSize() {
        return Optional.ofNullable(maxCacheSize);
    }

    public String getApiKey() {
        return apiKey;
    }
}
