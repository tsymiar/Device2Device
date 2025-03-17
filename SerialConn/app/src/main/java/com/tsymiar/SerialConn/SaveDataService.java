package com.tsymiar.SerialConn;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStreamWriter;

public class SaveDataService extends Service {
    private String temp = null;

    @Override
    public IBinder onBind(Intent p1) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("SaveDataService", "onStartCommand");
        if (intent != null && intent.getExtras() != null)
        {
            Bundle bundle = intent.getExtras();
            temp = bundle.getString("temp");
            saveData();
        }
        return START_STICKY;
    }

    private void saveData() {
        if (temp == null) return;

        new Thread(() -> {
            try {
                File dir = new File(android.os.Environment.getExternalStorageDirectory()
                        + "/Device2Device");
                if (!dir.exists()) {
                    if (!dir.mkdirs())
                        Log.e(SaveDataService.class.getSimpleName(), "mkdirs failed!");
                }
                File file = new File(dir, getString(R.string.file_local));
                if(!file.exists()){
                    if (!file.createNewFile())
                        Log.e(SaveDataService.class.getSimpleName(), "createNewFile failed!");
                }
                OutputStreamWriter writer = new OutputStreamWriter(new java.io.FileOutputStream(file, true));
                writer.write(temp + "\t");
                writer.flush();
                writer.close();
                InputStream is = getResources().getAssets().open("signal.dat");
                is.close();
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(SaveDataService.this, getString(R.string.saved), Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(SaveDataService.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                });
                Log.e("SaveDataService", "saveData failed!", e);
            }
        }).start();
    }
}
