package com.tsymiar.device2device.utils;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 应用数据目录的统一入口。
 *
 * <p>不再硬编码 {@code /sdcard/...}：应用私有 external 目录从 Android 11 起无需存储权限，
 * 分区存储下也可正常读写，避免各模块各自拼路径导致行为不一致。</p>
 */
public final class AppDataPaths {
    private static final String TAG = "AppDataPaths";
    private static final String LEGACY_ROOT = "Device2Device";
    private static final String SENSOR_REL = "SerialConn/signal.dat";

    private AppDataPaths() { }

    private static File baseDir(Context context) {
        File external = context.getExternalFilesDir(null);
        return external != null ? external : context.getFilesDir();
    }

    /** 录音落盘目录。 */
    public static File getRecordDir(Context context) {
        File dir = new File(baseDir(context), "record");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "mkdirs failed: " + dir);
        }
        return dir;
    }

    /**
     * 串口/传感器数据文件；首次使用时把旧版 {@code /sdcard/Device2Device} 下的数据迁过来，
     * 避免升级后用户看到的历史数据消失。
     */
    public static File getSensorDataFile(Context context) {
        File target = new File(baseDir(context), LEGACY_ROOT + "/" + SENSOR_REL);
        File legacy = new File(Environment.getExternalStorageDirectory(), LEGACY_ROOT + "/" + SENSOR_REL);
        if (!target.exists() && legacy.isFile()) {
            File parent = target.getParentFile();
            if (parent != null && (parent.exists() || parent.mkdirs())) {
                try (InputStream in = new FileInputStream(legacy);
                     OutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } catch (IOException e) {
                    Log.w(TAG, "migrate sensor data failed", e);
                }
            }
        }
        return target;
    }
}
