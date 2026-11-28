package com.tsymiar.device2device.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.tsymiar.device2device.utils.AppDataPaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class SaveDataService extends Service {
    private static final String TAG = "SaveDataService";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getExtras() != null) {
            String cmd = intent.getExtras().getString("cmd");
            if (cmd != null && !"stop".equals(cmd)) {
                saveData(cmd);
            } else {
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void saveData(String cmd) {
        new Thread(() -> {
            try {
                File file = AppDataPaths.getSensorDataFile(SaveDataService.this);
                File dir = file.getParentFile();
                if (dir != null && !dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "mkdirs failed");
                    return;
                }
                if (!file.exists() && !file.createNewFile()) {
                    Log.e(TAG, "createNewFile failed");
                    return;
                }
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                    writer.write(cmd + "\t");
                }
            } catch (Exception e) {
                Log.e(TAG, "saveData failed", e);
            }
        }).start();
    }
}
