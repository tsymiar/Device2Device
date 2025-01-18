package com.tsymiar.device2device.utils;

import android.os.Environment;
import android.util.Log;

import java.io.File;

public class LocalFile {
    private static final String TAG = LocalFile.class.getSimpleName();

    public static void createDirectory(String fullPath) {
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            Log.i(TAG, "ExternalStorageState = " + Environment.MEDIA_MOUNTED);
        }
        File file = new File(fullPath);
        if (!file.exists() && !file.mkdirs()) {
            Log.e(TAG, "File.mkdirs '" + fullPath + "' not created.");
        }
    }
}
