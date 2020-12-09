package com.tsymiar.devidroid.activity;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.tsymiar.devidroid.R;
import com.tsymiar.devidroid.event.EventEntity;
import com.tsymiar.devidroid.event.EventHandle;
import com.tsymiar.devidroid.event.EventNotify;
import com.tsymiar.devidroid.utils.MethodUtil;
import com.tsymiar.devidroid.wrapper.CallbackWrapper;
import com.tsymiar.devidroid.wrapper.NetWrapper;
import com.tsymiar.devidroid.wrapper.TimeWrapper;

import java.util.Arrays;

public class MainActivity extends AppCompatActivity implements EventHandle {
    private static final String TAG = MainActivity.class.getCanonicalName();
    private int gValue = 1;

    public static class Time {
        int x;
        short y;
        char z;
        long t;

        public static int length = 16;

        byte[] toByte() {
            byte[] b = new byte[16];
            int v = x;
            for (int i = 0; i < 4; i++) {
                b[i] = Integer.valueOf(v & 0xff).byteValue();
                v = v >> 8;
            }
            v = y;
            for (int i = b.length - 14; i > -1; i--) {
                b[i] = Integer.valueOf(v & 0xff).byteValue();
                v = v >> 8;
            }
            b[6] = (byte) (z >>> 8);
            b[7] = (byte) z;
            b[8] = (byte) (t);
            b[9] = (byte) (t >>> 8);
            b[10] = (byte) (t >>> 16);
            b[11] = (byte) (t >>> 24);
            b[12] = (byte) (t >>> 32);
            b[13] = (byte) (t >>> 40);
            b[14] = (byte) (t >>> 48);
            b[15] = (byte) (t >>> 56);
            return b;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // a call to a native method
        TextView tv = findViewById(R.id.sample_text);
        tv.setText((new CallbackWrapper()).stringGetJNI());
        Time time = new Time();

        CallbackWrapper.initJvmEnv(MethodUtil.TAG);
        CallbackWrapper.callJavaMethod("hello", 0, "non-static call", false);
        CallbackWrapper.callJavaMethod("welcome", 2, "callJavaStaticMethod!", true);

        TimeWrapper.getBootTimestamp();

        findViewById(R.id.button).setOnClickListener(
                v -> startActivity(new Intent(MainActivity.this, TextureActivity.class))
        );
        findViewById(R.id.btn_audio).setOnClickListener(
                v -> startActivity(new Intent(MainActivity.this, WaveActivity.class))
        );
        findViewById(R.id.btn_chart).setOnClickListener(
                v -> startActivity(new Intent(MainActivity.this, ChartActivity.class))
        );
        findViewById(R.id.btn_time).setOnClickListener(
                v -> {
                    time.x = (int) tv.getX();
                    time.t = System.currentTimeMillis();
                    Log.i(TAG, time.t + "----" + Arrays.toString(time.toByte()));
                    tv.setText(String.valueOf(new CallbackWrapper().timeSetJNI(time.toByte(), Time.length)));
                }
        );
        findViewById(R.id.btn_event).setOnClickListener(
                view -> {
                    EventNotify notify = new EventNotify();
                    notify.register(this);
                    EventEntity event = new EventEntity();
                    event.setEvent("event: " + gValue);
                    notify.notifyListeners(event);
                    gValue++;
                }
        );
        WifiManager manager = (WifiManager) this.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        assert manager != null;
        WifiManager.MulticastLock wifiLock = manager.createMulticastLock("localWifi");
        findViewById(R.id.server).setOnClickListener(
                v -> {
                    wifiLock.acquire();
                    NetWrapper.startServer();
                }
        );
        findViewById(R.id.client).setOnClickListener(
                v -> {
                    NetWrapper.sendUdpData(gValue + " - aaa", 8);
                    gValue++;
                    if (wifiLock.isHeld()) {
                        wifiLock.release();
                    }
                }
        );
    }

    @Override
    public void handle(EventEntity... event) {
        Log.i(TAG, Arrays.toString(event));
    }
}
