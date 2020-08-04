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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // a call to a native method
        TextView tv = findViewById(R.id.sample_text);
        tv.setText((new CallbackWrapper()).stringFromJNI());

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
