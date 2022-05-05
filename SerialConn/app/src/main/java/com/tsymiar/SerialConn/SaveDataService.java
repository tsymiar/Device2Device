package com.tsymiar.SerialConn;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;

public class SaveDataService extends Service {
    private String temp = null;

    @Override
    public IBinder onBind(Intent p1) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        Bundle bundle = intent.getExtras();
        temp = bundle != null ? bundle.getString("temp") : null;
        new Thread() {
            public void run() {
                Message msg = new Message();
                msg.what = 0;
                handler.sendMessage(msg);
                try {
                    sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }

    @SuppressLint("HandlerLeak")
    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            if (msg.what == 0) {
                try {
                    File dir = new File(android.os.Environment.getExternalStorageDirectory()
                            + "/Device2Device");
                    if (!dir.exists())
                        dir.mkdirs();
                    else {
                        FileOutputStream fos = new FileOutputStream(
                                android.os.Environment.getExternalStorageDirectory()
                                        + getString(R.string.filelocal), true);
                        OutputStreamWriter writer = new OutputStreamWriter(fos);
                        writer.write(temp + "\t");
                        writer.close();
                        fos.close();
                        InputStream is = getResources().getAssets().open("signal.dat");
                        is.close();
                    }
                } catch (Exception e) {
                    Toast.makeText(SaveDataService.this, e.getMessage() + getString(R.string.saved), Toast.LENGTH_SHORT).show();
                }
            }
            super.handleMessage(msg);
        }
    };
}