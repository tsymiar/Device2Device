package com.tsymiar.devidroid.utils;

import android.os.Environment;
import android.util.Log;

import java.io.File;

public class FileUtil {
    private static final String TAG = FileUtil.class.getSimpleName();

    public static void createDirectory(String fullPath) {
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            Log.e(TAG, "ExternalStorageState = '" + Environment.MEDIA_MOUNTED);
        }
        File file = new File(fullPath);
        if (!file.exists()) {
            if (!file.mkdirs()) {
                Log.e(TAG, "File.mkdirs '" + fullPath + "' not created.");
            }
        }
    }
}
