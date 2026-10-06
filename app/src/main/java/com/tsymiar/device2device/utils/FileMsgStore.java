package com.tsymiar.device2device.utils;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.tsymiar.device2device.wrapper.NetworkWrapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 文件传输的落盘位置管理（对话框与前台服务共用一套，避免两处各写一遍）。
 *
 * 为什么不直接让 native 写到 Download：
 * native 用 std::ofstream，只认真实文件系统路径；而本 app target 31，
 * Android 10+ 的公共目录不能用 File API 直写（requestLegacyExternalStorage 已失效）。
 * 所以流程是「native 落到应用私有 inbox → 收完立刻搬到公共 Download」，
 * 否则文件会留在 Android/data 下，用户在文件管理器里根本看不到。
 */
public final class FileMsgStore {
    private static final String TAG = "FileMsgStore";

    /** 公共下载目录下的子目录：文件最终落在 Download/Device2Device */
    public static final String SAVE_SUBDIR = "Device2Device";
    /** native 落盘的暂存目录（应用私有），收完即搬走 */
    private static final String INBOX_DIR = "inbox";

    private FileMsgStore() {
    }

    /** native 侧的落盘目录：应用私有 inbox，必然可写 */
    public static File inboxDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), INBOX_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create inbox dir: " + dir);
        }
        return dir;
    }

    /** 用户最终能看到的位置：Download/Device2Device */
    public static String publicDir() {
        return Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_SUBDIR;
    }

    /** 把 native 的保存路径指到 inbox（服务端收文件靠它落盘） */
    public static String applySavePath(Context ctx) {
        File dir = inboxDir(ctx);
        NetworkWrapper.setFileSavePath(dir.getAbsolutePath());
        return dir.getAbsolutePath();
    }

    public interface Callback {
        /** ok=true 时 dest 是公共目录里的相对位置；false 时 dest 是保留下来的暂存路径 */
        void onDone(boolean ok, String dest);
    }

    /** 后台搬一个文件到公共下载目录；callback 可为空（用于写日志） */
    public static void publishAsync(Context ctx, final String srcPath, final Callback cb) {
        if (ctx == null || srcPath == null || srcPath.isEmpty()) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            File src = new File(srcPath);
            if (!src.exists()) return;

            String saved = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saved = saveViaMediaStore(app, src);
            } else if (ContextCompat.checkSelfPermission(app,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                saved = saveDirect(app, src);
            }

            boolean ok = saved != null;
            if (ok) {
                if (!src.delete()) Log.w(TAG, "cannot remove staged file: " + srcPath);
            } else {
                Log.w(TAG, "public save unavailable, kept at " + srcPath);
            }
            if (cb != null) cb.onDone(ok, ok ? saved : srcPath);
        }).start();
    }

    /** 补搬：把 inbox 里残留的文件（收完时对话框已关、或上次没搬成功）全部搬到公共目录 */
    public static void flushInbox(Context ctx, Callback cb) {
        if (ctx == null) return;
        File[] files = inboxDir(ctx).listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) publishAsync(ctx, f.getAbsolutePath(), cb);
        }
    }

    /** Android 10+：走 MediaStore 写 Downloads，不需要任何存储权限 */
    private static String saveViaMediaStore(Context ctx, File src) {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, src.getName());
        cv.put(MediaStore.Downloads.RELATIVE_PATH, publicDir());
        cv.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
        cv.put(MediaStore.Downloads.IS_PENDING, 1);

        ContentResolver cr = ctx.getContentResolver();
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) {
            Log.w(TAG, "MediaStore insert failed for " + src.getName());
            return null;
        }
        try (InputStream in = new FileInputStream(src);
             OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("openOutputStream returned null");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "publish to Downloads failed: " + e.getMessage());
            try {
                cr.delete(uri, null, null);
            } catch (Exception ignored) {
            }
            return null;
        }
        cv.clear();
        cv.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(uri, cv, null, null);
        return publicDir() + "/" + src.getName();
    }

    /** Android 9 及以下：直接写公共 Downloads（需要 WRITE_EXTERNAL_STORAGE） */
    @SuppressWarnings("deprecation")
    private static String saveDirect(Context ctx, File src) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), SAVE_SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create dir: " + dir);
            return null;
        }
        File dst = new File(dir, src.getName());
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "copy to Downloads failed: " + e.getMessage());
            return null;
        }
        return dst.getAbsolutePath();
    }
}
