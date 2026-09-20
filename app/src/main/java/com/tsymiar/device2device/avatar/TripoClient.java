package com.tsymiar.device2device.avatar;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tripo3D 开放接口客户端：照片 → 3D 模型（image-to-model）。
 *
 * 流程（v3 OpenAPI，与官方文档一致）：
 *   1) POST /v3/files            multipart 上传照片 → 拿到 file_token
 *   2) POST /v3/generation/image-to-model  → 拿到 task_id
 *   3) GET  /v3/tasks/{task_id}  轮询 status（queued / running / success / failed）
 *   4) 下载 output.model_url（GLB）
 *
 * 只用 HttpURLConnection，不引第三方依赖；调用方请放在后台线程里跑。
 */
public final class TripoClient {

    private static final String TAG = "TripoClient";
    /** v3 接口基址（旧版 v2 为 api.tripo3d.ai/v2/openapi） */
    public static final String BASE = "https://openapi.tripo3d.com/v3";
    /** 生成模型版本：v3.1 画质最好；v2.5 更省额度 */
    public static final String MODEL_V31 = "v3.1-20260211";
    public static final String MODEL_V25 = "v2.5-20250123";

    private static final int CONNECT_MS = 15000;
    private static final int READ_MS = 60000;
    private static final int DOWNLOAD_READ_MS = 120000;
    private static final long POLL_MS = 3000L;
    private static final int POLL_MAX = 100;          // 最多等 5 分钟

    /** 生成参数 */
    public static final class Options {
        public String model = MODEL_V31;
        public int faceLimit = 20000;      // 顶点/面数上限：手机端 2 万左右足够
        public boolean texture = true;     // 是否生成贴图（关闭则只出几何，更快）
        public boolean pbr = false;
    }

    /** 生成结果 */
    public static final class Outcome {
        public File file;                  // 下载到本地的模型文件（GLB）
        public String modelUrl;
        public String renderedImageUrl;
        public String error;
    }

    /** 进度回调（在调用方线程里触发，切 UI 线程由调用方负责） */
    public interface Progress {
        void onProgress(String stage, int percent);
    }

    private TripoClient() {
    }

    /** 一步到位：上传 → 建任务 → 轮询 → 下载 */
    public static Outcome generate(String apiKey, byte[] jpeg, Options opt, File outFile,
                                   Progress progress) {
        Outcome out = new Outcome();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            out.error = "未填写 Tripo API Key";
            return out;
        }
        try {
            report(progress, "上传照片", 5);
            String token = uploadFile(apiKey, jpeg, "avatar.jpg", "image/jpeg");

            report(progress, "提交生成任务", 15);
            String taskId = createTask(apiKey, token, opt);

            String url = null;
            String preview = null;
            for (int i = 0; i < POLL_MAX; i++) {
                JSONObject task = queryTask(apiKey, taskId);
                JSONObject data = task.optJSONObject("data");
                if (data == null) {
                    out.error = "任务返回异常：" + task.toString();
                    return out;
                }
                String status = data.optString("status", "");
                int percent = data.optInt("progress", 0);
                if ("success".equals(status)) {
                    JSONObject output = data.optJSONObject("output");
                    if (output != null) {
                        url = output.optString("model_url", null);
                        preview = output.optString("rendered_image_url", null);
                    }
                    break;
                }
                if ("failed".equals(status) || "cancelled".equals(status)) {
                    out.error = "生成失败：" + data.optString("error_message",
                            "error_code=" + data.optInt("error_code", -1));
                    return out;
                }
                report(progress, "生成中 " + percent + "%", 20 + Math.min(60, percent * 60 / 100));
                Thread.sleep(POLL_MS);
            }
            if (url == null) {
                out.error = "任务超时，请稍后在 Tripo 后台查看结果";
                return out;
            }

            report(progress, "下载模型", 90);
            download(url, outFile);
            out.file = outFile;
            out.modelUrl = url;
            out.renderedImageUrl = preview;
            report(progress, "完成", 100);
            return out;
        } catch (Exception e) {
            Log.e(TAG, "tripo generate failed", e);
            out.error = "Tripo 调用失败：" + e.getMessage();
            return out;
        }
    }

    /** 1) 上传文件拿 file_token */
    public static String uploadFile(String apiKey, byte[] data, String fileName,
                                    String mime) throws Exception {
        String boundary = "----TripoBoundary" + System.currentTimeMillis();
        HttpURLConnection conn = open(BASE + "/files", apiKey);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setFixedLengthStreamingMode(bodyLength(boundary, fileName, mime, data.length));
        OutputStream os = conn.getOutputStream();
        os.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n").getBytes("UTF-8"));
        os.write(data);
        os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
        os.flush();
        os.close();

        String body = read(conn);
        JSONObject json = new JSONObject(body);
        check(json);
        String token = json.optJSONObject("data").optString("file_token", null);
        if (token == null) throw new Exception("上传未返回 file_token：" + body);
        return token;
    }

    /** 2) 建任务拿 task_id */
    public static String createTask(String apiKey, String fileToken, Options opt) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"input\":\"").append(fileToken).append('"');
        sb.append(",\"model\":\"").append(opt == null ? MODEL_V31 : opt.model).append('"');
        if (opt != null) {
            sb.append(",\"texture\":").append(opt.texture);
            sb.append(",\"pbr\":").append(opt.pbr && opt.texture);
            if (opt.faceLimit > 0) sb.append(",\"face_limit\":").append(opt.faceLimit);
        }
        sb.append('}');

        HttpURLConnection conn = open(BASE + "/generation/image-to-model", apiKey);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        OutputStream os = conn.getOutputStream();
        os.write(sb.toString().getBytes("UTF-8"));
        os.flush();
        os.close();

        String body = read(conn);
        JSONObject json = new JSONObject(body);
        check(json);
        String taskId = json.optJSONObject("data").optString("task_id", null);
        if (taskId == null) throw new Exception("未返回 task_id：" + body);
        return taskId;
    }

    /** 3) 查任务状态 */
    public static JSONObject queryTask(String apiKey, String taskId) throws Exception {
        HttpURLConnection conn = open(BASE + "/tasks/" + taskId, apiKey);
        conn.setRequestMethod("GET");
        return new JSONObject(read(conn));
    }

    /** 4) 下载模型文件 */
    public static void download(String url, File out) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(DOWNLOAD_READ_MS);
        conn.setInstanceFollowRedirects(true);
        InputStream in = conn.getInputStream();
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "mkdirs failed " + parent);
        }
        FileOutputStream fos = new FileOutputStream(out);
        try {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } finally {
            fos.close();
            in.close();
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // HTTP 工具
    // ------------------------------------------------------------------

    private static HttpURLConnection open(String url, String apiKey) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        return conn;
    }

    private static void check(JSONObject json) throws Exception {
        int code = json.optInt("code", -1);
        if (code != 0) {
            String msg = json.optString("message", json.optString("msg", "code=" + code));
            throw new Exception(msg);
        }
    }

    private static String read(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) throw new Exception("HTTP " + code + "（无响应体）");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        String body = new String(bos.toByteArray(), "UTF-8");
        if (code >= 400) {
            String msg = body;
            try {
                msg = new JSONObject(body).optString("message", body);
            } catch (Exception ignore) {
                // 非 JSON 错误体直接用原文
            }
            conn.disconnect();
            throw new Exception("HTTP " + code + " " + msg);
        }
        conn.disconnect();
        return body;
    }

    private static int bodyLength(String boundary, String fileName, String mime, int dataLen)
            throws Exception {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        return head.getBytes("UTF-8").length + dataLen + tail.getBytes("UTF-8").length;
    }

    private static void report(Progress progress, String stage, int percent) {
        if (progress != null) progress.onProgress(stage, percent);
    }
}
