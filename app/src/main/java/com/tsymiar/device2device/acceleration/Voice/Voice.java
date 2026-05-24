package com.tsymiar.device2device.acceleration.Voice;

import android.annotation.SuppressLint;
import android.app.IntentService;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.util.Log;

public class Voice extends IntentService {

    private static final String TAG = "Voice";
    private MediaPlayer mp3;

    @SuppressLint("NewApi")
    public Voice() {
        super("Voice");
        Log.i(TAG, this + " is constructed");
    }

    @SuppressLint("WrongThread")
    @Override
    protected void onHandleIntent(Intent intent) {
        mp3 = new MediaPlayer();
        try {
            mp3.stop();
            AssetFileDescriptor fileDescriptor = getAssets().openFd("m.mp3");
            mp3.setDataSource(fileDescriptor.getFileDescriptor(),
                    fileDescriptor.getStartOffset(),
                    fileDescriptor.getLength());
            mp3.prepare();
            mp3.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to play audio", e);
        }
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
    }
}
