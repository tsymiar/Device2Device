package com.tsymiar.device2device.service;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 轻量级 HTTP 文件浏览服务器
 * 支持目录列表和文件下载，可直接在浏览器中访问
 * 支持两种模式：
 * - filesystem 模式：通过 java.io.File 访问（适用于有文件系统权限的场景）
 * - SAF/DocumentFile 模式：通过 Storage Access Framework 访问（适用于 Android 10+ Scoped Storage）
 */
public class HttpFileService {
    private static final String TAG = "HttpFileService";

    // filesystem 模式
    private final String rootPath;
    // SAF/DocumentFile 模式
    private final Context context;
    private final Uri treeUri;
    private final DocumentFile rootDocFile;
    private String displayPath; // 从 SAF URI 解析出的文件系统路径

    private final int port;
    private final boolean useDocumentFile;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread serverThread;
    private String accessUrl;

    @FunctionalInterface
    public interface StatusCallback {
        void onStatus(String message);
    }

    private StatusCallback statusCallback;

    /**
     * filesystem 模式构造器
     */
    public HttpFileService(String rootPath, int port) {
        this.rootPath = rootPath;
        this.port = port;
        this.context = null;
        this.treeUri = null;
        this.displayPath = rootPath;
        this.rootDocFile = null;
        this.useDocumentFile = false;
    }

    /**
     * SAF/DocumentFile 模式构造器
     * 适用于 Android 10+ Scoped Storage，通过 SAF 获取的 treeUri 来访问文件
     */
    public HttpFileService(Context context, Uri treeUri, int port) {
        this.context = context.getApplicationContext();
        this.rootPath = null;
        this.treeUri = treeUri;
        this.port = port;
        this.rootDocFile = DocumentFile.fromTreeUri(this.context, treeUri);
        this.useDocumentFile = true;
        this.displayPath = resolveStoragePath(treeUri);
        if (this.rootDocFile != null && this.rootDocFile.exists()) {
            Log.i(TAG, "DocumentFile root: " + this.rootDocFile.getName() + " (" + displayPath + ")");
        } else {
            Log.w(TAG, "DocumentFile.fromTreeUri returned null or non-existent root for: " + treeUri);
        }
    }

    /** 从 SAF treeUri 解析出 /storage/... 格式的文件系统路径 */
    private String resolveStoragePath(Uri uri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            // docId 格式: "primary:Documents" 或 "XXXX-XXXX:path"
            String[] split = docId.split(":", 2);
            if (split.length < 2) return uri.toString();

            String type = split[0];
            String path = split[1];

            if ("primary".equalsIgnoreCase(type)) {
                return "/storage/emulated/0/" + (path.isEmpty() ? "" : path);
            } else {
                return "/storage/" + type + "/" + (path.isEmpty() ? "" : path);
            }
        } catch (Exception e) {
            return uri.toString();
        }
    }

    public void setStatusCallback(StatusCallback callback) {
        this.statusCallback = callback;
    }

    /** 设置浏览器访问 URL，用于日志显示 */
    public void setAccessUrl(String url) {
        this.accessUrl = url;
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        if (statusCallback != null) {
            statusCallback.onStatus(msg);
        }
    }

    public boolean start() {
        if (running.get()) {
            log("Server already running");
            return false;
        }

        if (useDocumentFile && rootDocFile == null) {
            Log.e(TAG, "DocumentFile root is null, cannot start server");
            return false;
        }

        try {
            serverSocket = new ServerSocket(port);
            running.set(true);

            serverThread = new Thread(() -> {
                log("HTTP URL " + (accessUrl != null ? accessUrl : "N/A") + "\nserving: " + displayPath);
                while (running.get()) {
                    try {
                        Socket client = serverSocket.accept();
                        new Thread(() -> handleClient(client)).start();
                    } catch (Exception e) {
                        if (running.get()) {
                            Log.e(TAG, "accept error: " + e.getMessage());
                        }
                    }
                }
                log("HTTP server stopped");
            });
            serverThread.setDaemon(true);
            serverThread.start();
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to start server: " + e.getMessage());
            return false;
        }
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing server: " + e.getMessage());
        }
        if (serverThread != null) {
            try {
                serverThread.join(2000);
            } catch (InterruptedException ignored) {}
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 获取共享根目录的显示名称 */
    public String getRootDisplayName() {
        return (displayPath != null) ? displayPath : "Unknown";
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                client.close();
                return;
            }

            // 解析请求
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendError(client, 400, "Bad Request");
                return;
            }

            String method = parts[0];
            String rawPath = parts[1];

            // 跳过请求头
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // 读取完所有头部
            }

            if (!"GET".equals(method)) {
                sendError(client, 405, "Method Not Allowed");
                return;
            }

            // 解码 URL
            String decodedPath = java.net.URLDecoder.decode(rawPath, "UTF-8");

            if (useDocumentFile) {
                handleDocFileRequest(client, decodedPath);
            } else {
                handleFileRequest(client, decodedPath);
            }

        } catch (Exception e) {
            Log.e(TAG, "handleClient error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== DocumentFile (SAF) 模式 ====================

    /**
     * 根据 URL 路径解析对应的 DocumentFile
     */
    private DocumentFile resolveDocumentFile(String urlPath) {
        if (urlPath.equals("/") || urlPath.isEmpty()) {
            return rootDocFile;
        }

        String[] parts = urlPath.split("/");
        DocumentFile current = rootDocFile;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            DocumentFile child = current.findFile(part);
            if (child == null) {
                Log.w(TAG, "resolveDocumentFile: not found '" + part + "' under " + current.getName());
                return null;
            }
            current = child;
        }
        return current;
    }

    private void handleDocFileRequest(Socket client, String decodedPath) throws Exception {
        DocumentFile docFile = resolveDocumentFile(decodedPath);

        if (docFile == null || !docFile.exists()) {
            sendError(client, 404, "Not Found");
            return;
        }

        if (docFile.isDirectory()) {
            sendDocDirectoryListing(client, docFile, decodedPath);
        } else {
            sendDocFile(client, docFile);
        }
    }

    private void sendDocDirectoryListing(Socket client, DocumentFile dir, String requestPath) throws Exception {
        OutputStream os = client.getOutputStream();
        PrintStream ps = new PrintStream(os);

        DocumentFile[] files = dir.listFiles();
        if (files == null) files = new DocumentFile[0];
        Arrays.sort(files, Comparator.comparing((DocumentFile f) -> !f.isDirectory())
                .thenComparing(f -> f.getName() != null ? f.getName().toLowerCase() : ""));

        StringBuilder html = buildHtmlHead(requestPath);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        // 上级目录链接
        if (!requestPath.equals("/") && !requestPath.isEmpty()) {
            String parentPath = requestPath.substring(0, requestPath.lastIndexOf('/'));
            if (parentPath.isEmpty()) parentPath = "/";
            html.append(buildParentLink(parentPath));
        }

        int visibleCount = 0;
        for (DocumentFile file : files) {
            String name = file.getName();
            if (name == null || name.startsWith(".")) continue;
            visibleCount++;

            String fileUrl = buildFileUrl(requestPath, name);

            html.append("<li class=\"file-item\">");
            if (file.isDirectory()) {
                html.append("<span class=\"file-icon\">📁</span>");
                html.append("<span class=\"file-name\"><a href=\"").append(fileUrl)
                        .append("\">").append(escapeHtml(name)).append("/</a></span>");
                html.append("<span class=\"file-size\">-</span>");
            } else {
                String icon = getFileIcon(name);
                html.append("<span class=\"file-icon\">").append(icon).append("</span>");
                html.append("<span class=\"file-name\"><a href=\"").append(fileUrl)
                        .append("\">").append(escapeHtml(name)).append("</a></span>");
                html.append("<span class=\"file-size\">").append(formatSize(file.length())).append("</span>");
            }
            html.append("<span class=\"file-date\">").append(sdf.format(new Date(file.lastModified()))).append("</span>");
            html.append("</li>");
        }

        if (visibleCount == 0) {
            html.append("<li class=\"file-item\"><span style=\"color:#999;padding:20px;\">📭 空目录</span></li>");
        }

        html.append(buildHtmlFoot(visibleCount));
        byte[] content = html.toString().getBytes("UTF-8");
        sendResponse(os, "200 OK", "text/html; charset=UTF-8", content);
    }

    private void sendDocFile(Socket client, DocumentFile file) throws Exception {
        Uri fileUri = file.getUri();
        String fileName = file.getName();
        String mimeType = resolveMimeType(fileName);

        OutputStream os = client.getOutputStream();

        long fileSize = file.length();
        if (fileSize <= 0) {
            // 无法获取文件大小时，使用 chunked 或直接读取所有内容
            byte[] buffer = new byte[64 * 1024];
            try (InputStream is = context.getContentResolver().openInputStream(fileUri)) {
                if (is == null) {
                    sendError(client, 500, "Cannot read file");
                    return;
                }
                // 先读取全部内容以获取 Content-Length
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                int read;
                while ((read = is.read(buffer)) > 0) {
                    baos.write(buffer, 0, read);
                }
                byte[] data = baos.toByteArray();
                sendResponse(os, "200 OK", mimeType, data);
            }
        } else if (fileSize < 10 * 1024 * 1024) {
            // 小于 10MB：一次性发送
            try (InputStream is = context.getContentResolver().openInputStream(fileUri)) {
                if (is == null) {
                    sendError(client, 500, "Cannot read file");
                    return;
                }
                byte[] data = new byte[(int) fileSize];
                int totalRead = 0;
                while (totalRead < data.length) {
                    int read = is.read(data, totalRead, data.length - totalRead);
                    if (read < 0) break;
                    totalRead += read;
                }
                sendResponse(os, "200 OK", mimeType, data);
            }
        } else {
            // 大文件：流式发送
            try (InputStream is = context.getContentResolver().openInputStream(fileUri)) {
                if (is == null) {
                    sendError(client, 500, "Cannot read file");
                    return;
                }
                PrintStream ps2 = new PrintStream(os);
                ps2.print("HTTP/1.0 200 OK\r\n");
                ps2.print("Content-Type: " + mimeType + "\r\n");
                ps2.print("Content-Length: " + fileSize + "\r\n");
                ps2.print("Connection: close\r\n");
                ps2.print("\r\n");
                ps2.flush();

                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = is.read(buffer)) > 0) {
                    os.write(buffer, 0, read);
                }
            }
            os.flush();
        }
    }

    // ==================== File (filesystem) 模式 ====================

    private void handleFileRequest(Socket client, String decodedPath) throws Exception {
        // 安全检查：防止路径穿越
        File requestedFile = new File(rootPath, decodedPath).getCanonicalFile();
        File rootDir = new File(rootPath).getCanonicalFile();

        if (!requestedFile.getPath().startsWith(rootDir.getPath())) {
            sendError(client, 403, "Forbidden");
            return;
        }

        if (!requestedFile.exists()) {
            sendError(client, 404, "Not Found");
            return;
        }

        if (requestedFile.isDirectory()) {
            sendFileDirectoryListing(client, requestedFile, decodedPath);
        } else {
            sendFileBinary(client, requestedFile);
        }
    }

    private void sendFileDirectoryListing(Socket client, File dir, String requestPath) throws Exception {
        OutputStream os = client.getOutputStream();

        File[] files = dir.listFiles();
        if (files == null) files = new File[0];
        Arrays.sort(files, Comparator.comparing(File::isFile)
                .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        StringBuilder html = buildHtmlHead(requestPath);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        // 上级目录链接
        if (!requestPath.equals("/") && !requestPath.isEmpty()) {
            String parentPath = requestPath.substring(0, requestPath.lastIndexOf('/'));
            if (parentPath.isEmpty()) parentPath = "/";
            html.append(buildParentLink(parentPath));
        }

        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(".")) continue;

            String fileUrl = buildFileUrl(requestPath, name);

            html.append("<li class=\"file-item\">");
            if (file.isDirectory()) {
                html.append("<span class=\"file-icon\">📁</span>");
                html.append("<span class=\"file-name\"><a href=\"").append(fileUrl)
                        .append("\">").append(escapeHtml(name)).append("/</a></span>");
                html.append("<span class=\"file-size\">-</span>");
            } else {
                String icon = getFileIcon(name);
                html.append("<span class=\"file-icon\">").append(icon).append("</span>");
                html.append("<span class=\"file-name\"><a href=\"").append(fileUrl)
                        .append("\">").append(escapeHtml(name)).append("</a></span>");
                html.append("<span class=\"file-size\">").append(formatSize(file.length())).append("</span>");
            }
            html.append("<span class=\"file-date\">").append(sdf.format(new Date(file.lastModified()))).append("</span>");
            html.append("</li>");
        }

        if (files.length == 0) {
            html.append("<li class=\"file-item\"><span style=\"color:#999;padding:20px;\">📭 空目录</span></li>");
        }

        html.append(buildHtmlFoot(files.length));
        byte[] content = html.toString().getBytes("UTF-8");
        sendResponse(os, "200 OK", "text/html; charset=UTF-8", content);
    }

    private void sendFileBinary(Socket client, File file) throws Exception {
        String mimeType = resolveMimeType(file.getName());
        OutputStream os = client.getOutputStream();

        long fileSize = file.length();
        if (fileSize > 0 && fileSize < 10 * 1024 * 1024) {
            byte[] data = new byte[(int) fileSize];
            try (FileInputStream fis = new FileInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                int totalRead = 0;
                while (totalRead < data.length) {
                    int read = bis.read(data, totalRead, data.length - totalRead);
                    if (read < 0) break;
                    totalRead += read;
                }
            }
            sendResponse(os, "200 OK", mimeType, data);
        } else {
            PrintStream ps = new PrintStream(os);
            ps.print("HTTP/1.0 200 OK\r\n");
            ps.print("Content-Type: " + mimeType + "\r\n");
            ps.print("Content-Length: " + fileSize + "\r\n");
            ps.print("Connection: close\r\n");
            ps.print("\r\n");
            ps.flush();

            byte[] buffer = new byte[64 * 1024];
            try (FileInputStream fis = new FileInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                int read;
                while ((read = bis.read(buffer)) > 0) {
                    os.write(buffer, 0, read);
                }
            }
            os.flush();
        }
    }

    // ==================== HTML 构建工具方法 ====================

    /** 构建 HTML head、样式和面包屑导航 */
    private StringBuilder buildHtmlHead(String requestPath) {
        // 构建面包屑导航
        StringBuilder breadcrumb = new StringBuilder();
        breadcrumb.append("<a href=\"/\"> 📁 Root</a>");
        if (!requestPath.equals("/") && !requestPath.isEmpty()) {
            String[] crumbs = requestPath.split("/");
            StringBuilder cumulative = new StringBuilder();
            for (String crumb : crumbs) {
                if (crumb.isEmpty()) continue;
                cumulative.append("/").append(crumb);
                breadcrumb.append(" / <a href=\"")
                        .append(cumulative.toString()).append("\">")
                        .append(escapeHtml(crumb)).append("</a>");
            }
        }

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<meta charset=\"UTF-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        html.append("<title>Device2Device - File Browser</title>");
        html.append("<style>");
        html.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;margin:0;padding:16px;background:#f5f5f5;}");
        html.append(".container{max-width:900px;margin:0 auto;background:#fff;border-radius:8px;box-shadow:0 2px 8px rgba(0,0,0,0.1);padding:16px;}");
        html.append("h1{font-size:20px;margin:0 0 12px 0;color:#333;}");
        html.append(".breadcrumb{font-size:14px;padding:8px 0;margin-bottom:12px;border-bottom:1px solid #eee;color:#666;}");
        html.append(".breadcrumb a{color:#1976d2;text-decoration:none;}");
        html.append(".breadcrumb a:hover{text-decoration:underline;}");
        html.append(".file-list{list-style:none;padding:0;margin:0;}");
        html.append(".file-item{display:flex;align-items:center;padding:10px 12px;border-bottom:1px solid #f0f0f0;transition:background 0.15s;}");
        html.append(".file-item:hover{background:#f8f9fa;}");
        html.append(".file-item:last-child{border-bottom:none;}");
        html.append(".file-icon{width:24px;margin-right:12px;font-size:18px;text-align:center;flex-shrink:0;}");
        html.append(".file-name{flex:1;font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0;}");
        html.append(".file-name a{color:#333;text-decoration:none;}");
        html.append(".file-name a:hover{color:#1976d2;}");
        html.append(".file-size{font-size:12px;color:#999;margin-left:16px;white-space:nowrap;flex-shrink:0;}");
        html.append(".file-date{font-size:12px;color:#999;margin-left:12px;white-space:nowrap;flex-shrink:0;}");
        html.append(".footer{text-align:center;font-size:12px;color:#999;margin-top:16px;padding-top:12px;border-top:1px solid #eee;}");
        html.append("</style></head><body>");
        html.append("<div class=\"container\">");
        html.append("<h1>📂 Device2Device File Browser</h1>");
        html.append("<div class=\"breadcrumb\">").append(breadcrumb).append("</div>");
        html.append("<ul class=\"file-list\">");
        return html;
    }

    /** 构建上级目录链接项 */
    private String buildParentLink(String parentPath) {
        return "<li class=\"file-item\">" +
                "<span class=\"file-icon\">📁</span>" +
                "<span class=\"file-name\"><a href=\"" + parentPath + "\">..</a></span>" +
                "<span class=\"file-size\"></span>" +
                "<span class=\"file-date\"></span>" +
                "</li>";
    }

    /** 构建文件条目的 URL */
    private String buildFileUrl(String requestPath, String fileName) {
        String base = requestPath;
        if (!base.endsWith("/")) base += "/";
        try {
            return base + java.net.URLEncoder.encode(fileName, "UTF-8");
        } catch (Exception e) {
            return base + fileName;
        }
    }

    /** 构建 HTML 尾部 */
    private String buildHtmlFoot(int fileCount) {
        return "</ul>" +
                "<div class=\"footer\">Device2Device HTTP Server · Port " + port +
                " · " + fileCount + " 项</div>" +
                "</div></body></html>";
    }

    // ==================== 通用 HTTP 响应 ====================

    private void sendError(Socket client, int code, String message) {
        try {
            String html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">" +
                    "<title>" + code + " " + message + "</title></head>" +
                    "<body style=\"font-family:sans-serif;text-align:center;padding:40px;\">" +
                    "<h1>" + code + " " + message + "</h1>" +
                    "<p><a href=\"/\">← 返回根目录</a></p>" +
                    "</body></html>";
            sendResponse(client.getOutputStream(), code + " " + message,
                    "text/html; charset=UTF-8", html.getBytes("UTF-8"));
        } catch (Exception ignored) {}
    }

    private void sendResponse(OutputStream os, String status, String contentType, byte[] content) throws Exception {
        PrintStream ps = new PrintStream(os);
        ps.print("HTTP/1.0 " + status + "\r\n");
        ps.print("Content-Type: " + contentType + "\r\n");
        ps.print("Content-Length: " + content.length + "\r\n");
        ps.print("Connection: close\r\n");
        ps.print("Server: Device2Device\r\n");
        ps.print("Access-Control-Allow-Origin: *\r\n");
        ps.print("\r\n");
        ps.flush();
        os.write(content);
        os.flush();
    }

    // ==================== 工具方法 ====================

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** 根据文件名解析 MIME 类型 */
    private static String resolveMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".txt") || lower.endsWith(".log")) return "text/plain";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    private static String getFileIcon(String name) {
        if (name == null) return "📎";
        String lower = name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp"))
            return "🖼️";
        if (lower.endsWith(".mp4") || lower.endsWith(".avi") || lower.endsWith(".mkv") || lower.endsWith(".mov"))
            return "🎬";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") || lower.endsWith(".ogg"))
            return "🎵";
        if (lower.endsWith(".pdf")) return "📕";
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar"))
            return "📦";
        if (lower.endsWith(".apk")) return "📱";
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log"))
            return "📄";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "🌐";
        if (lower.endsWith(".xml") || lower.endsWith(".json")) return "📋";
        if (lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".c") ||
                lower.endsWith(".cpp") || lower.endsWith(".py") || lower.endsWith(".js"))
            return "💻";
        return "📎";
    }
}
