package com.tsymiar.device2device.service;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 轻量级 HTTP 文件浏览服务器
 * 支持目录列表和文件下载，可直接在浏览器中访问
 * 支持两种模式：
 * - filesystem 模式：通过 java.io.File 访问（适用于有文件系统权限的场景）
 * - SAF/DocumentFile 模式：通过 Storage Access Framework 访问（适用于 Android 10+ Scoped Storage）
 */
public class HttpBrowserService {
    private static final String TAG = "HttpBrowserService";

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
    private volatile ExecutorService clientPool; // 客户端连接工作线程池
    private String accessUrl;

    // ===== 会话级状态：完整下载记录 + 缩略图内存缓存（服务生命周期内有效） =====
    /** 完整传输成功过的文件（键为 URL 的 decodedPath 形态，如 /DCIM/photo.jpg） */
    private final Set<String> completedPaths =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** 缩略图字节缓存（天然 LRU，上限 256 张） */
    private final Map<String, byte[]> thumbCache =
            Collections.synchronizedMap(new LinkedHashMap<String, byte[]>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > 256;
                }
            });

    /** 图片查看器压缩预览字节缓存（天然 LRU，上限 48 张：翻页/重复打开秒回，避免重复解码大图） */
    private final Map<String, byte[]> previewCache =
            Collections.synchronizedMap(new LinkedHashMap<String, byte[]>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > 48;
                }
            });

    // ===== 响应性能辅助：目录短期缓存 + HTML gzip + 缩略图生成限流 =====
    /** 目录子项列表短期缓存 TTL：合并连续翻页/换排序对同一目录的重复全量扫描（浏览场景下目录内容基本不变） */
    private static final long DIR_CACHE_TTL_MS = 3000L;
    private static final int DIR_CACHE_MAX = 96;

    /** 带时间戳的目录子项缓存值（存 List<File> 或 List<DocEntry>，仅服务会话有效） */
    private static final class DirCache {
        final long ts;
        final List<?> items;
        DirCache(long ts, List<?> items) { this.ts = ts; this.items = items; }
    }

    /** filesystem 模式目录条目缓存；访问序 LRU，容量受限防止浏览大量子目录造成内存膨胀 */
    private final Map<String, DirCache> fsDirCache = Collections.synchronizedMap(
            new LinkedHashMap<String, DirCache>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, DirCache> eldest) {
                    return size() > DIR_CACHE_MAX;
                }
            });
    /** SAF/DocumentFile 模式目录条目缓存 */
    private final Map<String, DirCache> safDirCache = Collections.synchronizedMap(
            new LinkedHashMap<String, DirCache>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, DirCache> eldest) {
                    return size() > DIR_CACHE_MAX;
                }
            });

    /** 当前请求 Accept-Encoding 是否含 gzip（仅用于 text/html 目录页压缩决策，连接内串行设置） */
    private final ThreadLocal<Boolean> reqGzip = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() { return Boolean.FALSE; }
    };
    /** 当前请求 Cookie 原文（浏览状态 d2d 用：排序偏好记忆 + 同目录页码恢复；连接内串行设置，keep-alive 多请求各自覆盖） */
    private final ThreadLocal<String> reqCookie = new ThreadLocal<String>();
    /** 缩略图现场解码并发上限：避免一页大量图片/视频首载同时解码占满手机 CPU */
    private final Semaphore thumbSlots = new Semaphore(2);

    @FunctionalInterface
    public interface StatusCallback {
        void onStatus(String message);
    }

    private StatusCallback statusCallback;

    /**
     * filesystem 模式构造器
     */
    public HttpBrowserService(String rootPath, int port) {
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
    public HttpBrowserService(Context context, Uri treeUri, int port) {
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

            // 可缓存线程池：避免每个短连接（目录页/图片缩略图）都新建线程的开销
            clientPool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "http-client");
                t.setDaemon(true);
                return t;
            });

            serverThread = new Thread(() -> {
                log("HTTP URL " + (accessUrl != null ? accessUrl : "N/A") + "\nserving: " + displayPath);
                while (running.get()) {
                    try {
                        Socket client = serverSocket.accept();
                        clientPool.execute(() -> handleClient(client));
                    } catch (Exception e) {
                        if (running.get()) {
                            Log.e(TAG, "accept error: " + e.getMessage());
                        }
                    }
                }
                log("HTTP server has stopped.");
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
        if (clientPool != null) {
            clientPool.shutdownNow();
            clientPool = null;
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
            // TCP 调优：禁用 Nagle（减小目录页/小响应延迟），放大发送缓冲（提升大文件吞吐）
            client.setTcpNoDelay(true);
            try { client.setSendBufferSize(512 * 1024); } catch (Exception ignored) {}
            client.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));

            // keep-alive：同一条 TCP 连接顺序服务多个请求（目录页/图片/小文件复用，免去反复握手与慢启动）
            while (running.get()) {
                String requestLine = reader.readLine();
                if (requestLine == null) return; // 客户端断开或空闲超时 → 结束连接
                if (requestLine.isEmpty()) continue; // RFC 7230：容忍请求前的空行
                try {
                    // 解析请求
                    String[] parts = requestLine.split(" ");
                    if (parts.length < 2) {
                        sendError(client, 400, "Bad Request");
                        return;
                    }

                    String method = parts[0];
                    String rawPath = parts[1];
                    String proto = parts.length > 2 ? parts[2] : "HTTP/1.0";
                    boolean http11 = proto != null && proto.toUpperCase(Locale.ROOT).contains("HTTP/1.1");

                    // 跳过请求头，识别 Connection 语义决定是否复用连接
                    String line;
                    boolean forceClose = false;
                    boolean kaRequested = false;
                    boolean gzipOk = false;
                    String cookieHeader = null;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.regionMatches(true, 0, "connection:", 0, 11)) {
                            String v = line.substring(11).trim().toLowerCase(Locale.ROOT);
                            if (v.contains("close")) forceClose = true;
                            if (v.contains("keep-alive")) kaRequested = true;
                        } else if (line.regionMatches(true, 0, "accept-encoding:", 0, 16)) {
                            // 目录 HTML 传输压缩决策用（主流浏览器均携带 gzip）
                            gzipOk = line.substring(16).toLowerCase(Locale.ROOT).contains("gzip");
                        } else if (line.regionMatches(true, 0, "cookie:", 0, 7)) {
                            // 目录页浏览状态（d2d Cookie）用：排序偏好记忆 + 同目录页码恢复
                            cookieHeader = line.substring(7).trim();
                        }
                    }
                    if (line == null) return; // 请求头未读完即断开
                    // HTTP/1.1 默认复用，HTTP/1.0 仅在显式请求 keep-alive 时复用
                    boolean keepAlive = http11 ? !forceClose : kaRequested && !forceClose;
                    reqGzip.set(gzipOk);
                    reqCookie.set(cookieHeader);

                    if (!"GET".equals(method)) {
                        sendError(client, 405, "Method Not Allowed");
                        return;
                    }

                    // 拆分路径与查询串（zip 打包参数在查询串中），再解码路径
                    String pathPart = rawPath;
                    String queryPart = null;
                    int qIdx = rawPath.indexOf('?');
                    if (qIdx >= 0) {
                        pathPart = rawPath.substring(0, qIdx);
                        queryPart = rawPath.substring(qIdx + 1);
                    }
                    String decodedPath = java.net.URLDecoder.decode(pathPart, "UTF-8");

                    // zip（chunked/连接关闭收尾）或错误响应由处理函数返回 false，主动结束本连接
                    boolean keepGoing;
                    if (useDocumentFile) {
                        keepGoing = handleDocFileRequest(client, decodedPath, queryPart, http11, keepAlive);
                    } else {
                        keepGoing = handleFileRequest(client, decodedPath, queryPart, http11, keepAlive);
                    }
                    if (!keepGoing) return;

                } catch (java.net.SocketTimeoutException te) {
                    return; // keep-alive 空闲超时，静默关闭
                } catch (Exception e) {
                    Log.e(TAG, "handleClient error: " + e.getMessage());
                    return;
                }
            }
        } catch (Exception e) {
            if (running.get() && !(e instanceof java.net.SocketTimeoutException)
                    && !(e instanceof java.net.SocketException)) {
                Log.e(TAG, "handleClient error: " + e.getMessage());
            }
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

    /**
     * 单次批量查询目录的所有子项元数据。
     * 与逐文件调用 isDirectory()/length()/lastModified() 不同，这里一次 Cursor
     * 就取回全部子项的 文件(夹)/类型/大小/时间，避免 N 次 ContentProvider Binder 往返，
     * 显著加快大目录浏览。失败返回 null，由调用方回退到逐项查询。
     */
    private List<DocEntry> queryDocChildren(DocumentFile dir) {
        if (dir == null) return null;
        List<DocEntry> list = new ArrayList<>();
        Cursor cursor = null;
        try {
            String[] projection = {
                    Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_MIME_TYPE,
                    Document.COLUMN_SIZE,
                    Document.COLUMN_LAST_MODIFIED
            };
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getDocumentId(dir.getUri()));
            cursor = context.getContentResolver().query(childrenUri, projection, null, null, null);
            if (cursor == null) {
                Log.w(TAG, "query children cursor null, fallback to per-file");
                return null;
            }
            int idxName = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME);
            int idxMime = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE);
            int idxSize = cursor.getColumnIndex(Document.COLUMN_SIZE);
            int idxMod = cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED);
            while (cursor.moveToNext()) {
                String name = cursor.getString(idxName);
                if (name == null || name.startsWith(".")) continue;
                String mime = (idxMime >= 0) ? cursor.getString(idxMime) : null;
                // 目录判定与 androidx DocumentFile.isDirectory() 一致：目录的 MIME 恒为 MIME_TYPE_DIR
                boolean isDir = Document.MIME_TYPE_DIR.equals(mime);
                long size = (idxSize >= 0 && !cursor.isNull(idxSize)) ? cursor.getLong(idxSize) : 0;
                long modified = (idxMod >= 0 && !cursor.isNull(idxMod)) ? cursor.getLong(idxMod) : -1;
                list.add(new DocEntry(name, isDir, size, modified));
            }
            return list;
        } catch (Exception e) {
            Log.w(TAG, "batch query children failed, fallback to per-file: " + e.getMessage());
            return null;
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** SAF 目录一次性装载全部可见子项：批量 Cursor 优先，失败回退逐项查询（结果可被目录缓存复用） */
    private List<DocEntry> loadDocChildren(DocumentFile dir) {
        List<DocEntry> batched = queryDocChildren(dir);
        if (batched != null) return batched;
        // 批量查询失败（个别 Provider 兼容性问题）→ 回退逐项查询
        List<DocEntry> list = new ArrayList<>();
        DocumentFile[] files = dir.listFiles();
        if (files == null) return list;
        for (DocumentFile file : files) {
            String name = file.getName();
            if (name == null || name.startsWith(".")) continue;
            boolean isDirectory = file.isDirectory();
            list.add(new DocEntry(name, isDirectory,
                    isDirectory ? 0 : file.length(), file.lastModified()));
        }
        return list;
    }

    /** 目录子项列表短期缓存：TTL 内命中直接复用（供连续翻页/换排序/重复导航），未命中经 loader 重建并写回 */
    private static List<?> cachedDirItems(Map<String, DirCache> cache, String key,
                                          Supplier<List<?>> loader) {
        long now = System.currentTimeMillis();
        DirCache hit = cache.get(key);
        if (hit != null && now - hit.ts < DIR_CACHE_TTL_MS) return hit.items;
        List<?> fresh = loader.get();
        if (fresh == null) fresh = Collections.emptyList();
        cache.put(key, new DirCache(now, fresh));
        return fresh;
    }

    /** 目录条目元数据快照（名称/是否目录/字节数/最后修改毫秒） */
    private static final class DocEntry {
        final String name;
        final boolean isDir;
        final long size;
        final long lastModified;

        DocEntry(String name, boolean isDir, long size, long lastModified) {
            this.name = name;
            this.isDir = isDir;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    /**
     * SAF/DocumentFile 模式请求分发。返回 true 表示响应后连接可复用（keep-alive）；
     * 返回 false 表示本连接须关闭（zip 打包/错误响应）。
     */
    private boolean handleDocFileRequest(Socket client, String decodedPath, String query,
                                         boolean http11, boolean keepAlive) throws Exception {
        DocumentFile docFile = resolveDocumentFile(decodedPath);

        if (docFile == null || !docFile.exists()) {
            sendError(client, 404, "Not Found");
            return false;
        }

        if (docFile.isDirectory()) {
            List<String> zipNames = parseZipNames(query);
            if (zipNames != null) {
                // zip 边压边传依赖 chunked/连接关闭收尾，该连接不再复用
                sendDocZip(client, docFile, zipNames, http11);
                return false;
            }
            sendDocDirectoryListing(client, docFile, decodedPath, query, keepAlive);
            return keepAlive;
        } else if (isThumbRequest(query)) {
            // 图片/视频缩略图请求：独立小图响应，不计入“完整下载”标记
            sendDocThumb(client, docFile, keepAlive);
            return keepAlive;
        } else if (isPreviewRequest(query)) {
            // 查看器压缩预览：光栅图现场解码为最长边≤1920 的 JPEG，避免整张大图传输；预览不计入“完整下载”。
            // 非光栅(svg 等)/解码失败回退原图发送并照常标记，保证可查看与可下载
            byte[] pv = previewOf(docFile.getUri().toString(), null, docFile);
            if (pv == null) {
                boolean ok = sendDocFile(client, docFile, keepAlive);
                if (ok) completedPaths.add(decodedPath);
                return ok;
            }
            writeBytesResponse(client, pv, keepAlive);
            return keepAlive;
        } else {
            boolean ok = sendDocFile(client, docFile, keepAlive);
            if (ok) completedPaths.add(decodedPath); // 整份内容已成功发完（可 keep-alive 复用）才标记
            return ok;
        }
    }

    private void sendDocDirectoryListing(Socket client, DocumentFile dir, String requestPath,
                                         String query, boolean keepAlive) throws Exception {
        OutputStream os = client.getOutputStream();

        // 子项列表做短期缓存(3s TTL)复用：快速翻页/换排序时不再对同一目录重复全量扫描；
        // 未命中才走 loadDocChildren 的单次批量 Cursor(失败逐项回退)。取副本排序，不污染共享缓存。
        @SuppressWarnings("unchecked")
        List<DocEntry> entries = new ArrayList<>((List<DocEntry>) cachedDirItems(
                safDirCache, dir.getUri().toString(), () -> loadDocChildren(dir)));
        // 服务端全局排序：目录恒置顶，再按 列键(sort)+方向(dir) 排序，翻页/换列保持同一顺序。
        // 排序键/方向/页码优先取 URL 动作参数，否则用 d2d Cookie 记忆（排序全局生效；页码仅同目录恢复）
        BrowseState bs = resolveBrowseState(requestPath, query);
        String sortKey = bs.sortKey;
        boolean asc = bs.asc;
        sortDocEntries(entries, sortKey, asc);

        // 大目录分页：先计算总条数与当前页码，再截取本页渲染范围
        int total = entries.size();
        int page = bs.page;
        int pageCount = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > pageCount) page = pageCount;
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(total, start + PAGE_SIZE);
        List<DocEntry> pageEntries = entries.subList(start, end);

        // 动作请求（列头排序/翻页）：Set-Cookie 记忆状态后 302 回无参路径，URL 不残留长参数且状态不丢
        String setCookie = browseSetCookieIfNeeded(requestPath, bs, sortKey, asc, page);
        if (bs.action) {
            sendRedirect(os, encodeUrlPath(requestPath), keepAlive, setCookie);
            return;
        }

        StringBuilder html = buildHtmlHead(requestPath, sortKey, asc, page);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        // 上级目录链接
        if (!requestPath.equals("/") && !requestPath.isEmpty()) {
            String parentPath = requestPath.substring(0, requestPath.lastIndexOf('/'));
            if (parentPath.isEmpty()) parentPath = "/";
            html.append(buildParentLink(parentPath));
        }

        if (entries.isEmpty()) {
            html.append("<li class=\"file-item\"><span class=\"chk\"></span><span class=\"file-icon\"></span><span style=\"color:#999;padding:14px;\">📭 空目录</span></li>");
        }
        for (DocEntry entry : pageEntries) {
            String name = entry.name;
            String fileUrl = buildFileUrl(requestPath, name);

            html.append("<li class=\"file-item\" data-type=\"")
                    .append(entry.isDir ? "dir" : "file").append("\" data-name=\"")
                    .append(escapeHtml(name)).append("\" data-size=\"")
                    .append(entry.isDir ? 0 : entry.size).append("\" data-date=\"")
                    .append(entry.lastModified < 0 ? 0 : entry.lastModified).append("\">");
            html.append("<span class=\"chk\"><input type=\"checkbox\" class=\"cbox\" data-name=\"")
                    .append(escapeHtml(name)).append("\"></span>");
            if (entry.isDir) {
                html.append("<span class=\"file-icon\">📁</span>");
                html.append("<span class=\"file-name\"><a href=\"").append(fileUrl)
                        .append("\" title=\"").append(escapeHtml(name)).append("\">")
                        .append(escapeHtml(name)).append("/</a></span>");
                html.append("<span class=\"file-size\">-</span>");
            } else {
                String icon = getFileIcon(name);
                if (canThumbnail(name)) {
                    // 图片/视频行显示服务端缩略图；解码失败时退回原图标
                    html.append("<span class=\"file-icon\"><img class=\"thumb\" src=\"").append(fileUrl)
                            .append("?thumb=1\" alt=\"\" loading=\"lazy\" onerror=\"this.parentNode.textContent='")
                            .append(icon).append("'\"></span>");
                } else {
                    html.append("<span class=\"file-icon\">").append(icon).append("</span>");
                }
                html.append("<span class=\"file-name\"><a");
                String linkCls = isImageViewable(name) ? "img" : "";
                if (completedPaths.contains(childPath(requestPath, name)))
                    linkCls = linkCls.isEmpty() ? "done" : linkCls + " done";
                if (!linkCls.isEmpty()) html.append(" class=\"").append(linkCls).append("\"");
                if (isImageViewable(name)) html.append(" data-name=\"").append(escapeHtml(name)).append("\"");
                html.append(" title=\"").append(escapeHtml(name)).append("\" href=\"").append(fileUrl)
                        .append("\">").append(escapeHtml(name)).append("</a></span>");
                html.append("<span class=\"file-size\">").append(formatSize(entry.size)).append("</span>");
            }
            long millis = entry.lastModified < 0 ? 0 : entry.lastModified;
            html.append("<span class=\"file-date\">").append(sdf.format(new Date(millis))).append("</span>");
            html.append("</li>");
        }

        html.append(buildHtmlFoot(total, buildPagerHtml(
                encodeUrlPath(requestPath) + "?sort=" + sortKey + "&d=" + (asc ? "0" : "1"),
                page, pageCount, start, end, total)));
        byte[] content = html.toString().getBytes("UTF-8");
        sendResponse(os, "200 OK", "text/html; charset=UTF-8", content, keepAlive, setCookie);
    }

    private boolean sendDocFile(Socket client, DocumentFile file, boolean keepAlive) throws Exception {
        Uri fileUri = file.getUri();
        String fileName = file.getName();
        String mimeType = resolveMimeType(fileName);

        OutputStream os = client.getOutputStream();

        long fileSize = file.length();
        if (fileSize <= 0) {
            // 无法获取文件大小时，先读入内存取得 Content-Length（避免 keep-alive 下无边界响应）
            byte[] buffer = new byte[64 * 1024];
            try (InputStream is = context.getContentResolver().openInputStream(fileUri)) {
                if (is == null) {
                    sendError(client, 500, "Cannot read file");
                    return false;
                }
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                int read;
                while ((read = is.read(buffer)) > 0) {
                    baos.write(buffer, 0, read);
                }
                byte[] data = baos.toByteArray();
                sendResponse(os, "200 OK", mimeType, data, keepAlive);
            }
        } else if (fileSize < 10 * 1024 * 1024) {
            // 小于 10MB：一次性发送
            try (InputStream is = context.getContentResolver().openInputStream(fileUri)) {
                if (is == null) {
                    sendError(client, 500, "Cannot read file");
                    return false;
                }
                byte[] data = new byte[(int) fileSize];
                int totalRead = 0;
                while (totalRead < data.length) {
                    int read = is.read(data, totalRead, data.length - totalRead);
                    if (read < 0) break;
                    totalRead += read;
                }
                sendResponse(os, "200 OK", mimeType, data, keepAlive);
            }
        } else {
            // 大文件：流式发送（256KB 缓冲，减少 ContentProvider IPC/系统调用往返）
            try (InputStream is = context.getContentResolver().openInputStream(fileUri)) {
                if (is == null) {
                    sendError(client, 500, "Cannot read file");
                    return false;
                }
                streamLargeFile(os, is, mimeType, fileSize, keepAlive);
            }
        }
        return keepAlive;
    }

    // ==================== File (filesystem) 模式 ====================

    /** filesystem 模式请求分发。返回 true 表示响应后连接可复用（keep-alive）；false 表示本连接须关闭 */
    private boolean handleFileRequest(Socket client, String decodedPath, String query,
                                      boolean http11, boolean keepAlive) throws Exception {
        // 安全检查：防止路径穿越
        File requestedFile = new File(rootPath, decodedPath).getCanonicalFile();
        File rootDir = new File(rootPath).getCanonicalFile();

        if (!requestedFile.getPath().startsWith(rootDir.getPath())) {
            sendError(client, 403, "Forbidden");
            return false;
        }

        if (!requestedFile.exists()) {
            sendError(client, 404, "Not Found");
            return false;
        }

        if (requestedFile.isDirectory()) {
            List<String> zipNames = parseZipNames(query);
            if (zipNames != null) {
                // zip 边压边传依赖 chunked/连接关闭收尾，该连接不再复用
                sendFileZip(client, requestedFile, zipNames, http11);
                return false;
            }
            sendFileDirectoryListing(client, requestedFile, decodedPath, query, keepAlive);
            return keepAlive;
        } else if (isThumbRequest(query)) {
            // 图片/视频缩略图请求：独立小图响应，不计入“完整下载”标记
            sendFileThumb(client, requestedFile, keepAlive);
            return keepAlive;
        } else if (isPreviewRequest(query)) {
            // 查看器压缩预览：光栅图现场解码为最长边≤1920 的 JPEG，避免整张大图传输；预览不计入“完整下载”。
            // 非光栅(svg 等)/解码失败回退原图发送并照常标记，保证可查看与可下载
            byte[] pv = previewOf(requestedFile.getAbsolutePath(), requestedFile, null);
            if (pv == null) {
                sendFileBinary(client, requestedFile, keepAlive);
                completedPaths.add(decodedPath);
                return keepAlive;
            }
            writeBytesResponse(client, pv, keepAlive);
            return keepAlive;
        } else {
            sendFileBinary(client, requestedFile, keepAlive);
            completedPaths.add(decodedPath); // 能走到这里说明整份内容已成功发完，才算“浏览过/下载过”
            return keepAlive;
        }
    }

    private void sendFileDirectoryListing(Socket client, File dir, String requestPath,
                                          String query, boolean keepAlive) throws Exception {
        OutputStream os = client.getOutputStream();

        // 子项列表做短期缓存(3s TTL)复用：快速翻页/换排序时不再对同一目录重复 listFiles 全量扫描。
        // 取副本后排序/分页，不污染共享缓存。缓存值已跳过点文件。
        @SuppressWarnings("unchecked")
        List<File> items = new ArrayList<>((List<File>) cachedDirItems(
                fsDirCache, dir.getAbsolutePath(), () -> {
                    File[] files = dir.listFiles();
                    if (files == null) return new ArrayList<File>();
                    List<File> list = new ArrayList<>(files.length);
                    for (File f : files) if (!f.getName().startsWith(".")) list.add(f);
                    return list;
                }));
        // 服务端全局排序：目录恒置顶，再按 列键(sort)+方向(dir) 排序，翻页/换列保持同一顺序。
        // 排序键/方向/页码优先取 URL 动作参数，否则用 d2d Cookie 记忆（排序全局生效；页码仅同目录恢复）
        BrowseState bs = resolveBrowseState(requestPath, query);
        String sortKey = bs.sortKey;
        boolean asc = bs.asc;
        sortFileEntries(items, sortKey, asc);

        // 大目录分页：计算总条数与当前页码，再截取本页渲染范围
        int total = items.size();
        int page = bs.page;
        int pageCount = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > pageCount) page = pageCount;

        // 动作请求（列头排序/翻页）：Set-Cookie 记忆状态后 302 回无参路径，URL 不残留长参数且状态不丢
        String setCookie = browseSetCookieIfNeeded(requestPath, bs, sortKey, asc, page);
        if (bs.action) {
            sendRedirect(os, encodeUrlPath(requestPath), keepAlive, setCookie);
            return;
        }

        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(total, start + PAGE_SIZE);

        StringBuilder html = buildHtmlHead(requestPath, sortKey, asc, page);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        // 上级目录链接
        if (!requestPath.equals("/") && !requestPath.isEmpty()) {
            String parentPath = requestPath.substring(0, requestPath.lastIndexOf('/'));
            if (parentPath.isEmpty()) parentPath = "/";
            html.append(buildParentLink(parentPath));
        }

        for (int i = start; i < end; i++) {
            File file = items.get(i);
            String name = file.getName();

            String fileUrl = buildFileUrl(requestPath, name);

            html.append("<li class=\"file-item\" data-type=\"")
                    .append(file.isDirectory() ? "dir" : "file").append("\" data-name=\"")
                    .append(escapeHtml(name)).append("\" data-size=\"")
                    .append(file.isDirectory() ? 0 : file.length()).append("\" data-date=\"")
                    .append(file.lastModified()).append("\">");
            html.append("<span class=\"chk\"><input type=\"checkbox\" class=\"cbox\" data-name=\"")
                    .append(escapeHtml(name)).append("\"></span>");
            if (file.isDirectory()) {
                html.append("<span class=\"file-icon\">📁</span>");
                html.append("<span class=\"file-name\"><a href=\"").append(fileUrl)
                        .append("\" title=\"").append(escapeHtml(name)).append("\">")
                        .append(escapeHtml(name)).append("/</a></span>");
                html.append("<span class=\"file-size\">-</span>");
            } else {
                String icon = getFileIcon(name);
                if (canThumbnail(name)) {
                    // 图片/视频行显示服务端缩略图；解码失败时退回原图标
                    html.append("<span class=\"file-icon\"><img class=\"thumb\" src=\"").append(fileUrl)
                            .append("?thumb=1\" alt=\"\" loading=\"lazy\" onerror=\"this.parentNode.textContent='")
                            .append(icon).append("'\"></span>");
                } else {
                    html.append("<span class=\"file-icon\">").append(icon).append("</span>");
                }
                html.append("<span class=\"file-name\"><a");
                String linkCls = isImageViewable(name) ? "img" : "";
                if (completedPaths.contains(childPath(requestPath, name)))
                    linkCls = linkCls.isEmpty() ? "done" : linkCls + " done";
                if (!linkCls.isEmpty()) html.append(" class=\"").append(linkCls).append("\"");
                if (isImageViewable(name)) html.append(" data-name=\"").append(escapeHtml(name)).append("\"");
                html.append(" title=\"").append(escapeHtml(name)).append("\" href=\"").append(fileUrl)
                        .append("\">").append(escapeHtml(name)).append("</a></span>");
                html.append("<span class=\"file-size\">").append(formatSize(file.length())).append("</span>");
            }
            html.append("<span class=\"file-date\">").append(sdf.format(new Date(file.lastModified()))).append("</span>");
            html.append("</li>");
        }

        if (total == 0) {
            html.append("<li class=\"file-item\"><span class=\"chk\"></span><span class=\"file-icon\"></span><span style=\"color:#999;padding:14px;\">📭 空目录</span></li>");
        }

        html.append(buildHtmlFoot(total, buildPagerHtml(
                encodeUrlPath(requestPath) + "?sort=" + sortKey + "&d=" + (asc ? "0" : "1"),
                page, pageCount, start, end, total)));
        byte[] content = html.toString().getBytes("UTF-8");
        sendResponse(os, "200 OK", "text/html; charset=UTF-8", content, keepAlive, setCookie);
    }

    private void sendFileBinary(Socket client, File file, boolean keepAlive) throws Exception {
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
            sendResponse(os, "200 OK", mimeType, data, keepAlive);
        } else {
            // 大文件：流式发送（256KB 缓冲）
            try (FileInputStream fis = new FileInputStream(file)) {
                streamLargeFile(os, fis, mimeType, fileSize, keepAlive);
            }
        }
    }

    // ==================== 勾选打包 ZIP 下载 ====================

    /** 从查询串解析 zip 参数（可重复 zip=name，每个值均已 URL 编码）。无 zip 参数返回 null */
    private static List<String> parseZipNames(String query) {
        if (query == null || query.isEmpty()) return null;
        List<String> names = new ArrayList<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!"zip".equals(key)) continue;
            String val = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                val = java.net.URLDecoder.decode(val, "UTF-8");
            } catch (Exception ignored) {}
            if (!val.isEmpty() && isSafeChildName(val)) {
                names.add(val);
            }
        }
        return names.isEmpty() ? null : names;
    }

    /** 仅允许当前目录的直接子项名（拒绝路径穿越/分隔符） */
    private static boolean isSafeChildName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && !name.contains("/") && !name.contains("\\");
    }

    /** 发送 ZIP 打包响应头。HTTP/1.1 采用 chunked（zip 按文件逐个压缩、边压边传）；HTTP/1.0 退化为连接关闭收尾 */
    private static void sendZipHeaders(OutputStream os, boolean http11) {
        PrintStream ps = new PrintStream(os);
        if (http11) {
            ps.print("HTTP/1.1 200 OK\r\n");
            ps.print("Transfer-Encoding: chunked\r\n");
            ps.print("Connection: close\r\n");
        } else {
            ps.print("HTTP/1.0 200 OK\r\n");
            ps.print("Connection: close\r\n");
        }
        ps.print("Content-Type: application/zip\r\n");
        ps.print("Content-Disposition: attachment; filename=\"download.zip\"\r\n");
        ps.print("Server: Device2Device\r\n");
        ps.print("Access-Control-Allow-Origin: *\r\n");
        ps.print("\r\n");
        ps.flush();
    }

    /** 文件本身已是压缩/编码格式：打包时跳过重复 deflate，原样逐个写入条目（省 CPU，体积基本不变） */
    private static boolean isAlreadyCompressed(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            // 图片
            case "jpg": case "jpeg": case "png": case "gif": case "webp":
            case "bmp": case "tif": case "tiff": case "heic": case "heif":
            case "ico": case "svg": case "raw": case "psd":
            // 视频
            case "mp4": case "m4v": case "mov": case "mkv": case "webm":
            case "avi": case "3gp": case "3g2": case "wmv": case "flv":
            case "ts": case "m2ts": case "mpg": case "mpeg":
            // 音频
            case "mp3": case "m4a": case "aac": case "flac": case "ogg":
            case "oga": case "opus": case "wav": case "aif": case "aiff":
            case "amr": case "wma": case "mid": case "midi": case "pcm":
            // 压缩包 / 安装包 / 常见打包文档
            case "zip": case "rar": case "7z": case "gz": case "tgz":
            case "bz2": case "xz": case "zst": case "cab": case "apk":
            case "jar": case "pdf": case "docx": case "xlsx": case "pptx":
            case "odt": case "ods": case "odp":
                return true;
            default:
                return false;
        }
    }

    /** filesystem 模式：所选子项（文件/夹，目录递归）逐个打包为 zip，单个文件失败仅跳过而不破坏整包 */
    private void sendFileZip(Socket client, File dir, List<String> names, boolean http11) throws Exception {
        OutputStream raw = client.getOutputStream();
        sendZipHeaders(raw, http11);
        ChunkedOutputStream chunked = http11 ? new ChunkedOutputStream(raw) : null;
        // chunked 自带缓冲；HTTP/1.0 用 128KB 缓冲批量发（满即自动写出，保持边压边传）
        OutputStream body = (chunked != null) ? chunked : new BufferedOutputStream(raw, 128 * 1024);
        ZipOutputStream zos = new ZipOutputStream(body);
        int ok = 0;
        for (String name : names) {
            File f = new File(dir, name);
            if (!f.exists()) continue;
            try {
                if (zipFileTo(f, f.getName(), zos, 0)) ok++;
            } catch (Exception e) {
                Log.w(TAG, "zip skip '" + name + "': " + e.getMessage());
            }
            body.flush(); // 每个顶层条目完成后立即发送，避免数据滞留在缓冲中
        }
        zos.finish();
        body.flush();
        if (chunked != null) chunked.finish();
        raw.flush();
        Log.i(TAG, "zip(fs) done: " + ok + " top item(s) packed");
    }

    private boolean zipFileTo(File file, String base, ZipOutputStream zos, int depth) {
        if (file == null || depth > 32) return false;
        if (file.isDirectory()) {
            String folder = base.endsWith("/") ? base : base + "/";
            try {
                ZipEntry de = new ZipEntry(folder);
                de.setTime(file.lastModified());
                zos.putNextEntry(de);
                zos.closeEntry();
            } catch (Exception e) {
                Log.w(TAG, "zip skip dir '" + base + "': " + e.getMessage());
                return false;
            }
            File[] children = file.listFiles();
            if (children == null) return false;
            boolean any = false;
            for (File child : children) {
                if (child.getName().startsWith(".")) continue; // 与网页列表一致：跳过隐藏项
                try {
                    if (zipFileTo(child, folder + child.getName(), zos, depth + 1)) any = true;
                } catch (Exception e) {
                    Log.w(TAG, "zip skip child '" + child.getName() + "': " + e.getMessage());
                }
            }
            return any;
        }

        // 每个文件独立压缩项：已压缩媒体不再二次 deflate
        zos.setLevel(isAlreadyCompressed(file.getName())
                ? Deflater.NO_COMPRESSION : Deflater.DEFAULT_COMPRESSION);
        ZipEntry e = new ZipEntry(base);
        long t = file.lastModified();
        if (t > 0) e.setTime(t);
        try {
            zos.putNextEntry(e);
        } catch (Exception ex) {
            Log.w(TAG, "zip entry error '" + base + "': " + ex.getMessage());
            return false;
        }
        boolean truncated = false;
        byte[] buf = new byte[128 * 1024];
        try (FileInputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                zos.write(buf, 0, n);
            }
        } catch (Exception ex) {
            truncated = true; // 单文件读取中断：记录并收尾本条目，保证 zip 其余文件完整可解压
            Log.w(TAG, "zip read error '" + base + "': " + ex.getMessage());
        }
        try {
            zos.closeEntry();
        } catch (Exception ex) {
            Log.w(TAG, "zip close entry error '" + base + "': " + ex.getMessage());
            return false;
        }
        return !truncated;
    }

    /** SAF/DocumentFile 模式：同 sendFileZip，但逐项经 ContentProvider 读取，同样逐文件独立压缩 */
    private void sendDocZip(Socket client, DocumentFile dir, List<String> names, boolean http11) throws Exception {
        OutputStream raw = client.getOutputStream();
        sendZipHeaders(raw, http11);
        ChunkedOutputStream chunked = http11 ? new ChunkedOutputStream(raw) : null;
        OutputStream body = (chunked != null) ? chunked : new BufferedOutputStream(raw, 128 * 1024);
        ZipOutputStream zos = new ZipOutputStream(body);
        int ok = 0;
        for (String name : names) {
            try {
                DocumentFile f = dir.findFile(name);
                if (f != null && f.exists() && zipDocTo(f, f.getName(), zos, 0)) ok++;
            } catch (Exception e) {
                Log.w(TAG, "zip skip '" + name + "': " + e.getMessage());
            }
            body.flush(); // 每个顶层条目完成后立即发送，避免数据滞留在缓冲中
        }
        zos.finish();
        body.flush();
        if (chunked != null) chunked.finish();
        raw.flush();
        Log.i(TAG, "zip(saf) done: " + ok + " top item(s) packed");
    }

    private boolean zipDocTo(DocumentFile df, String base, ZipOutputStream zos, int depth) {
        if (df == null || depth > 32) return false;
        if (df.isDirectory()) {
            String folder = base.endsWith("/") ? base : base + "/";
            try {
                ZipEntry de = new ZipEntry(folder);
                long t = df.lastModified();
                if (t > 0) de.setTime(t);
                zos.putNextEntry(de);
                zos.closeEntry();
            } catch (Exception e) {
                Log.w(TAG, "zip skip dir '" + base + "': " + e.getMessage());
                return false;
            }
            DocumentFile[] children = df.listFiles();
            if (children == null) return false;
            boolean any = false;
            for (DocumentFile child : children) {
                String cname = child.getName();
                if (cname == null || cname.startsWith(".")) continue;
                try {
                    if (zipDocTo(child, folder + cname, zos, depth + 1)) any = true;
                } catch (Exception e) {
                    Log.w(TAG, "zip skip child '" + cname + "': " + e.getMessage());
                }
            }
            return any;
        }

        zos.setLevel(isAlreadyCompressed(base)
                ? Deflater.NO_COMPRESSION : Deflater.DEFAULT_COMPRESSION);
        ZipEntry e = new ZipEntry(base);
        long t = df.lastModified();
        if (t > 0) e.setTime(t);
        try {
            zos.putNextEntry(e);
        } catch (Exception ex) {
            Log.w(TAG, "zip entry error '" + base + "': " + ex.getMessage());
            return false;
        }
        boolean truncated = false;
        byte[] buf = new byte[128 * 1024];
        try (InputStream in = context.getContentResolver().openInputStream(df.getUri())) {
            if (in != null) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    zos.write(buf, 0, n);
                }
            } else {
                truncated = true;
            }
        } catch (Exception ex) {
            truncated = true; // ContentProvider 单文件读取失败不影响其余文件
            Log.w(TAG, "zip read error '" + base + "': " + ex.getMessage());
        }
        try {
            zos.closeEntry();
        } catch (Exception ex) {
            Log.w(TAG, "zip close entry error '" + base + "': " + ex.getMessage());
            return false;
        }
        return !truncated;
    }

    /**
     * HTTP/1.1 Transfer-Encoding: chunked 输出流。
     * 自带 32KB 缓冲，写满自动发一个 chunk；flush()/finish() 显式发出残余并写终止块，
     * 使浏览器按块持续接收 zip 数据，而不是等到连接关闭才落盘。
     */
    private static final class ChunkedOutputStream extends OutputStream {
        private static final int BUF_SIZE = 32 * 1024;
        private static final byte[] CRLF = new byte[]{'\r', '\n'};
        private final OutputStream out;
        private final byte[] buf = new byte[BUF_SIZE];
        private int count;

        ChunkedOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            if (count == BUF_SIZE) flushChunk();
            buf[count++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (count == BUF_SIZE) flushChunk();
                int n = Math.min(len, BUF_SIZE - count);
                System.arraycopy(b, off, buf, count, n);
                count += n;
                off += n;
                len -= n;
            }
        }

        @Override
        public void flush() throws IOException {
            flushChunk();
        }

        /** 发送剩余缓冲并写 chunked 终止块，完成响应体 */
        void finish() throws IOException {
            flushChunk();
            out.write(new byte[]{'0', '\r', '\n', '\r', '\n'});
            out.flush();
        }

        private void flushChunk() throws IOException {
            if (count == 0) return;
            out.write((Integer.toHexString(count) + "\r\n").getBytes("US-ASCII"));
            out.write(buf, 0, count);
            out.write(CRLF);
            out.flush();
            count = 0;
        }
    }

    // ==================== HTML 构建工具方法 ====================

    /** 构建 HTML head、样式和面包屑导航；page 为当前页码，随排序链接保留以便换列/换向后仍停留在本页 */
    private StringBuilder buildHtmlHead(String requestPath, String sortKey, boolean asc, int page) {
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
        // 完整传输成功（下载/完整打开）过的文件以绿色标记（服务端 completedPaths 判定，点击但未下完不算）
        html.append(".file-name a.done{color:#2e7d32;}");
        // 下载打包工具条：sticky 吸顶；页首停靠态透明融入排版，滚过静态位后切 .float 悬浮白条吸顶
        // 纵向 padding/高度两态保持一致，避免吸顶瞬间布局跳变；悬浮用近不透明纯色而非 backdrop-filter，防滚动时滤镜重绘闪烁
        html.append(".selbar{position:sticky;top:0;z-index:60;display:flex;align-items:center;gap:12px;flex-wrap:wrap;padding:8px 12px;margin-bottom:10px;border:1px solid transparent;border-radius:10px;background:rgba(255,255,255,0);font-size:13px;color:#555;box-shadow:0 0 0 rgba(0,0,0,0);transition:background-color .3s ease,border-color .3s ease,box-shadow .3s ease;}");
        html.append(".selbar.float{background:rgba(255,255,255,.98);border-color:#e3ecf5;box-shadow:0 2px 10px rgba(0,0,0,.08);}");
        // “全选本目录”入口始终显示在工具条右侧（顶部 list-head 的全选框滚动后不可见）
        html.append(".selbar .bar-selall{display:flex;align-items:center;gap:6px;margin-left:auto;cursor:pointer;user-select:none;white-space:nowrap;}");
        html.append(".selbar .cnt{color:#1976d2;font-weight:600;}");
        html.append(".selbar button{padding:6px 14px;font-size:13px;border:1px solid #1976d2;background:#1976d2;color:#fff;border-radius:16px;cursor:pointer;}");
        html.append(".selbar button:disabled{background:#b9cfe1;border-color:#b9cfe1;cursor:not-allowed;}");
        html.append(".chk{width:30px;margin-right:6px;flex-shrink:0;display:flex;align-items:center;}");
        html.append("input.cbox{width:15px;height:15px;accent-color:#1976d2;cursor:pointer;flex-shrink:0;}");
        html.append(".file-list{list-style:none;padding:0;margin:0;}");
        html.append(".file-item{display:flex;align-items:center;padding:10px 12px;border-bottom:1px solid #f0f0f0;transition:background 0.15s;}");
        html.append(".file-item:hover{background:#f8f9fa;}");
        html.append(".file-item:last-child{border-bottom:none;}");
        html.append(".file-icon{width:24px;margin-right:12px;font-size:18px;text-align:center;flex-shrink:0;}");
        // 图片/视频行缩略图：与 emoji 图标同尺寸、居中裁剪显示，行高/列宽保持一致
        html.append(".file-icon .thumb{width:24px;height:24px;border-radius:4px;object-fit:cover;background:#e9edf2;display:inline-block;vertical-align:middle;}");
        html.append(".file-name{flex:1;font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0;}");
        html.append(".file-name a{color:#333;text-decoration:none;}");
        html.append(".file-name a:hover{color:#1976d2;}");
        html.append(".file-size{font-size:12px;color:#999;margin-left:16px;white-space:nowrap;flex-shrink:0;width:88px;text-align:right;}");
        html.append(".file-date{font-size:12px;color:#999;margin-left:12px;white-space:nowrap;flex-shrink:0;width:136px;text-align:right;}");
        // 大目录分页条（超过 PAGE_SIZE 时在列表底部出现）
        // 底部翻页条：sticky 吸底常驻（滚动浏览时悬浮在视口底部）；滚到最底部原位置翻页露出时 .settled 去悬浮样式回归普通条
        // 结构两行：上排 .pg-nav 页码(nowrap 单行，溢出可横向滚动)、下排 .pg-info 页次/总数信息行，各自独立成行不折行
        html.append(".pager{position:sticky;bottom:0;z-index:55;display:flex;flex-direction:column;align-items:center;gap:4px;margin-top:6px;padding:10px 10px 12px;font-size:13px;color:#555;user-select:none;background:rgba(255,255,255,.92);backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);border-top:1px solid #e3ecf5;border-radius:14px 14px 0 0;box-shadow:0 -2px 12px rgba(0,0,0,.06);transition:background .25s ease,border-color .25s ease,box-shadow .25s ease;}");
        html.append(".pager.settled{background:transparent;box-shadow:none;border-top-color:transparent;}");
        // .pg-nav 为横向滚动视口；内层 .pg-in 宽度自适应内容：不溢出时 margin:auto 整体居中，溢出时才出滚动条且可从第 1 页滚起（两侧不裁切）
        html.append(".pg-nav{width:100%;overflow-x:auto;scrollbar-width:thin;scrollbar-color:rgba(25,118,210,.35) transparent;}");
        html.append(".pg-nav::-webkit-scrollbar{height:5px;}");
        html.append(".pg-nav::-webkit-scrollbar-thumb{background:rgba(25,118,210,.35);border-radius:3px;}");
        html.append(".pg-in{display:flex;align-items:center;justify-content:center;gap:6px;flex-wrap:nowrap;white-space:nowrap;width:max-content;margin:0 auto;padding:4px 0 6px;}");
        html.append(".pg-in a,.pg-in .dead,.pg-in .cur{display:inline-block;min-width:30px;padding:6px 10px;text-align:center;border:1px solid #d9e2ec;border-radius:8px;color:#333;text-decoration:none;background:#fff;box-sizing:border-box;flex-shrink:0;}");
        html.append(".pg-in a:hover{background:#e8f1fa;color:#1976d2;border-color:#1976d2;}");
        html.append(".pg-in .cur{background:#1976d2;border-color:#1976d2;color:#fff;cursor:default;}");
        html.append(".pg-in .dead{opacity:.4;pointer-events:none;}");
        html.append(".pg-in .gap{color:#999;padding:6px 2px;flex-shrink:0;}");
        html.append(".pg-info{font-size:12px;color:#777;line-height:1.4;white-space:nowrap;}");
        // 窄屏适度压缩页码按钮尺寸，尽量在一行内自适应放下更多页码
        html.append("@media (max-width:520px){.pg-in{gap:4px;}.pg-in a,.pg-in .dead,.pg-in .cur{min-width:26px;padding:5px 7px;font-size:12px;}}");
        // 文件列表可排序列头
        html.append(".list-head{display:flex;align-items:center;padding:0 12px 8px;border-bottom:2px solid #e4e4e4;font-size:12px;color:#777;user-select:none;}");
        html.append(".lh{padding:6px 0;cursor:pointer;white-space:nowrap;color:#777;text-decoration:none;}");
        html.append(".lh:hover{color:#1976d2;}");
        html.append(".lh .lh-a{display:inline-block;min-width:10px;margin-left:3px;font-size:9px;line-height:1;color:#1976d2;}");
        html.append(".lh-icon{width:24px;margin-right:12px;flex-shrink:0;}");
        html.append(".lh-name{flex:1;min-width:0;}");
        html.append(".lh-size{width:88px;margin-left:16px;text-align:right;}");
        html.append(".lh-date{width:136px;margin-left:12px;text-align:right;}");
        // 窄屏压缩次要列，把空间让给文件(夹)（name 已 flex:1，会自动吃下剩余宽度）
        html.append("@media (max-width:760px){.file-size,.lh-size{width:76px;}.file-date,.lh-date{width:118px;}}");
        // 手机竖屏：日期列移到每行第二行显示（flex 换行）；表头同步换行，保留“修改日期”排序入口
        html.append("@media (max-width:580px){.list-head{flex-wrap:wrap;}.lh-name{font-size:14px;}.lh-date{width:auto;flex-basis:100%;flex-shrink:1;text-align:left;margin-left:72px;margin-top:2px;font-size:11px;}.lh-size{font-size:11px;}.file-item{flex-wrap:wrap;}.file-size{width:64px;font-size:11px;}.file-date{width:auto;flex-basis:100%;flex-shrink:1;text-align:left;margin-left:72px;margin-top:2px;font-size:11px;}}");
        html.append("@media (max-width:420px){.file-size,.lh-size{width:56px;}}");
        html.append(".footer{text-align:center;font-size:12px;color:#999;margin-top:16px;padding-top:12px;border-top:1px solid #eee;}");
        // 图片查看器样式
        html.append(".viewer,#viewer{display:none;position:fixed;inset:0;z-index:9999;background:rgba(0,0,0,.94);flex-direction:column;align-items:center;justify-content:center;touch-action:manipulation;overflow:hidden;}");
        html.append(".viewer.open,#viewer.open{display:flex;}");
        html.append(".viewer-stage{flex:1;min-height:0;width:100%;display:flex;align-items:center;justify-content:center;margin:0;padding:0;position:relative;}");
        html.append(".viewer-stage img{display:block;max-width:92vw;max-height:calc(100vh - 150px);width:auto;height:auto;object-fit:contain;border-radius:6px;box-shadow:0 6px 30px rgba(0,0,0,.65);}");
        html.append(".viewer-spin{position:fixed;top:50%;left:50%;width:44px;height:44px;margin:-22px 0 0 -22px;border:4px solid rgba(255,255,255,.25);border-top-color:#fff;border-radius:50%;animation:d2dspin .8s linear infinite;display:none;}");
        html.append("@keyframes d2dspin{to{transform:rotate(360deg);}}");
        html.append(".viewer-msg{position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);text-align:center;color:#fff;font-size:15px;background:rgba(0,0,0,.6);padding:14px 18px;border-radius:12px;display:none;z-index:10001;}");
        html.append(".viewer-msg button{margin:4px 6px;padding:8px 18px;border-radius:18px;border:1px solid #8fd0ff;background:none;color:#8fd0ff;font-size:14px;}");
        html.append(".viewer-msg button:active{background:#8fd0ff;color:#062;}");
        html.append(".viewer-btn{position:fixed;z-index:10000;display:flex;align-items:center;justify-content:center;background:rgba(30,30,30,.6);color:#fff;border:1px solid rgba(255,255,255,.4);border-radius:50%;width:52px;height:52px;font-size:34px;font-weight:bold;line-height:1;cursor:pointer;padding:0;user-select:none;box-shadow:0 2px 12px rgba(0,0,0,.45);} ");
        html.append(".viewer-btn:hover{background:rgba(100,100,100,.75);}");
        html.append(".viewer-btn.dim{opacity:.28;pointer-events:none;}");
        html.append(".viewer-close{top:16px;right:16px;font-size:24px;}");
        html.append(".viewer-prev{left:10px;top:50%;transform:translateY(-50%);}");
        html.append(".viewer-next{right:10px;top:50%;transform:translateY(-50%);}");
        html.append(".viewer-bar{position:fixed;left:0;right:0;bottom:0;display:flex;flex-wrap:wrap;align-items:center;justify-content:center;gap:10px 18px;padding:14px 76px;color:#ddd;font-size:14px;background:linear-gradient(transparent,rgba(0,0,0,.85));box-sizing:border-box;}");
        html.append(".viewer-bar #viewer-name{max-width:60vw;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#eee;}");
        html.append(".viewer-bar a#viewer-dl{color:#8fd0ff;text-decoration:none;padding:6px 16px;border:1px solid #8fd0ff;border-radius:18px;}");
        html.append(".viewer-bar a#viewer-dl:hover{background:#8fd0ff;color:#062;}");
        html.append("</style></head><body>");
        html.append("<div class=\"container\">");
        html.append("<h1>📂 Device2Device File Browser</h1>");
        html.append("<div class=\"breadcrumb\">").append(breadcrumb).append("</div>");
        // 勾选打包工具条：sticky 吸顶常驻（含打包下载按钮与选中计数）；勾选后按钮由 JS 激活
        html.append("<div class=\"selbar\" id=\"zipbar\"><button type=\"button\" id=\"zip-btn\" disabled>📦 下载</button>");
        html.append("<span class=\"cnt\" id=\"sel-count\">已选 0 项</span>");
        html.append("<label class=\"bar-selall\" title=\"全选本目录\"><input type=\"checkbox\" id=\"sel-all2\" class=\"cbox\"><span>全选</span></label></div>");
        // 可排序列头：服务端按 sort/dir 参数全局排序（目录恒置顶），点列头整页重载，翻页沿用同一排序
        html.append("<div class=\"list-head\">");
        html.append("<span class=\"chk\"></span>"); // 仅作列宽占位(与行内复选框同宽)；全选入口统一在下载工具栏(.bar-selall)，此处不重复放置
        html.append("<span class=\"lh-icon\"></span>");
        html.append(sortHeaderLink(requestPath, sortKey, asc, page, "name", "文件(夹)"));
        html.append(sortHeaderLink(requestPath, sortKey, asc, page, "size", "大小"));
        html.append(sortHeaderLink(requestPath, sortKey, asc, page, "date", "修改日期"));
        html.append("</div>");
        html.append("<ul class=\"file-list\">");
        return html;
    }

    /** 构建上级目录链接项 */
    private String buildParentLink(String parentPath) {
        return "<li class=\"file-item\" data-type=\"up\">" +
                "<span class=\"chk\"></span>" +
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

    /** 构建 HTML 尾部（totalCount 为目录全量子项数；pagerHtml 为分页条，单页时传空串保持原观感） */
    private String buildHtmlFoot(int totalCount, String pagerHtml) {
        // 列头排序已改为服务端全局排序（见 sortHeaderLink / parseSortKey），此处不再放置页内排序脚本
        // 勾选打包：行内复选框联动全选、计数与 zip 按钮；打包请求带 zip=name 可重复参数
        String selectionScript =
                "<script>"
                        + "(function(){"
                        + "var ul=document.querySelector('ul.file-list');"
                        + "var selAll2=document.getElementById('sel-all2');"
                        + "var cnt=document.getElementById('sel-count');"
                        + "var bar=document.getElementById('zipbar');"
                        + "var btn=document.getElementById('zip-btn');"
                        + "if(!ul||!bar||!btn)return;"
                        + "function boxes(){var lis=ul.querySelectorAll('li[data-type=\"file\"],li[data-type=\"dir\"]');"
                        + "var out=[];for(var i=0;i<lis.length;i++){var c=lis[i].querySelector('input.cbox');if(c)out.push(c);}return out;}"
                        + "function stopY(){var s=0,e=bar;while(e){s+=e.offsetTop;e=e.offsetParent;}return s;}" // 工具条静态(文档流)位置；滚动越过该处时 sticky 吸顶并切换 .float
                        + "function refresh(){var cs=boxes(),n=0;for(var i=0;i<cs.length;i++)if(cs[i].checked)n++;"
                        + "var allOn=cs.length>0&&n===cs.length;"
                        + "if(selAll2)selAll2.checked=allOn;"
                        + "cnt.textContent='已选 '+n+' 项';"
                        + "var y=window.pageYOffset||document.documentElement.scrollTop||0;"
                        + "bar.classList.toggle('float',y>=2&&y>=stopY());" // 停靠↔悬浮切换经 CSS transition 平滑过渡；class 结果不变时无操作，不抖闪
                        + "btn.disabled=(n===0);}"
                        + "window.addEventListener('scroll',refresh,{passive:true});"
                        + "window.addEventListener('resize',refresh,{passive:true});"
                        + "function onAllChange(){var on=this.checked,cs=boxes();"
                        + "for(var i=0;i<cs.length;i++)cs[i].checked=on;refresh();}"
                        + "if(selAll2)selAll2.addEventListener('change',onAllChange);"
                        + "ul.addEventListener('change',function(e){var t=e.target;"
                        + "if(t&&t.className&&String(t.className).indexOf('cbox')>=0)refresh();});"
                        + "btn.addEventListener('click',function(){"
                        + "var cs=boxes(),q='',first=true;"
                        + "for(var i=0;i<cs.length;i++){if(!cs[i].checked)continue;"
                        + "var n=cs[i].getAttribute('data-name');if(!n)continue;"
                        + "q+=(first?'':'&')+'zip='+encodeURIComponent(n);first=false;}"
                        + "if(!q)return;"
                        + "var p=location.pathname||'/';"
                        + "location.href=p+(p.indexOf('?')>=0?'&':'?')+q;"
                        + "});"
                        + "refresh();"
                        + "})();"
                        + "</script>";
        // 底部翻页条吸底悬浮：接近文档最底（原位置翻页条已露出）时 .settled 收起悬浮高亮，回归普通翻页条
        String pagerScript =
                "<script>"
                        + "(function(){"
                        + "var pg=document.querySelector('div.pager');"
                        + "if(!pg)return;"
                        + "function tick(){var de=document.documentElement,db=document.body;"
                        + "var y=window.pageYOffset||de.scrollTop||0;"
                        + "var h=Math.max(de?de.scrollHeight:0,db?db.scrollHeight:0);"
                        + "var vh=window.innerHeight||de.clientHeight||0;"
                        + "pg.classList.toggle('settled',(h-(y+vh))<=8);}"
                        + "window.addEventListener('scroll',tick,{passive:true});"
                        + "window.addEventListener('resize',tick);"
                        + "tick();"
                        + "})();"
                        + "</script>";
        return "</ul>" +
                (pagerHtml == null ? "" : pagerHtml) +
                "<div class=\"footer\">Device2Device HTTP Server · Port " + port +
                " · " + totalCount + " 项</div>" +
                "</div>" + selectionScript + pagerScript + buildImageViewerHtml() + "</body></html>";
    }

    // ==================== 大目录分页 ====================

    /** 超过该数量的子项即分页渲染（服务端每页仅返回一段，兼顾渲染/缩略图性能） */
    private static final int PAGE_SIZE = 100;

    /** 目录页请求中解析 pg=N（缺省/非法一律回第 1 页） */
    private static int parsePage(String query) {
        if (query == null) return 1;
        for (String part : query.split("&")) {
            if (part != null && part.startsWith("pg=")) {
                try {
                    int v = Integer.parseInt(part.substring(3));
                    if (v > 0) return v;
                } catch (NumberFormatException ignored) {}
            }
        }
        return 1;
    }

    /** 目录页请求中解析排序列键 sort=name/size/date（非法/缺省回 name） */
    private static String parseSortKey(String query) {
        if (query == null) return "name";
        for (String part : query.split("&")) {
            if (part != null && part.startsWith("sort=")) {
                String v = part.substring(5);
                if (v.equals("name") || v.equals("size") || v.equals("date")) return v;
            }
        }
        return "name";
    }

    /** 目录页请求中解析方向：d=1 降序，d=0/缺省 升序；取值定长单字符(0/1)，不兼容旧写法 */
    private static boolean parseSortAsc(String query) {
        if (query == null) return true;
        for (String part : query.split("&")) {
            if (part != null && part.startsWith("d=") && part.length() > 2) {
                return !part.substring(2).equals("1");
            }
        }
        return true;
    }

    /** SAF/文档树条目按 列键+方向 全局排序（目录恒置顶） */
    private static void sortDocEntries(List<DocEntry> list, String key, boolean asc) {
        Collections.sort(list, (a, b) -> compareListed(a.isDir, a.name, a.size, a.lastModified,
                b.isDir, b.name, b.size, b.lastModified, key, asc));
    }

    /** fs 条目按 列键+方向 全局排序（目录恒置顶） */
    private static void sortFileEntries(List<File> list, String key, boolean asc) {
        Collections.sort(list, (a, b) -> compareListed(a.isDirectory(), a.getName(), a.length(), a.lastModified(),
                b.isDirectory(), b.getName(), b.length(), b.lastModified(), key, asc));
    }

    /** 目录恒在文件前；组内按 key(name/size/date) 及 asc 比较，值相同回退名称升序保证跨页稳定 */
    private static int compareListed(boolean aDir, String aName, long aSize, long aTime,
                                     boolean bDir, String bName, long bSize, long bTime,
                                     String key, boolean asc) {
        if (aDir != bDir) return aDir ? -1 : 1;
        int c;
        if (key.equals("size")) c = Long.compare(aSize, bSize);
        else if (key.equals("date")) c = Long.compare(aTime, bTime);
        else c = aName.compareToIgnoreCase(bName);
        if (!asc) c = -c;
        if (c == 0) c = aName.compareToIgnoreCase(bName);
        return c;
    }

    /** 请求路径按段 URL 编码（保留 / 分隔符），用于生成翻页链接 */
    private static String encodeUrlPath(String p) {
        if (p == null || p.isEmpty() || p.equals("/")) return "/";
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("/")) {
            if (seg.isEmpty()) continue;
            sb.append('/');
            try {
                sb.append(java.net.URLEncoder.encode(seg, "UTF-8"));
            } catch (Exception e) {
                sb.append(seg);
            }
        }
        return sb.toString();
    }

    /**
     * 浏览状态 Cookie（d2d）：排序偏好/页码不通过 URL 长参数传递，改由服务端 Set-Cookie 记忆。
     * 列头排序/翻页链接仍须短时携带 ?sort/?d/?pg 来表达“动作”，服务端解析后在响应中写回 Cookie，
     * 并 302 收敛到无参路径 —— 地址栏/收藏/刷新均为最短 URL，而排序、页码状态不丢。
     * Cookie 值格式：sort|asc(0/1)|URL编码目录路径|页码。
     * 排序(sort/d)为全局偏好(跨目录生效)；页码仅在“Cookie 记录的目录 == 当前请求目录”时恢复，
     * 避免把某目录的页码串到其它目录；切目录自然回到第 1 页。
     */
    private static final String BROWSE_COOKIE_NAME = "d2d";
    private static final long BROWSE_COOKIE_MAX_AGE = 31536000L; // 1 年（重启/关机不丢偏好）

    /** 从 d2d Cookie 解析出的浏览偏好 */
    private static final class BrowsePrefs {
        String sortKey = "name";
        boolean asc = true;
        int page = 1;
        String path; // Cookie 记录的目录（decoded）；null 表示无有效 Cookie
    }

    /** 单次目录页请求解析后的浏览状态 */
    private static final class BrowseState {
        String sortKey = "name";
        boolean asc = true;
        int page = 1;
        boolean action; // URL 是否显式携带 sort/d/pg（动作链接：列头换排序 / 翻页）
        BrowsePrefs prefs = new BrowsePrefs();
    }

    /** 从 Cookie 请求头中取指定名字的取值（首个匹配段）；无则 null */
    private static String parseCookieValue(String cookieHeader, String name) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(name))
                return part.substring(eq + 1).trim();
        }
        return null;
    }

    /** query 中是否显式携带 name= 参数 */
    private static boolean hasBrowseParam(String query, String name) {
        if (query == null) return false;
        for (String part : query.split("&")) {
            if (part != null && part.startsWith(name + "=")) return true;
        }
        return false;
    }

    /** 解析 d2d Cookie；缺失/字段非法一律回默认 */
    private static BrowsePrefs parseBrowsePrefs(String cookieHeader) {
        BrowsePrefs p = new BrowsePrefs();
        String raw = parseCookieValue(cookieHeader, BROWSE_COOKIE_NAME);
        if (raw == null) return p;
        String[] f = raw.split("\\|"); // 段内路径已 URL 编码（不含原始 '|'），可安全分段
        if (f.length < 4) return p;
        if (f[0].equals("name") || f[0].equals("size") || f[0].equals("date")) p.sortKey = f[0];
        p.asc = !"1".equals(f[1]);
        try {
            p.path = java.net.URLDecoder.decode(f[2], "UTF-8");
        } catch (Exception e) {
            return p; // 路径段异常 → 视为无有效 Cookie，仅影响“恢复页码”，不影响渲染
        }
        try {
            int pg = Integer.parseInt(f[3]);
            if (pg > 0) p.page = pg;
        } catch (NumberFormatException ignored) {}
        return p;
    }

    /** 生成 Set-Cookie 响应头取值（Path=/ 全局生效、持久一年、SameSite=Lax） */
    private static String buildSetCookie(String sortKey, boolean asc, String path, int page) {
        String enc;
        try {
            enc = java.net.URLEncoder.encode(path == null || path.isEmpty() ? "/" : path, "UTF-8");
        } catch (Exception e) {
            enc = "/";
        }
        return BROWSE_COOKIE_NAME + "=" + sortKey + "|" + (asc ? "0" : "1") + "|" + enc + "|" + page
                + "; Path=/; Max-Age=" + BROWSE_COOKIE_MAX_AGE + "; SameSite=Lax";
    }

    /** 解析目录页浏览状态：URL 显式参数（动作）优先，其次用 Cookie 记忆；页码仅在目录一致时恢复 */
    private BrowseState resolveBrowseState(String requestPath, String query) {
        BrowseState st = new BrowseState();
        BrowsePrefs prefs = parseBrowsePrefs(reqCookie.get());
        st.prefs = prefs;
        boolean hasSort = hasBrowseParam(query, "sort");
        boolean hasD = hasBrowseParam(query, "d");
        boolean hasPg = hasBrowseParam(query, "pg");
        st.action = hasSort || hasD || hasPg;
        if (hasSort) st.sortKey = parseSortKey(query);
        else if (prefs.path != null) st.sortKey = prefs.sortKey;
        if (hasD) st.asc = parseSortAsc(query);
        else if (prefs.path != null) st.asc = prefs.asc;
        if (hasPg) st.page = parsePage(query);
        else if (prefs.path != null && prefs.path.equals(requestPath)) st.page = prefs.page;
        return st;
    }

    /**
     * 决定是否回写浏览状态 Cookie：
     * 动作请求（列头排序/翻页）一律回写；纯路径请求仅当 目录切换/首访/状态变化 时回写，
     * 状态相同的常规刷新不重复下发。返回 null 表示无需写。
     */
    private String browseSetCookieIfNeeded(String requestPath, BrowseState bs,
                                           String sortKey, boolean asc, int page) {
        BrowsePrefs prefs = bs.prefs;
        boolean samePath = prefs.path != null && prefs.path.equals(requestPath);
        if (!bs.action && samePath && prefs.sortKey.equals(sortKey) && prefs.asc == asc && prefs.page == page) {
            return null;
        }
        return buildSetCookie(sortKey, asc, requestPath, page);
    }

    /** 302 跳回无参路径（动作完成后收敛 URL），可选附带 Set-Cookie */
    private static void sendRedirect(OutputStream os, String location, boolean keepAlive, String setCookie) {
        PrintStream ps = new PrintStream(os);
        ps.print("HTTP/1.1 302 Found\r\n");
        ps.print("Location: " + location + "\r\n");
        if (setCookie != null && !setCookie.isEmpty()) {
            ps.print("Set-Cookie: " + setCookie + "\r\n");
        }
        ps.print("Content-Length: 0\r\n");
        ps.print("Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n");
        ps.print("Server: Device2Device\r\n\r\n");
        ps.flush();
    }

    /** 列表底部翻页条：上排页码(单行)，下排“第 x / y 页 · 共 n 项”；pagerQuery 为“路径 + sort/d 参数”(不含 pg)；单页返回空串 */
    private static String buildPagerHtml(String pagerQuery, int page, int pageCount,
                                         int start, int end, int total) {
        if (pageCount <= 1) return "";
        StringBuilder s = new StringBuilder("<div class=\"pager\"><div class=\"pg-nav\"><div class=\"pg-in\">");
        if (page > 1) {
            s.append("<a href=\"").append(pagerQuery).append("&pg=").append(page - 1).append("\">‹ 上一页</a>");
        } else {
            s.append("<span class=\"dead\">‹ 上一页</span>");
        }
        // 当前页两侧各显示 2 个近邻页码(其余用省略号 + 首/末页)，控制按钮总数保持精简
        int lo = Math.max(1, page - 2);
        int hi = Math.min(pageCount, page + 2);
        if (lo > 1) {
            pagerLink(s, pagerQuery, 1, page);
            if (lo > 2) s.append("<span class=\"gap\">…</span>");
        }
        for (int i = lo; i <= hi; i++) pagerLink(s, pagerQuery, i, page);
        if (hi < pageCount) {
            if (hi < pageCount - 1) s.append("<span class=\"gap\">…</span>");
            pagerLink(s, pagerQuery, pageCount, page);
        }
        if (page < pageCount) {
            s.append("<a href=\"").append(pagerQuery).append("&pg=").append(page + 1).append("\">下一页 ›</a>");
        } else {
            s.append("<span class=\"dead\">下一页 ›</span>");
        }
        s.append("</div></div><div class=\"pg-info\">第 ").append(page).append(" / ").append(pageCount)
                .append(" 页 · 共 ").append(total).append(" 项</div>");
        return s.append("</div>").toString();
    }

    private static void pagerLink(StringBuilder s, String base, int n, int cur) {
        if (n == cur) {
            s.append("<span class=\"cur\">").append(n).append("</span>");
        } else {
            s.append("<a href=\"").append(base).append("&pg=").append(n)
                    .append("\">").append(n).append("</a>");
        }
    }

    /** 列头排序链接：同列再点一次切换升降序，换列默认升序；携带当前 pg 使换列/换向后仍停留在同一页(服务端全局排序，页内容稳定；超界自动收敛到末页) */
    private static String sortHeaderLink(String requestPath, String sortKey, boolean asc, int page,
                                         String key, String label) {
        boolean active = key.equals(sortKey);
        boolean nextAsc = active ? !asc : true;
        return "<a class=\"lh lh-" + key + "\" title=\"按" + label + "排序\" href=\""
                + encodeUrlPath(requestPath) + "?sort=" + key + "&d=" + (nextAsc ? "0" : "1")
                + (page > 1 ? "&pg=" + page : "")
                + "\">" + label + "<span class=\"lh-a\">"
                + (active ? (asc ? "▲" : "▼") : "") + "</span></a>";
    }

    // ==================== 完整下载标记 + 缩略图 ====================

    /** URL 目录路径 + 子项名 → completedPaths 键（与下载成功时记录的 decodedPath 形态一致） */
    private static String childPath(String basePath, String name) {
        String b = (basePath == null || basePath.isEmpty()) ? "/" : basePath;
        if (!b.endsWith("/")) b += "/";
        return b + name;
    }

    /** 请求是否为缩略图请求（?thumb=1） */
    private static boolean isThumbRequest(String query) {
        return query != null && query.equals("thumb=1");
    }

    /** 请求是否为查看器压缩预览请求（?preview=1） */
    private static boolean isPreviewRequest(String query) {
        return query != null && query.equals("preview=1");
    }

    /** 是否可生成缩略图（图片去掉 svg/ico；视频按常见容器，解码失败有 emoji 兜底） */
    private static boolean canThumbnail(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp"))
            return true;
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".mov") || lower.endsWith(".3gp") || lower.endsWith(".avi"))
            return true;
        return false;
    }

    /** fs 模式缩略图响应（?thumb=1） */
    private void sendFileThumb(Socket client, File file, boolean keepAlive) throws Exception {
        byte[] data = thumbOf(file.getAbsolutePath(), file, null);
        if (data == null) {
            sendError(client, 404, "No thumbnail");
            return;
        }
        writeBytesResponse(client, data, keepAlive);
    }

    /** SAF 模式缩略图响应（?thumb=1） */
    private void sendDocThumb(Socket client, DocumentFile doc, boolean keepAlive) throws Exception {
        byte[] data = thumbOf(doc.getUri().toString(), null, doc);
        if (data == null) {
            sendError(client, 404, "No thumbnail");
            return;
        }
        writeBytesResponse(client, data, keepAlive);
    }

    /** 读缓存或现场生成缩略图；并发生成限 2 路（acquire 排队），避免一页多图/视频同时解码把手机 CPU 打满 */
    private byte[] thumbOf(String key, File file, DocumentFile doc) {
        byte[] data = thumbCache.get(key);
        if (data != null) return data;
        try {
            thumbSlots.acquire();
            try {
                data = thumbCache.get(key); // 排队等待许可期间可能已被其它请求生成
                if (data == null) data = generateThumb(file, doc);
            } finally {
                thumbSlots.release();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (data != null) thumbCache.put(key, data);
        return data;
    }

    private byte[] generateThumb(File file, DocumentFile doc) {
        String name = (file != null) ? file.getName() : (doc != null ? doc.getName() : null);
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        boolean image = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp");
        boolean video = lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".mov") || lower.endsWith(".3gp") || lower.endsWith(".avi");
        try {
            if (image) return imageThumb(file, doc);
            if (video) return videoThumb(file, doc);
        } catch (Throwable t) {
            Log.w(TAG, "thumbnail fail for " + name + ": " + t.getMessage());
        }
        return null;
    }

    /** 图片缩略图：目标边长 96px（列表图标按 2~4x 高密度屏），复用通用图片转码 */
    private byte[] imageThumb(File file, DocumentFile doc) {
        return imageJpeg(file, doc, THUMB_TARGET, 72);
    }

    /**
     * 图片通用转码：两遍解码（先取边界算采样、再解局部）→ EXIF 校正(API24+) → 缩放 + 白底合成 → JPEG(quality)。
     * 列表缩略图(96px)与查看器压缩预览(≤1920px)共用；预览只传缩小图，原图仅在“下载”时整份发送。
     */
    @SuppressLint("NewApi")
    private byte[] imageJpeg(File file, DocumentFile doc, int targetPx, int quality) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = openThumb(file, doc);
        if (in == null) return null;
        try { BitmapFactory.decodeStream(in, null, bounds); } finally { close(in); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample <<= 1;
        BitmapFactory.Options dec = new BitmapFactory.Options();
        dec.inSampleSize = sample;
        in = openThumb(file, doc);
        if (in == null) return null;
        Bitmap bmp;
        try { bmp = BitmapFactory.decodeStream(in, null, dec); } finally { close(in); }
        if (bmp == null) return null;
        if (Build.VERSION.SDK_INT >= 24) {
            int deg = 0;
            try {
                if (file != null) {
                    deg = exifRotation(new ExifInterface(file.getAbsolutePath()));
                } else if (doc != null) {
                    in = openThumb(null, doc);
                    if (in != null) {
                        try { deg = exifRotation(new ExifInterface(in)); } finally { close(in); }
                    }
                }
            } catch (Throwable ignored) {}
            if (deg != 0) {
                Bitmap r = rotateBitmap(bmp, deg);
                if (r != bmp) bmp.recycle();
                bmp = r;
            }
        }
        return encodeJpegWhite(bmp, targetPx, quality);
    }

    /** 视频缩略图：取第 1 秒附近关键帧，失败退回第 0 帧 */
    private byte[] videoThumb(File file, DocumentFile doc) {
        MediaMetadataRetriever ret = new MediaMetadataRetriever();
        try {
            if (doc != null) ret.setDataSource(context, doc.getUri());
            else ret.setDataSource(file.getAbsolutePath());
        } catch (Throwable e) {
            try { ret.release(); } catch (Exception ignored) {}
            return null;
        }
        Bitmap frame = null;
        try {
            frame = ret.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Throwable ignored) {}
        if (frame == null) {
            try {
                frame = ret.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Throwable ignored) {}
        }
        try { ret.release(); } catch (Exception ignored) {}
        if (frame == null) return null;
        return encodeJpegWhite(frame, THUMB_TARGET, 72);
    }

    // 图标 24px 展示（2~4x 高密度屏），目标解码尺寸取 96 即可，避免为小图生成大图浪费 CPU/带宽
    private static final int THUMB_TARGET = 96;

    @SuppressLint("NewApi")
    private static int exifRotation(ExifInterface ex) {
        int o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        switch (o) {
            case ExifInterface.ORIENTATION_ROTATE_90: return 90;
            case ExifInterface.ORIENTATION_ROTATE_180: return 180;
            case ExifInterface.ORIENTATION_ROTATE_270: return 270;
            default: return 0;
        }
    }

    private static Bitmap rotateBitmap(Bitmap b, int deg) {
        if (deg == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, false);
    }

    /** 统一缩放到最长边≤targetPx 并铺白底后以 JPEG(quality) 输出；透明 PNG 先铺白避免 JPEG 后呈黑底 */
    private static byte[] encodeJpegWhite(Bitmap b, int targetPx, int quality) {
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        if (w > 0 && h > 0 && (w > targetPx || h > targetPx)) {
            float scale = targetPx / (float) Math.max(w, h);
            int nw = Math.max(1, Math.round(w * scale));
            int nh = Math.max(1, Math.round(h * scale));
            try {
                Bitmap s = Bitmap.createScaledBitmap(b, nw, nh, true);
                if (s != b) { b.recycle(); b = s; }
            } catch (Throwable ignored) {}
        }
        if (b.hasAlpha()) {
            try {
                Bitmap out = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(out);
                cv.drawColor(Color.WHITE);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                cv.drawBitmap(b, 0, 0, p);
                b.recycle();
                b = out;
            } catch (Throwable ignored) {}
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(
                    Math.max(8 * 1024, b.getWidth() * b.getHeight() / 3));
            if (!b.compress(Bitmap.CompressFormat.JPEG, quality, bos)) return null;
            return bos.toByteArray();
        } finally {
            b.recycle();
        }
    }

    /** 查看器压缩预览目标：最长边 1920px（高密度屏查看足够清晰），预览不替代原图，原图下载保持完整尺寸与画质 */
    private static final int PREVIEW_MAX = 1920;
    private static final int PREVIEW_QUALITY = 82;

    /** 是否为可光栅化转码的位图格式；svg/ico 等非光栅直接回退原图（矢量原样显示无损） */
    private static boolean isRasterImage(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }

    /** 查看器压缩预览图：读缓存或现场解码（最长边≤1920 JPEG）。非光栅/解码失败返回 null，调用方回退原图发送 */
    private byte[] previewOf(String key, File file, DocumentFile doc) {
        String name = (file != null) ? file.getName() : (doc != null ? doc.getName() : null);
        if (!isRasterImage(name)) return null;
        byte[] data = previewCache.get(key);
        if (data == null) {
            data = imageJpeg(file, doc, PREVIEW_MAX, PREVIEW_QUALITY);
            if (data != null) previewCache.put(key, data);
        }
        return data;
    }

    private InputStream openThumb(File file, DocumentFile doc) {
        try {
            if (doc != null) return context.getContentResolver().openInputStream(doc.getUri());
            if (file != null) return new BufferedInputStream(new FileInputStream(file), 64 * 1024);
        } catch (Exception e) {
            Log.w(TAG, "openThumb error: " + e.getMessage());
        }
        return null;
    }

    private static void close(InputStream in) {
        if (in != null) {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    /** 直接写出缩略图字节（带长缓存头，浏览器同目录二次打开几乎零流量） */
    private void writeBytesResponse(Socket client, byte[] data, boolean keepAlive) throws Exception {
        OutputStream os = client.getOutputStream();
        PrintStream ps = new PrintStream(os);
        ps.print("HTTP/1.1 200 OK\r\n");
        ps.print("Content-Type: image/jpeg\r\n");
        ps.print("Content-Length: " + data.length + "\r\n");
        ps.print("Cache-Control: public, max-age=86400\r\n");
        ps.print("Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n\r\n");
        ps.flush();
        os.write(data);
        os.flush();
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
                    "text/html; charset=UTF-8", html.getBytes("UTF-8"), false);
        } catch (Exception ignored) {}
    }

    /**
     * 发送带 Content-Length 的完整响应。keepAlive 为 true 时用 HTTP/1.1 + keep-alive 复用连接；
     * 否则保持 HTTP/1.0 + close（向后兼容）。
     */
    private void sendResponse(OutputStream os, String status, String contentType,
                              byte[] content, boolean keepAlive) throws Exception {
        sendResponse(os, status, contentType, content, keepAlive, null);
    }

    /** 同上，可额外附带一个 Set-Cookie 头（目录页回写浏览状态用） */
    private void sendResponse(OutputStream os, String status, String contentType,
                              byte[] content, boolean keepAlive, String setCookie) throws Exception {
        // 目录 HTML(text/html)若客户端支持 gzip 则压缩传输：页面内联的整块样式/脚本与文件名列表均可高度压缩，
        // 显著降低每次导航重传的字节数；小响应(<700B)压缩不划算，二进制/媒体类型一律不压缩
        byte[] body = content;
        boolean gzipped = false;
        if (contentType.startsWith("text/html")
                && content.length >= 700
                && Boolean.TRUE.equals(reqGzip.get())) {
            byte[] gz = gzipBytes(content);
            if (gz != null && gz.length < content.length) {
                body = gz;
                gzipped = true;
            }
        }
        PrintStream ps = new PrintStream(os);
        if (keepAlive) {
            ps.print("HTTP/1.1 " + status + "\r\n");
        } else {
            ps.print("HTTP/1.0 " + status + "\r\n");
        }
        ps.print("Content-Type: " + contentType + "\r\n");
        if (gzipped) {
            ps.print("Content-Encoding: gzip\r\n");
            ps.print("Vary: Accept-Encoding\r\n");
        }
        ps.print("Content-Length: " + body.length + "\r\n");
        ps.print("Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n");
        ps.print("Server: Device2Device\r\n");
        ps.print("Access-Control-Allow-Origin: *\r\n");
        if (setCookie != null && !setCookie.isEmpty()) {
            ps.print("Set-Cookie: " + setCookie + "\r\n");
        }
        ps.print("\r\n");
        ps.flush();
        os.write(body);
        os.flush();
    }

    /** gzip 压缩(仅 text/html)：BEST_SPEED 级别兼顾移动端 CPU 与传输字节；失败返回 null 由调用方回退原样发送 */
    private static byte[] gzipBytes(byte[] raw) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(512, raw.length / 3));
            GZIPOutputStream gz = new GZIPOutputStream(bos) {
                { def.setLevel(Deflater.BEST_SPEED); }
            };
            gz.write(raw);
            gz.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 大文件（&gt;10MB）流式发送：256KB 缓冲循环拷贝。
     * 相比原先 64KB，可显著减少 read/write 系统调用与 ContentProvider IPC 往返次数。
     * 响应始终携带 Content-Length，keepAlive 时连接可继续复用。
     */
    private static void streamLargeFile(OutputStream os, InputStream is,
                                        String mimeType, long fileSize, boolean keepAlive) throws Exception {
        PrintStream ps = new PrintStream(os);
        if (keepAlive) {
            ps.print("HTTP/1.1 200 OK\r\n");
        } else {
            ps.print("HTTP/1.0 200 OK\r\n");
        }
        ps.print("Content-Type: " + mimeType + "\r\n");
        ps.print("Content-Length: " + fileSize + "\r\n");
        ps.print("Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n");
        ps.print("\r\n");
        ps.flush();

        byte[] buffer = new byte[256 * 1024];
        int read;
        while ((read = is.read(buffer)) > 0) {
            os.write(buffer, 0, read);
        }
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

    /** 根据文件(夹)解析 MIME 类型 */
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
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
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

    /** 是否为可在页内预览的图片类型 */
    private static boolean isImageViewable(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".bmp")
                || lower.endsWith(".webp") || lower.endsWith(".svg");
    }

    /**
     * 页内图片查看器：点击图片放大预览（预览走服务端 ?preview=1 压缩图，最长边≤1920，不必全屏拉原图，
     * 展示效率更高），支持左右翻页（含上一张/下一张、键盘方向键与触屏滑动）
     * 与“下载原图”（download 属性 + 同源直达原图 URL，完整尺寸/画质）。
     */
    private String buildImageViewerHtml() {
        return "<div id=\"viewer\" class=\"viewer\" aria-hidden=\"true\">"
                + "<div class=\"viewer-stage\">"
                + "<img id=\"viewer-img\" alt=\"\" draggable=\"false\">"
                + "<div class=\"viewer-spin\" id=\"viewer-spin\" role=\"status\"></div>"
                + "</div>"
                + "<button type=\"button\" class=\"viewer-btn viewer-close\" id=\"viewer-close\" aria-label=\"关闭\">×</button>"
                + "<button type=\"button\" class=\"viewer-btn viewer-prev\" id=\"viewer-prev\" aria-label=\"上一张\">←</button>"
                + "<button type=\"button\" class=\"viewer-btn viewer-next\" id=\"viewer-next\" aria-label=\"下一张\">→</button>"
                + "<div class=\"viewer-msg\" id=\"viewer-msg\" role=\"alert\"></div>"
                + "<div class=\"viewer-bar\">"
                + "<span id=\"viewer-name\"></span>"
                + "<span id=\"viewer-count\"></span>"
                + "<a id=\"viewer-dl\" href=\"#\" download>⬇ 下载原图</a>"
                + "</div>"
                + "</div>"
                + "<script>"
                + "(function(){"
                + "var box=document.getElementById('viewer');"
                + "var vimg=document.getElementById('viewer-img');"
                + "var spin=document.getElementById('viewer-spin');"
                + "var vmsg=document.getElementById('viewer-msg');"
                + "var vdl=document.getElementById('viewer-dl');"
                + "var vname=document.getElementById('viewer-name');"
                + "var vcount=document.getElementById('viewer-count');"
                + "var bPrev=document.getElementById('viewer-prev');"
                + "var bNext=document.getElementById('viewer-next');"
                + "function allImgs(){return Array.prototype.slice.call(document.querySelectorAll('a.img'));}"
                + "var imgs=allImgs();"
                + "if(!imgs.length)return;"
                + "var idx=0,startX=0,startY=0;"
                + "function absUrl(u){try{return new URL(u,location.href).href;}catch(e){return u;}}"
                + "function setButtons(){"
                + "bPrev.className='viewer-btn viewer-prev'+(idx<=0?' dim':'');"
                + "bNext.className='viewer-btn viewer-next'+(idx>=imgs.length-1?' dim':'');"
                + "}"
                + "function showMsg(actions){"
                + "vmsg.innerHTML='';"
                + "for(var i=0;i<actions.length;i++){(function(act){"
                + "var b=document.createElement('button');b.type='button';b.textContent=act.t;"
                + "b.addEventListener('click',act.f);vmsg.appendChild(b);"
                + "})(actions[i]);}"
                + "vmsg.style.display=actions.length?'block':'none';"
                + "}"
                + "function load(i){"
                + "imgs=allImgs();if(!imgs.length)return;"
                + "idx=(i+imgs.length)%imgs.length;"
                + "var a=imgs[idx];"
                + "var u=absUrl(a.getAttribute('href'));"
                + "var pu=u+(u.indexOf('?')>=0?'&':'?')+'preview=1';" // 预览加载服务端压缩图(最长边≤1920px)：传输与解码大幅降低，翻页更快
                + "var name=a.getAttribute('data-name')||'image';"
                + "vdl.href=u;vdl.setAttribute('download',name);" // “下载原图”仍指向原图 URL，保持完整尺寸/画质
                + "vname.textContent=name;vcount.textContent=(idx+1)+' / '+imgs.length;"
                + "setButtons();"
                + "spin.style.display='block';vimg.style.visibility='hidden';showMsg([]);"
                + "vimg.onload=function(){spin.style.display='none';vimg.style.visibility='visible';};"
                + "vimg.onerror=function(){spin.style.display='none';"
                + "showMsg([{t:'重试',f:function(){load(idx);}},"
                + "{t:'新窗口打开原图',f:function(){window.open(u,'_blank');}}]);};"
                + "vimg.removeAttribute('src');"
                + "vimg.src=pu;"
                + "box.classList.add('open');box.setAttribute('aria-hidden','false');"
                + "document.body.style.overflow='hidden';"
                + "}"
                + "function hide(){"
                + "box.classList.remove('open');box.setAttribute('aria-hidden','true');"
                + "document.body.style.overflow='';"
                + "spin.style.display='none';showMsg([]);"
                + "vimg.removeAttribute('src');vimg.onload=null;vimg.onerror=null;"
                + "}"
                + "var anchors=allImgs();"
                + "for(var ai=0;ai<anchors.length;ai++)(function(a){a.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();var at=allImgs().indexOf(a);load(at<0?0:at);});})(anchors[ai]);"
                + "document.getElementById('viewer-close').addEventListener('click',hide);"
                + "bPrev.addEventListener('click',function(e){e.stopPropagation();load(idx-1);});"
                + "bNext.addEventListener('click',function(e){e.stopPropagation();load(idx+1);});"
                + "box.addEventListener('click',function(e){if(e.target===box)hide();});"
                + "document.addEventListener('keydown',function(e){"
                + "if(!box.classList.contains('open'))return;"
                + "if(e.key==='Escape'||e.key==='Esc'){hide();}"
                + "else if(e.key==='ArrowLeft'){load(idx-1);}"
                + "else if(e.key==='ArrowRight'){load(idx+1);}"
                + "});"
                + "box.addEventListener('touchstart',function(e){var t=e.touches[0];startX=t.clientX;startY=t.clientY;},{passive:true});"
                + "box.addEventListener('touchend',function(e){"
                + "if(startX===0||!box.classList.contains('open')){startX=0;return;}"
                + "var t=e.changedTouches[0];"
                + "var dx=t.clientX-startX,dy=t.clientY-startY;"
                + "if(Math.abs(dx)>50&&Math.abs(dx)>Math.abs(dy)*1.4){dx<0?load(idx+1):load(idx-1);}"
                + "startX=0;startY=0;"
                + "},{passive:true});"
                + "})();"
                + "</script>";
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
