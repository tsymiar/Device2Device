package com.tsymiar.SerialConn.Voice;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.IntentService;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.Log;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

@TargetApi(Build.VERSION_CODES.CUPCAKE)
public class Voice extends IntentService {

    private static final String TAG = "Voice";
    private TextView state = null;
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
        // mp3 = MediaPlayer.create(MainActivity.this,R.raw.m);
        try {
            if (mp3 != null) {
                mp3.stop();
            }
            AssetFileDescriptor fileDescriptor = getAssets().openFd("m.mp3");
            mp3.setDataSource(fileDescriptor.getFileDescriptor(),
                    fileDescriptor.getStartOffset(),
                    fileDescriptor.getLength());
            mp3.prepare();
            mp3.start();
            state.setText("Playing");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
    }

    public String getFromAssets(String fileName) {
        StringBuilder result = new StringBuilder();
        try {
            InputStreamReader inputReader = new InputStreamReader(getResources().getAssets().open(fileName));
            InputStream in = getResources().getAssets().open(fileName);
            int length = in.available();
            byte[] buffer = new byte[length];
            in.read(buffer);
            String line;
            BufferedReader bufReader = new BufferedReader(inputReader);
            while ((line = bufReader.readLine()) != null)
                result.append(line);
        } catch (Exception e) {
            e.printStackTrace();
            result = null;
        }
        assert result != null;
        return result.toString();
    }
}
