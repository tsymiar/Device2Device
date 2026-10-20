package com.tsymiar.device2device.utils;

import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HttpsRequest {

    public interface HttpsRequestCallback {
        void onSuccess(int responseCode, String response, Map<String, String> headers);
        void onFailure(Exception e);
    }

    /**
     * 默认超时。行情 / 翻译这类接口几秒内就该回来，15 秒足够。
     *
     * 本地跑的大模型不适用：见下面带超时参数的重载。
     */
    public static final int DEFAULT_CONNECT_MS = 10000;
    public static final int DEFAULT_READ_MS = 15000;

    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void executeRequest(
            String urlString,
            String method,
            HashMap<String, String> headers,
            String requestBody,
            HttpsRequestCallback callback) {
        executeRequest(urlString, method, headers, requestBody, "utf-8",
                DEFAULT_CONNECT_MS, DEFAULT_READ_MS, callback);
    }

    /**
     * @param charset 响应正文的编码。绝大多数接口是 utf-8；腾讯 / 新浪那几条老接口给的是 GBK
     *                —— 不按 GBK 解，中文字段（名称那类）就是一堆乱码，
     *                数字字段虽不受影响，但靠它拿名称的那条路就废了。
     */
    public static void executeRequest(
            String urlString,
            String method,
            HashMap<String, String> headers,
            String requestBody,
            String charset,
            HttpsRequestCallback callback) {
        executeRequest(urlString, method, headers, requestBody, charset,
                DEFAULT_CONNECT_MS, DEFAULT_READ_MS, callback);
    }

    /**
     * 带超时参数的版本。
     *
     * @param connectMs >0 时生效，否则用 {@link #DEFAULT_CONNECT_MS}
     * @param readMs    >0 时生效，否则用 {@link #DEFAULT_READ_MS}。
     *                  读超时管的是"等多久还没等到下一个字节"，本地大模型一次生成
     *                  几十秒到几分钟都正常，默认的 15 秒会把还在跑的请求掐掉，
     *                  所以慢接口由调用方自己定，不统一加长。
     */
    public static void executeRequest(
            String urlString,
            String method,
            HashMap<String, String> headers,
            String requestBody,
            String charset,
            int connectMs,
            int readMs,
            HttpsRequestCallback callback) {

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                // 创建连接。基类 HttpURLConnection：url 是 https 时 openConnection 返回的
                // 就是 HttpsURLConnection，顺带也就支持了 http（本地自建的 LLM 服务走 http）
                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();

                connection.setRequestMethod(method);
                connection.setConnectTimeout(connectMs > 0 ? connectMs : DEFAULT_CONNECT_MS);
                connection.setReadTimeout(readMs > 0 ? readMs : DEFAULT_READ_MS);

                if (headers != null) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        connection.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }

                if (requestBody != null && (method.equals("POST") || method.equals("PUT"))) {
                    connection.setDoOutput(true);
                    try (OutputStream os = connection.getOutputStream()) {
                        byte[] input = requestBody.getBytes("utf-8");
                        os.write(input, 0, input.length);
                    }
                }

                int responseCode = connection.getResponseCode();

                Map<String, String> responseHeaders = new HashMap<>();
                for (Map.Entry<String, java.util.List<String>> header : connection.getHeaderFields().entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        responseHeaders.put(header.getKey(), header.getValue().get(0));
                    }
                }

                InputStream inputStream = (responseCode >= 400) ?
                        connection.getErrorStream() : connection.getInputStream();
                String response = convertStreamToString(inputStream, charset);

                mainHandler.post(() ->
                        callback.onSuccess(responseCode, response, responseHeaders));

            } catch (Exception e) {
                mainHandler.post(() -> callback.onFailure(e));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private static String convertStreamToString(InputStream is, String charset) throws IOException {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, charset))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        }
    }
}