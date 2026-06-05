package com.tsymiar.device2device.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.tsymiar.device2device.R;

import java.io.File;
import java.io.OutputStreamWriter;

public class SaveDataService extends Service {
    private static final String TAG = "SaveDataService";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        if (intent != null && intent.getExtras() != null) {
            String temp = intent.getExtras().getString("temp");
            if (temp != null && !"stop".equals(temp)) {
                saveData(temp);
            } else {
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void saveData(String temp) {
        new Thread(() -> {
            try {
                File dir = new File(android.os.Environment.getExternalStorageDirectory()
                        + "/Device2Device");
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "mkdirs failed");
                    return;
                }
                File file = new File(dir, getString(R.string.file_local));
                if (!file.exists() && !file.createNewFile()) {
                    Log.e(TAG, "createNewFile failed");
                    return;
                }
                OutputStreamWriter writer = new OutputStreamWriter(
                        new java.io.FileOutputStream(file, true));
                writer.write(temp + "\t");
                writer.flush();
                writer.close();
            } catch (Exception e) {
                Log.e(TAG, "saveData failed", e);
            }
        }).start();
    }
}
