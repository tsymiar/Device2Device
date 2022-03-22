package com.tsymiar.devidroid.activity;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.tsymiar.devidroid.R;
import com.tsymiar.devidroid.data.PubSubSetting;
import com.tsymiar.devidroid.event.EventEntity;
import com.tsymiar.devidroid.event.EventHandle;
import com.tsymiar.devidroid.event.EventNotify;
import com.tsymiar.devidroid.service.FloatingService;
import com.tsymiar.devidroid.service.PublishDialog;
import com.tsymiar.devidroid.utils.JvmMethods;
import com.tsymiar.devidroid.wrapper.CallbackWrapper;
import com.tsymiar.devidroid.wrapper.NetWrapper;
import com.tsymiar.devidroid.wrapper.TimeWrapper;

import java.util.Arrays;

public class MainActivity extends AppCompatActivity implements EventHandle {
    private static final String TAG = MainActivity.class.getCanonicalName();
    public static final int RequestPublish = 10002;
    public static final int RequestFloat = 10003;
    static MainActivity mainActivity;
    BroadcastReceiverClass mBroadcastReceiverClass = new BroadcastReceiverClass();
    private ServiceConnection mServiceConnection = null;
    FloatingService mFloatService;
    private int gValue = 1;
    Intent publisherIntent;
    Intent subscribeIntent;

    public static MainActivity getInstance()
    {
        return mainActivity;
    }

    public ServiceConnection getServiceConnection() {
        return mServiceConnection;
    }

    public void setServiceConnection(ServiceConnection mServiceConnection) {
        this.mServiceConnection = mServiceConnection;
    }

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
    public void handle(EventEntity... event) {
        Log.i(TAG, Arrays.toString(event));
    }

    @SuppressLint("HandlerLeak")
    private final Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            Log.i(TAG, msg.obj.toString());
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.M)
    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // a call to a native method
        TextView textView = findViewById(R.id.sample_text);
        textView.setText((new CallbackWrapper()).stringGetJNI());
        Time time = new Time();

        CallbackWrapper.initJvmEnv(JvmMethods.TAG);
        CallbackWrapper.callJavaMethod("hello", 0, "non-static call", false);
        CallbackWrapper.callJavaMethod("welcome", 2, "callJavaStaticMethod!", true);

        TimeWrapper.getBootTimestamp();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(FloatingService.BROADCAST_ACTION);
        this.registerReceiver(mBroadcastReceiverClass, intentFilter);

        setServiceConnection(new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                FloatingService.Binder binder = (FloatingService.Binder) service;
                mFloatService = binder.getService();
                mFloatService.setCallback(data -> {
                    Message msg = new Message();
                    msg.obj = data;
                    handler.sendMessage(msg);
                });
            }
            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.d(TAG, name.toString() + " is disconnected");
            }
        });

        subscribeIntent = new Intent(MainActivity.this, FloatingService.class);
        publisherIntent = new Intent(MainActivity.this, PublishDialog.class);

        findViewById(R.id.btn_texture).setOnClickListener(
                v -> startActivity(new Intent(MainActivity.this, TextureActivity.class))
        );
        findViewById(R.id.btn_audio).setOnClickListener(
                v -> startActivity(new Intent(MainActivity.this, WaveActivity.class))
        );
        findViewById(R.id.btn_chart).setOnClickListener(
                v -> startActivity(new Intent(MainActivity.this, GraphActivity.class))
        );
        findViewById(R.id.btn_time).setOnClickListener(
                v -> {
                    TextView tv = findViewById(R.id.txt_time);
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
                    TextView tv = findViewById(R.id.txt_event);
                    tv.setText(event.getEvent().toString() + ", value = " + gValue);
                }
        );
        WifiManager manager = (WifiManager) this.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        assert manager != null;
        WifiManager.MulticastLock wifiLock = manager.createMulticastLock("localWifi");
        findViewById(R.id.btn_server).setOnClickListener(
                v -> {
                    wifiLock.acquire();
                    NetWrapper.startServer();
                }
        );
        findViewById(R.id.btn_client).setOnClickListener(
                v -> {
                    NetWrapper.sendUdpData(gValue + " - aaa", 8);
                    gValue++;
                    if (wifiLock.isHeld()) {
                        wifiLock.release();
                    }
                }
        );
        findViewById(R.id.btn_subscribe).setOnClickListener(
                v -> {
                    if (!Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, "Please enable the PERMISSION", Toast.LENGTH_SHORT).show();
                        startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), RequestFloat);
                    } else {
                        startService(subscribeIntent);
                    }
                }
        );
        findViewById(R.id.btn_publisher).setOnClickListener(
                v -> {
                    if (!Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, "Please enable the PERMISSION", Toast.LENGTH_SHORT).show();
                        startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), RequestFloat);
                    } else {
                        startService(publisherIntent);
                    }
                }
        );
    }
    private class BroadcastReceiverClass extends BroadcastReceiver {
        @SuppressLint("SetTextI18n")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(FloatingService.BROADCAST_ACTION)) {
                String subscribe = intent.getStringExtra("Subscribe");
                System.out.println("Subscribe status ==> " + subscribe);
                TextView tv = findViewById(R.id.txt_status);
                if (subscribe != null && subscribe.equals("SUCCESS")) {
                    Log.i(TAG, PubSubSetting.getSetting().toString());
                    int ret = CallbackWrapper.KaiSubscribe(PubSubSetting.getAddr(),
                            PubSubSetting.getPort(), PubSubSetting.getTopic(), "txt_status", R.id.txt_status);
                    if (ret < 0) {
                        tv.setText("subscribe has ready!");
                    } else {
                        tv.setText("success");
                    }
                } else {
                    Log.i(TAG, "Subscribe with " + subscribe);
                }
                String publish = intent.getStringExtra("Publish");
                if (publish != null) {
                    if (publish.equals("SUCCESS")) {
                        Log.i(TAG, "Publish status ==> " + publish + ":\n" + PubSubSetting.getSetting().toString());
                        CallbackWrapper.KaiPublish(PubSubSetting.getTopic(), PubSubSetting.getPayload());
                    }
                } else {
                    Log.i(TAG, "Publish status is null");
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        MainActivity.this.unregisterReceiver(mBroadcastReceiverClass);
        stopService(subscribeIntent);
        stopService(publisherIntent);
        if(mFloatService != null) {
            mFloatService.closeWindow();
        }
        super.onDestroy();
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RequestPublish && resultCode == RESULT_OK) {
            String string = data.getStringExtra("Publish");
            if (string != null && string.equals("SUCCESS")) {
                Log.i(TAG, "Publish " + string + ":\n" + PubSubSetting.getSetting().toString());
                CallbackWrapper.KaiPublish(PubSubSetting.getTopic(), PubSubSetting.getPayload());
            } else {
                Log.i(TAG, PubSubSetting.getSetting().toString() + " with " + string);
            }
        }
        if (requestCode == RequestFloat) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "permission denied", Toast.LENGTH_SHORT).show();
            } else {
                startService(new Intent(MainActivity.this, FloatingService.class));
            }
        }
    }
}
