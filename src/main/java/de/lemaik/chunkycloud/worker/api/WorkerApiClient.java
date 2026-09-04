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
import okio.BufferedSink;
import se.llbit.chunky.main.Version;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
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

    public CompletableFuture<MergeTask> getNextTask() {
        CompletableFuture<MergeTask> result = new CompletableFuture<>();
        client.newCall(new Request.Builder()
                        .url(baseUrl + "/worker-nodes/me/tasks/next").get()
                        .build())
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        result.completeExceptionally(e);
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        try (response) {
                            if (response.code() == 200) {
                                try (InputStreamReader reader = new InputStreamReader(response.body().byteStream())) {
                                    result.complete(gson.fromJson(reader, MergeTask.class));
                                } catch (IOException e) {
                                    result.completeExceptionally(e);
                                }
                            } else if (response.code() == 204) {
                                result.complete(null);
                            } else {
                                result.completeExceptionally(new IOException("The merge task could not be downloaded " + response.code()));
                            }
                        }
                    }
                });

        return result;
    }

    public CompletableFuture<FinishMergeTaskResponse> getMergeTaskUploadUrls(int jobId) {
        CompletableFuture<FinishMergeTaskResponse> result = new CompletableFuture<>();
        client.newCall(new Request.Builder()
                        .url(baseUrl + "/worker-nodes/me/tasks/merge/" + jobId + "/upload").post(RequestBody.create(new byte[0]))
                        .build())
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        result.completeExceptionally(e);
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        try (response) {
                            if (response.isSuccessful()) {
                                try (InputStreamReader reader = new InputStreamReader(response.body().byteStream())) {
                                    result.complete(gson.fromJson(reader, FinishMergeTaskResponse.class));
                                } catch (IOException e) {
                                    result.completeExceptionally(e);
                                }
                            } else {
                                result.completeExceptionally(new IOException("The merge task could not be finished"));
                            }
                        }
                    }
                });
        return result;
    }

    public CompletableFuture<Void> finishMergeTask(int jobId) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        client.newCall(new Request.Builder()
                        .url(baseUrl + "/worker-nodes/me/tasks/merge/" + jobId + "/finish").post(RequestBody.create(new byte[0]))
                        .build())
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        result.completeExceptionally(e);
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        try (response) {
                            if (response.isSuccessful()) {
                                result.complete(null);
                            } else {
                                result.completeExceptionally(new IOException("The task could not be finished, status " + response.code() + " " + response.body().string()));
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
        return result;
    }

    public CompletableFuture<Void> uploadFile(String url, Buffer body, String mimeType) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        uploadClient.newCall(new Request.Builder()
                        .url(url)
                        .put(new RequestBody() {
                            @Override
                            public MediaType contentType() {
                                return MediaType.parse(mimeType);
                            }

                            @Override
                            public long contentLength() throws IOException {
                                return body.size();
                            }

                            @Override
                            public void writeTo(BufferedSink sink) throws IOException {
                                sink.write(body, body.size());
                            }
                        })
                        .build()
                )
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        result.completeExceptionally(e);
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        try (response) {
                            if (response.isSuccessful()) {
                                result.complete(null);
                            } else {
                                result.completeExceptionally(new IOException(
                                        "Upload failed" + response.code() + " " + response.body().string()));
                            }
                        }
                    }
                });
        return result;
    }
}
