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
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.dialog.ChatBoxDialog;
import com.tsymiar.device2device.dialog.FileMsgDialog;
import com.tsymiar.device2device.entity.PubSubSetting;
import com.tsymiar.device2device.entity.Receiver;
import com.tsymiar.device2device.event.EventEntity;
import com.tsymiar.device2device.event.EventHandle;
import com.tsymiar.device2device.event.EventNotify;
import com.tsymiar.device2device.service.HttpFileService;
import com.tsymiar.device2device.service.PublishService;
import com.tsymiar.device2device.service.SubscribeService;
import com.tsymiar.device2device.utils.JvmMethods;
import com.tsymiar.device2device.utils.Utils;
import com.tsymiar.device2device.wrapper.CallbackWrapper;
import com.tsymiar.device2device.wrapper.NetworkWrapper;
import com.tsymiar.device2device.wrapper.TimeWrapper;

import java.util.Arrays;

public class SelectActivity extends AppCompatActivity implements EventHandle {
    private static final String TAG = SelectActivity.class.getCanonicalName();
    public static final int RequestStorage = 10001;
    public static final int RequestFloat = 10002;
    public static final int RequestAudio = 10003;
    public static final int RequestHttpFolder = 10004;
    @SuppressLint("StaticFieldLeak")
    static SelectActivity mainActivity;
    BroadcastReceiverClass mBroadcastReceiverClass = new BroadcastReceiverClass();
    private ServiceConnection mServiceConnection = null;
    SubscribeService mFloatService;
    private int mGValue = 1;
    Intent mPublisherIntent;
    Intent mSubscribeIntent;
    Button mKcpBtn;
    private long mCurTime;
    ChatBoxDialog mChatBoxDialog;
    FileMsgDialog mFileMsgDialog;
    HttpFileService mHttpFileService;

    private static boolean mKcpStart = false;

    public static SelectActivity getInstance() {
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
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            TextView tv;
            switch (msg.what) {
                case Receiver.MESSAGE:
                    tv = findViewById(R.id.txt_status);
                    tv.setText(msg.obj.toString());
                    break;
                case Receiver.FILE_PROGRESS: {
                    // 格式: "status|current|total" → 路由到文件传输对话框
                    String data = msg.obj.toString();
                    if (mFileMsgDialog != null) {
                        String[] parts = data.split("\\|", 3);
                        if (parts.length == 3) {
                            long current = Long.parseLong(parts[1]);
                            long total = Long.parseLong(parts[2]);
                            mFileMsgDialog.updateProgress(current, total, parts[0]);
                        }
                    }
                    break;
                }
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
                case Receiver.KCP_VIEW:
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
        setContentView(R.layout.activity_select);
        // a call to a native method
        TextView textView = findViewById(R.id.sample_text);
        textView.setText((new CallbackWrapper()).stringGetJNI());
        Utils.Time time = new Utils.Time();

        CallbackWrapper.initJvmEnv(JvmMethods.TAG);
        CallbackWrapper.callJavaMethod("hello", 0, "non-static call", false);
        CallbackWrapper.callJavaMethod("welcome", 2, "callJavaStaticMethod!", true);

        TimeWrapper.getBootTimestamp();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(SubscribeService.BROADCAST_ACTION);
        this.registerReceiver(mBroadcastReceiverClass, intentFilter);

        setServiceConnection(new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                SubscribeService.Binder binder = (SubscribeService.Binder)service;
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

        mSubscribeIntent = new Intent(SelectActivity.this, SubscribeService.class);
        mPublisherIntent = new Intent(SelectActivity.this, PublishService.class);

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
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } while (true);
        }).start();

        findViewById(R.id.btn_texture)
                .setOnClickListener(v -> startActivity(new Intent(SelectActivity.this, TextureActivity.class)));
        findViewById(R.id.btn_wave)
                .setOnClickListener(v -> startActivity(new Intent(SelectActivity.this, WaveActivity.class)));
        findViewById(R.id.btn_chart)
                .setOnClickListener(v -> startActivity(new Intent(SelectActivity.this, GraphActivity.class)));
        findViewById(R.id.btn_bluetooth)
                .setOnClickListener(v -> startActivity(new Intent(SelectActivity.this, ConnectActivity.class)));
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
            event.setEvent("event: " + mGValue);
            notify.notifyListeners(event);
            mGValue++;
            TextView tv = findViewById(R.id.txt_event);
            tv.setText(event.getEvent().toString() + ", value = " + mGValue);
        });
        WifiManager manager = (WifiManager)this.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        assert manager != null;
        WifiManager.MulticastLock wifiLock = manager.createMulticastLock("localWifi");
        findViewById(R.id.btn_server).setOnClickListener(v -> {
            wifiLock.acquire();
            NetworkWrapper.startUdpServer(8899);
        });
        findViewById(R.id.btn_client).setOnClickListener(v -> {
            String text = Utils.MD5(mGValue + "").substring(0, 6);
            NetworkWrapper.sendUdpData(text, text.length());
            mGValue++;
            if (wifiLock.isHeld()) {
                wifiLock.release();
            }
        });
        findViewById(R.id.btn_subscribe).setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please select Device2Device to set MANAGE_OVERLAY_PERMISSION", Toast.LENGTH_SHORT).show();
                startActivityForResult(
                        new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
                        RequestFloat);
            } else {
                startService(mSubscribeIntent);
            }
        });
        findViewById(R.id.btn_publisher).setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please select Device2Device to set MANAGE_OVERLAY_PERMISSION", Toast.LENGTH_SHORT).show();
                startActivityForResult(
                        new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
                        RequestFloat);
            } else {
                startService(mPublisherIntent);
            }
        });
        findViewById(R.id.btn_tcp).setOnClickListener(v -> NetworkWrapper.startTcpServer(8700));
        mKcpBtn = findViewById(R.id.btn_ikcp);
        mKcpBtn.setOnClickListener(v -> {
            if (!mKcpStart) {
                NetworkWrapper.startKcpServer(8090);
                NetworkWrapper.startKcpClient("127.0.0.1", 8090);
                mKcpStart = true;
            } else {
                Message msg = new Message();
                msg.obj = getString(R.string.kcprun);
                msg.what = Receiver.KCP_VIEW;
                handler.sendMessage(msg);
            }
        });
        if (savedInstanceState != null && mChatBoxDialog != null) {
            mChatBoxDialog.restoreState(savedInstanceState);
        }
        findViewById(R.id.btn_chat).setOnClickListener(v ->
        {
            mChatBoxDialog = new ChatBoxDialog(SelectActivity.this);
            mChatBoxDialog.show();
        });
        findViewById(R.id.btn_file_trans).setOnClickListener(v ->
        {
            mFileMsgDialog = FileMsgDialog.newInstance();
            mFileMsgDialog.show(getSupportFragmentManager(), "file_trans");
        });
        findViewById(R.id.btn_http_server).setOnClickListener(v ->
        {
            // 打开系统文件夹选择器
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, RequestHttpFolder);
        });

        // 网络服务区域折叠/展开
        final LinearLayout networkContent = findViewById(R.id.network_content);
        final TextView networkArrow = findViewById(R.id.network_arrow);
        findViewById(R.id.network_header).setOnClickListener(v -> {
            if (networkContent.getVisibility() == View.VISIBLE) {
                networkContent.setVisibility(View.GONE);
                networkArrow.setText("▼");
            } else {
                networkContent.setVisibility(View.VISIBLE);
                networkArrow.setText("▲");
            }
        });
    }

    private class BroadcastReceiverClass extends BroadcastReceiver {
        @SuppressLint("SetTextI18n")
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!SubscribeService.BROADCAST_ACTION.equals(intent.getAction())) {
                return;
            }
            TextView tvStatus = findViewById(R.id.txt_status);
            String subscribe = intent.getStringExtra("Subscribe");
            System.out.println("Subscribe status ==> " + subscribe);
            if ("SUCCESS".equals(subscribe)) {
                PubSubSetting setting = PubSubSetting.getSetting();
                if (setting != null) {
                    Log.i(TAG, setting.toString());
                }
                int ret = CallbackWrapper.StartSubscribe(PubSubSetting.getAddr(), PubSubSetting.getPort(),
                        PubSubSetting.getTopic(), "txt_status", R.id.txt_status);
                if (ret < 0) {
                    tvStatus.setText("Subscribe failed!");
                    Log.e(TAG, "Subscribe failed with error code: " + ret);
                } else {
                    Toast.makeText(SelectActivity.this, "Success", Toast.LENGTH_SHORT).show();
                }
            } else if (subscribe != null) {
                Log.i(TAG, "Subscribe with " + subscribe);
                tvStatus.setText("Subscribe: " + subscribe);
            }
            String publish = intent.getStringExtra("Publish");
            if ("SUCCESS".equals(publish)) {
                PubSubSetting setting = PubSubSetting.getSetting();
                if (setting != null) {
                    Log.i(TAG, "Publish status ==> " + publish + ":\n" + setting);
                }
                if (PubSubSetting.getAddr().isEmpty() || PubSubSetting.getPort() == 0) {
                    Toast.makeText(SelectActivity.this, "confirm subscribe first", Toast.LENGTH_SHORT).show();
                } else {
                    CallbackWrapper.Publish(PubSubSetting.getTopic(), PubSubSetting.getPayload(), PubSubSetting.getAddr(), PubSubSetting.getPort());
                }
            } else if (publish != null) {
                Log.i(TAG, "Publish status: " + publish);
            }
        }
    }

    @Override
    public void onDestroy() {
        SelectActivity.this.unregisterReceiver(mBroadcastReceiverClass);
        stopService(mSubscribeIntent);
        stopService(mPublisherIntent);
        if (mFloatService != null) {
            mFloatService.closeWindow();
        }
        if (mHttpFileService != null) {
            mHttpFileService.stop();
        }
        super.onDestroy();
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RequestFloat) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show();
            } else {
                startService(new Intent(SelectActivity.this, SubscribeService.class));
            }
        }
        if (requestCode == RequestHttpFolder && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                // 持久化读取权限
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);

                startHttpServer(treeUri);
            }
        }
        if (requestCode == ChatBoxDialog.CHAT_FILE_REQUEST && resultCode == RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                if (mChatBoxDialog != null) {
                    mChatBoxDialog.handleFileResult(uri);
                }
            }
        }
    }

    /**
     * 从 DocumentTree URI 中提取实际文件路径
     */
    @SuppressLint("SetTextI18n")
    private void startHttpServer(Uri treeUri) {
        final int HTTP_PORT = 8080;

        // 停止旧服务器
        if (mHttpFileService != null) {
            mHttpFileService.stop();
        }

        // 使用 SAF DocumentFile 模式（解决 Android 10+ Scoped Storage 空目录问题）
        mHttpFileService = new HttpFileService(this, treeUri, HTTP_PORT);
        mHttpFileService.setStatusCallback(message -> {
            Message msg = new Message();
            msg.what = Receiver.MESSAGE;
            msg.obj = message;
            handler.sendMessage(msg);
        });

        String localIp = Utils.getLocalWifiIp(this);
        String accessUrl = "http://" + localIp + ":" + HTTP_PORT + "/";
        mHttpFileService.setAccessUrl(accessUrl);

        if (mHttpFileService.start()) {
            String folderName = mHttpFileService.getRootDisplayName();
            Toast.makeText(this, "HTTP 服务已启动\n访问地址: " + accessUrl, Toast.LENGTH_LONG).show();

            TextView tv = findViewById(R.id.txt_status);
            tv.setText("🌐 HTTP 文件服务\n访问地址: " + accessUrl + "\n共享目录: " + folderName);
        } else {
            Toast.makeText(this, "HTTP 服务启动失败", Toast.LENGTH_SHORT).show();
        }
    }
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mChatBoxDialog != null) {
            mChatBoxDialog.saveState(outState);
        }
    }
    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        /* Called when the activity is first created. */
        long mLastTime = mCurTime;
        mCurTime = System.currentTimeMillis();
        if ((keyCode == KeyEvent.KEYCODE_BACK) && (mCurTime - mLastTime >= 800)) {
            Toast.makeText(SelectActivity.this, R.string.exit_app, Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
