package com.tsymiar.device2device.utils;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** 导入的私钥统一放在应用私有目录，避免暴露给其他应用；配置里只记文件名。 */
public final class SshKeyStore {
    private static final String TAG = "SshKeyStore";
    private static final String DIR = "ssh_keys";
    /** 私钥一般几 KB，1MB 上限足够，避免误选大文件卡死 UI */
    private static final int MAX_SIZE = 1024 * 1024;

    private SshKeyStore() {
    }

    public static File dir(Context ctx) {
        File d = new File(ctx.getApplicationContext().getFilesDir(), DIR);
        if (!d.exists() && !d.mkdirs()) {
            Log.w(TAG, "cannot create key dir: " + d);
        }
        return d;
    }

    public static File file(Context ctx, String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("..")) {
            return null;
        }
        return new File(dir(ctx), name);
    }

    /** 从系统文件选择器选中的 Uri 读入并复制一份；返回保存后的文件名 */
    public static String importKey(Context ctx, Uri uri) throws Exception {
        if (uri == null) {
            throw new IllegalArgumentException("未选择文件");
        }
        byte[] data;
        try (InputStream in = ctx.getApplicationContext().getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new java.io.IOException("无法读取所选文件");
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_SIZE) {
                    throw new java.io.IOException("私钥文件过大（上限 1MB）");
                }
                bos.write(buf, 0, n);
            }
            data = bos.toByteArray();
        }
        if (data.length == 0) {
            throw new java.io.IOException("私钥文件为空");
        }
        String name = "key_" + UUID.randomUUID().toString().replace("-", "") + ".pem";
        File target = new File(dir(ctx), name);
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(data);
        }
        // 两步都要执行：内部存储本身已隔离，这里再把权限收紧到仅 owner 可读
        boolean removed = target.setReadable(false, false);
        boolean ownerOnly = target.setReadable(true, true);
        if (!removed || !ownerOnly) {
            Log.w(TAG, "cannot tighten key file permission: " + target);
        }
        return name;
    }

    public static void remove(Context ctx, String name) {
        File f = file(ctx, name);
        if (f != null && f.exists() && !f.delete()) {
            Log.w(TAG, "cannot delete key file: " + f);
        }
    }
}
