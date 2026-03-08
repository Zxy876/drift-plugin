package com.driftmc.backend;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class BackendClient {

    private static final Logger LOGGER = Logger.getLogger(BackendClient.class.getName());
    private static final int LOG_PREVIEW_LIMIT = 800;

    private final String baseUrl;
    private final OkHttpClient client;

    public BackendClient(String baseUrl) {
        this(
            baseUrl,
            Duration.ofSeconds(150),
            Duration.ofSeconds(10),
            Duration.ofSeconds(120),
            Duration.ofSeconds(120));
        }

        public BackendClient(
            String baseUrl,
            Duration callTimeout,
            Duration connectTimeout,
            Duration readTimeout,
            Duration writeTimeout) {

        // ---- Base URL 修正 ----
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        this.baseUrl = baseUrl;

        // ---- 最终稳定配置（适配 DriftSystem） ----
        this.client = new OkHttpClient.Builder()
            .callTimeout(callTimeout) // 整体最大时间
            .connectTimeout(connectTimeout) // 连接服务器超时
            .readTimeout(readTimeout) // 读取 JSON 超时
            .writeTimeout(writeTimeout) // 发送 JSON 超时
                .retryOnConnectionFailure(true) // 避免偶发超时
                .followRedirects(true)
                .build();
    }

    private String buildUrl(String path) {
        if (!path.startsWith("/"))
            path = "/" + path;
        return baseUrl + path;
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\n", " ").replace("\r", " ");
        if (normalized.length() <= LOG_PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, LOG_PREVIEW_LIMIT) + "...";
    }

    private static boolean isIntentRequestPath(String path) {
        return path != null && path.startsWith("/ai/intent");
    }

    private static boolean isWorldApplyPath(String path) {
        return path != null && path.startsWith("/world/apply");
    }

    // ------------------------------------------------------
    // 同步 postJson（调试用）
    // ------------------------------------------------------
    public String postJson(String path, String json) throws IOException {
        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("BackendClient.postJson cannot run on the primary server thread");
        }
        RequestBody body = RequestBody.create(
            MediaType.parse("application/json; charset=utf-8"),
            json);

        Request request = new Request.Builder()
                .url(buildUrl(path))
                .post(body)
                .build();

        if (isIntentRequestPath(path)) {
            LOGGER.log(Level.INFO, "[BackendClient][intent request] path={0} payload={1}", new Object[] { path, preview(json) });
        }

        try (Response resp = client.newCall(request).execute()) {
            ResponseBody rb = resp.body();
            String responseText = rb != null ? rb.string() : "";

            if (isWorldApplyPath(path)) {
                LOGGER.log(Level.INFO, "[BackendClient][world patch response] code={0} body={1}", new Object[] { resp.code(), preview(responseText) });
            }

            if (!resp.isSuccessful()) {
                LOGGER.log(Level.WARNING, "[BackendClient][http error] path={0} code={1} body={2}", new Object[] { path, resp.code(), preview(responseText) });
                throw new IOException("POST " + path + " failed: " + resp.code() + ", body=" + preview(responseText));
            }

            return responseText;
        }
    }

    // ------------------------------------------------------
    // 异步 postJson（用于 IntentRouter2）
    // ------------------------------------------------------
    public void postJsonAsync(String path, String json, Callback callback) {

        if (isIntentRequestPath(path)) {
            LOGGER.log(Level.INFO, "[BackendClient][intent request] path={0} payload={1}", new Object[] { path, preview(json) });
        }

        RequestBody body = RequestBody.create(
            MediaType.parse("application/json; charset=utf-8"),
            json);

        Request request = new Request.Builder()
                .url(buildUrl(path))
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                LOGGER.log(Level.WARNING, "[BackendClient][http error] path={0} error={1}", new Object[] { path, e.getMessage() });
                if (callback != null) {
                    callback.onFailure(call, e);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (isWorldApplyPath(path)) {
                    String peek = "";
                    try {
                        peek = response.peekBody(8192).string();
                    } catch (Exception ignored) {
                    }
                    LOGGER.log(Level.INFO, "[BackendClient][world patch response] code={0} body={1}", new Object[] { response.code(), preview(peek) });
                }

                if (!response.isSuccessful()) {
                    String peek = "";
                    try {
                        peek = response.peekBody(8192).string();
                    } catch (Exception ignored) {
                    }
                    LOGGER.log(Level.WARNING, "[BackendClient][http error] path={0} code={1} body={2}", new Object[] { path, response.code(), preview(peek) });
                }

                if (callback != null) {
                    callback.onResponse(call, response);
                } else {
                    response.close();
                }
            }
        });
    }

    // ------------------------------------------------------
    // 异步 GET 请求（用于获取小地图等资源）
    // ------------------------------------------------------
    public void getAsync(String path, Callback callback) {
        Request request = new Request.Builder()
                .url(buildUrl(path))
                .get()
                .build();

        client.newCall(request).enqueue(callback);
    }

    public void getAsync(String path, Map<String, String> headers, Callback callback) {
        Request.Builder builder = new Request.Builder()
                .url(buildUrl(path))
                .get();

        if (headers != null) {
            headers.forEach((key, value) -> {
                if (key != null && value != null) {
                    builder.addHeader(key, value);
                }
            });
        }

        Request request = builder.build();

        client.newCall(request).enqueue(callback);
    }
}