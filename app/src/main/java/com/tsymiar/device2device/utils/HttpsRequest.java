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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

public class HttpsRequest {

    public interface HttpsRequestCallback {
        void onSuccess(int responseCode, String response, Map<String, String> headers);
        void onFailure(Exception e);
    }

    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void executeRequest(
            String urlString,
            String method,
            HashMap<String, String> headers,
            String requestBody,
            HttpsRequestCallback callback) {

        executor.execute(() -> {
            HttpsURLConnection connection = null;
            try {
                // 创建连接
                URL url = new URL(urlString);
                connection = (HttpsURLConnection) url.openConnection();

                // 设置基本参数
                connection.setRequestMethod(method);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);

                // 添加请求头
                if (headers != null) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        connection.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }

                // 处理请求体
                if (requestBody != null && (method.equals("POST") || method.equals("PUT"))) {
                    connection.setDoOutput(true);
                    try (OutputStream os = connection.getOutputStream()) {
                        byte[] input = requestBody.getBytes("utf-8");
                        os.write(input, 0, input.length);
                    }
                }

                // 获取响应
                int responseCode = connection.getResponseCode();

                // 读取响应头
                Map<String, String> responseHeaders = new HashMap<>();
                for (Map.Entry<String, java.util.List<String>> header : connection.getHeaderFields().entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        responseHeaders.put(header.getKey(), header.getValue().get(0));
                    }
                }

                // 读取响应内容
                InputStream inputStream = (responseCode >= 400) ?
                        connection.getErrorStream() : connection.getInputStream();
                String response = convertStreamToString(inputStream);

                // 成功回调
                mainHandler.post(() ->
                        callback.onSuccess(responseCode, response, responseHeaders));

            } catch (Exception e) {
                // 失败回调
                mainHandler.post(() -> callback.onFailure(e));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private static String convertStreamToString(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        }
    }
}