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

package de.lemaik.chunkycloud.worker.api;

import com.google.gson.Gson;
import de.lemaik.chunkycloud.worker.Main;
import okhttp3.*;
import okio.Buffer;
import se.llbit.chunky.main.Version;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public class WorkerApiClient {
    private static final Gson gson = new Gson();
    private final String baseUrl;
    private final OkHttpClient client;
    private final OkHttpClient uploadClient;

    public WorkerApiClient(String baseUrl, String apiKey, File cacheDirectory,
                           long maxCacheSize) {
        this.baseUrl = baseUrl;
        client = new OkHttpClient.Builder()
                .followRedirects(true)
                .cache(new Cache(cacheDirectory, maxCacheSize))
                .addInterceptor(chain -> {
                    if (chain.request().url().toString().startsWith(baseUrl)) {
                        return chain.proceed(
                                chain.request().newBuilder()
                                        .header("User-Agent",
                                                "ChunkyCloudWorkerNode/" + Main.VERSION + " (VC " + Main.VERSION_CODE + ") Chunky/" + Version.getVersion() + " (" + Version.getCommit() + ")")
                                        .header("Authorization", "Bearer " + apiKey)
                                        .build()
                        );
                    }
                    return chain.proceed(chain.request());
                })
                .connectTimeout(
                        Integer.parseInt(System.getProperty("chunkycloud.http.connectTimeout", "10")),
                        TimeUnit.SECONDS)
                .writeTimeout(Integer.parseInt(System.getProperty("chunkycloud.http.writeTimeout", "10")),
                        TimeUnit.SECONDS)
                .readTimeout(Integer.parseInt(System.getProperty("chunkycloud.http.readTimeout", "35")), // TODO
                        TimeUnit.SECONDS)
                .build();
        uploadClient = client.newBuilder()
                .connectTimeout(
                        Integer.parseInt(System.getProperty("chunkycloud.http.uploadConnectTimeout", "10")),
                        TimeUnit.SECONDS)
                .writeTimeout(
                        Integer.parseInt(System.getProperty("chunkycloud.http.uploadWriteTimeout", "1800")),
                        TimeUnit.SECONDS)
                .readTimeout(
                        Integer.parseInt(System.getProperty("chunkycloud.http.uploadReadTimeout", "10")),
                        TimeUnit.SECONDS)
                .build();
    }

    public MergeTask getNextTask() throws IOException {
        try (Response response = client.newCall(new Request.Builder()
                .url(baseUrl + "/worker-nodes/me/tasks/next").get()
                .build()).execute()) {
            if (response.code() == 200) {
                try (InputStreamReader reader = new InputStreamReader(response.body().byteStream())) {
                    return gson.fromJson(reader, MergeTask.class);
                }
            } else if (response.code() == 204) {
                return null;
            } else {
                throw new IOException("The merge task could not be downloaded " + response.code());
            }
        }
    }

    public FinishMergeTaskResponse getMergeTaskUploadUrls(int jobId) throws IOException {
        try (Response response = client.newCall(new Request.Builder()
                .url(baseUrl + "/worker-nodes/me/tasks/merge/" + jobId + "/upload").post(RequestBody.EMPTY)
                .build()).execute()) {
            if (response.isSuccessful()) {
                try (InputStreamReader reader = new InputStreamReader(response.body().byteStream())) {
                    return gson.fromJson(reader, FinishMergeTaskResponse.class);
                }
            } else {
                throw new IOException("The merge task could not be finished");
            }
        }
    }

    public ResponseBody downloadFile(String url) throws IOException {
        Response response = client.newCall(new Request.Builder()
                .url(url)
                .get()
                .build()).execute();
        if (!response.isSuccessful()) {
            try (response) {
                throw new IOException("Download failed" + response.code() + " " + response.body().string());
            }
        }

        ResponseBody body = response.body();
        if (body == null) {
            response.close();
            throw new IOException("Download failed: response body is empty");
        }
        return body;
    }

    public void finishMergeTask(int jobId) throws IOException {
        try (Response response = client.newCall(new Request.Builder()
                .url(baseUrl + "/worker-nodes/me/tasks/merge/" + jobId + "/finish").post(RequestBody.EMPTY)
                .build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("The task could not be finished, status " + response.code() + " " + response.body().string());
            }
        }
    }

    public void uploadFile(String url, Buffer body, String mimeType) throws IOException {
        try (Response response = uploadClient.newCall(new Request.Builder()
                .url(url)
                .put(RequestBody.create(body.snapshot(), MediaType.parse(mimeType)))
                .build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Upload failed" + response.code() + " " + response.body().string());
            }
        }
    }
}
