package com.tsymiar.device2device.activity;

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
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.entity.PubSubSetting;
import com.tsymiar.device2device.entity.Receiver;
import com.tsymiar.device2device.event.EventEntity;
import com.tsymiar.device2device.event.EventHandle;
import com.tsymiar.device2device.event.EventNotify;
import com.tsymiar.device2device.service.FloatingService;
import com.tsymiar.device2device.service.PublishDialog;
import com.tsymiar.device2device.utils.JvmMethods;
import com.tsymiar.device2device.utils.Utils;
import com.tsymiar.device2device.wrapper.CallbackWrapper;
import com.tsymiar.device2device.wrapper.NetWrapper;
import com.tsymiar.device2device.wrapper.TimeWrapper;

import java.util.Arrays;

public class MainActivity extends AppCompatActivity implements EventHandle {
    private static final String TAG = MainActivity.class.getCanonicalName();
    public static final int RequestStorage = 10001;
    public static final int RequestFloat = 10002;
    public static final int RequestAudio = 10003;
    @SuppressLint("StaticFieldLeak")
    static MainActivity mainActivity;
    BroadcastReceiverClass mBroadcastReceiverClass = new BroadcastReceiverClass();
    private ServiceConnection mServiceConnection = null;
    FloatingService mFloatService;
    private int gValue = 1;
    Intent publisherIntent;
    Intent subscribeIntent;
    Button mKcpBtn;

    public static MainActivity getInstance() {
        return mainActivity;
    }

    public ServiceConnection getServiceConnection() {
        return mServiceConnection;
    }

    public void setServiceConnection(ServiceConnection mServiceConnection) {
        this.mServiceConnection = mServiceConnection;
    }

    @Override
    public void handle(EventEntity... event) {
        Log.i(TAG, Arrays.toString(event));
    }

    @SuppressLint("HandlerLeak")
    private final Handler handler = new Handler() {
        @SuppressLint("SetTextI18n")
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            TextView tv;
            switch (msg.what) {
            case Receiver.MESSAGE:
                tv = findViewById(R.id.txt_status);
                tv.setText(msg.obj.toString());
                break;
            case Receiver.UDP_SERVER:
                tv = findViewById(R.id.txt_server);
                tv.setText(msg.obj.toString());
                break;
            case Receiver.UDP_CLIENT:
                tv = findViewById(R.id.txt_client);
                tv.setText(msg.obj.toString());
                break;
            case Receiver.TOAST:
            case Receiver.KAI_SUBSCRIBE:
            case Receiver.KAI_PUBLISHER:
                Toast.makeText(getApplicationContext(), msg.obj.toString(), Toast.LENGTH_SHORT).show();
                break;
            case Receiver.LOG_VIEW:
                TextureActivity.log(msg.obj.toString());
                break;
            case Receiver.UPDATE_VIEW:
                mKcpBtn.setText(msg.obj.toString());
                break;
            default:
                break;
            }
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.M)
    @SuppressLint({ "SetTextI18n", "UnspecifiedRegisterReceiverFlag" })
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // a call to a native method
        TextView textView = findViewById(R.id.sample_text);
        textView.setText((new CallbackWrapper()).stringGetJNI());
        Utils.Time time = new Utils.Time();

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
                FloatingService.Binder binder = (FloatingService.Binder)service;
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

        new Thread(() -> {
            do {
                Receiver receiver = new Receiver();
                CallbackWrapper wrapper = new CallbackWrapper();
                receiver = wrapper.getMessage(receiver);
                if (receiver != null && receiver.message != null) {
                    Message msg = new Message();
                    msg.what = receiver.receiver;
                    msg.obj = receiver.message;
                    handler.sendMessage(msg);
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } while (true);
        }).start();

        findViewById(R.id.btn_texture)
                .setOnClickListener(v -> startActivity(new Intent(MainActivity.this, TextureActivity.class)));
        findViewById(R.id.btn_wave)
                .setOnClickListener(v -> startActivity(new Intent(MainActivity.this, WaveActivity.class)));
        findViewById(R.id.btn_chart)
                .setOnClickListener(v -> startActivity(new Intent(MainActivity.this, GraphActivity.class)));
        findViewById(R.id.btn_time).setOnClickListener(v -> {
            TextView tv = findViewById(R.id.txt_time);
            time.x = (int)tv.getX();
            time.t = System.currentTimeMillis();
            Log.i(TAG, time.t + "\n---- " + Arrays.toString(time.toByte()));
            tv.setText(String.valueOf(new CallbackWrapper().timeSetJNI(time.toByte(), Utils.Time.length)));
        });
        findViewById(R.id.btn_event).setOnClickListener(view -> {
            EventNotify notify = new EventNotify();
            notify.register(this);
            EventEntity event = new EventEntity();
            event.setEvent("event: " + gValue);
            notify.notifyListeners(event);
            gValue++;
            TextView tv = findViewById(R.id.txt_event);
            tv.setText(event.getEvent().toString() + ", value = " + gValue);
        });
        WifiManager manager = (WifiManager)this.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        assert manager != null;
        WifiManager.MulticastLock wifiLock = manager.createMulticastLock("localWifi");
        findViewById(R.id.btn_server).setOnClickListener(v -> {
            wifiLock.acquire();
            NetWrapper.startUdpServer(8899);
        });
        findViewById(R.id.btn_client).setOnClickListener(v -> {
            String text = Utils.MD5(gValue + "").substring(0, 6);
            NetWrapper.sendUdpData(text, text.length());
            gValue++;
            if (wifiLock.isHeld()) {
                wifiLock.release();
            }
        });
        findViewById(R.id.btn_subscribe).setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please enable the PERMISSION", Toast.LENGTH_SHORT).show();
                startActivityForResult(
                        new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
                        RequestFloat);
            } else {
                startService(subscribeIntent);
            }
        });
        findViewById(R.id.btn_publisher).setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please enable the PERMISSION", Toast.LENGTH_SHORT).show();
                startActivityForResult(
                        new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
                        RequestFloat);
            } else {
                startService(publisherIntent);
            }
        });
        findViewById(R.id.btn_tcp).setOnClickListener(v -> NetWrapper.startTcpServer(8700));
        mKcpBtn = findViewById(R.id.btn_ikcp);
        mKcpBtn.setOnClickListener(v -> {
            NetWrapper.startKcpServer(8090);
            NetWrapper.startKcpClient("127.0.0.1", 8090);
        });
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
                tv.setText("");
                if (subscribe != null && subscribe.equals("SUCCESS")) {
                    Log.i(TAG, PubSubSetting.getSetting().toString());
                    int ret = CallbackWrapper.StartSubscribe(PubSubSetting.getAddr(), PubSubSetting.getPort(),
                            PubSubSetting.getTopic(), "txt_status", R.id.txt_status);
                    if (ret < 0) {
                        tv.setText("Subscribe beginning!");
                    } else {
                        Toast.makeText(MainActivity.this, "Success", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Log.i(TAG, "Subscribe with " + subscribe);
                }
                String publish = intent.getStringExtra("Publish");
                if (publish != null && publish.equals("SUCCESS")) {
                    PubSubSetting setting = PubSubSetting.getSetting();
                    if (setting != null) {
                        Log.i(TAG, "Publish status ==> " + publish + ":\n" + setting.toString());
                    }
                    if (PubSubSetting.getAddr().isEmpty() || PubSubSetting.getPort() == 0) {
                        Toast.makeText(MainActivity.this, "confirm subscribe first", Toast.LENGTH_SHORT).show();
                    } else {
                        CallbackWrapper.Publish(PubSubSetting.getTopic(), PubSubSetting.getPayload());
                    }
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        MainActivity.this.unregisterReceiver(mBroadcastReceiverClass);
        stopService(subscribeIntent);
        stopService(publisherIntent);
        if (mFloatService != null) {
            mFloatService.closeWindow();
        }
        super.onDestroy();
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RequestFloat) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "permission denied", Toast.LENGTH_SHORT).show();
            } else {
                startService(new Intent(MainActivity.this, FloatingService.class));
            }
        }
    }
}
