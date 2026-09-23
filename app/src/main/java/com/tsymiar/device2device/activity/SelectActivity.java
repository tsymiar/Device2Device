package com.tsymiar.device2device.activity;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.text.TextUtils;
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
import com.tsymiar.device2device.dialog.GameDialog;
import com.tsymiar.device2device.entity.PubSubSetting;
import com.tsymiar.device2device.entity.Receiver;
import com.tsymiar.device2device.event.EventEntity;
import com.tsymiar.device2device.event.EventHandle;
import com.tsymiar.device2device.event.EventNotify;
import com.tsymiar.device2device.service.HttpServerService;
import com.tsymiar.device2device.service.PublishService;
import com.tsymiar.device2device.service.SshServerService;
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
    private static final int RequestBatteryOptimize = 10005;
    /** 单进程内只引导一次“忽略电池优化”，避免每次启动 HTTP 都打扰 */
    private static boolean sBatteryPromptShown = false;
    @SuppressLint("StaticFieldLeak")
    static SelectActivity mainActivity;
    BroadcastReceiverClass mBroadcastReceiverClass = new BroadcastReceiverClass();
    private ServiceConnection mServiceConnection = null;
    SubscribeService mFloatService;
    private int mGValue = 1;
    Intent mPublisherIntent;
    Intent mSubscribeIntent;
    /** 原 KCP 按钮：现在是「客户端发一串随机数」 */
    Button mKcpBtn;
    /** 原 TCP 按钮：现在是「KCP 服务启停」 */
    Button mKcpServerBtn;
    /** KCP 服务端最近收到的几条消息（server 按钮下方滚动显示） */
    private final java.util.List<String> mKcpLog = new java.util.ArrayList<>();
    /** KCP 客户端最近几条消息（client 按钮下方滚动显示） */
    private final java.util.List<String> mKcpStatusLog = new java.util.ArrayList<>();
    private boolean mKcpServerStart = false;
    private boolean mKcpClientStart = false;
    private long mCurTime;
    ChatBoxDialog mChatBoxDialog;
    FileMsgDialog mFileMsgDialog;
    /** SSH 正在启动（还没收到服务广播）：这段时间置灰启动按钮，避免连点 */
    private boolean mSshStarting = false;
    /** 最近一次 SSH 启动失败的原因：显示在状态卡片里，直到下次启动成功或手动再启动 */
    private String mSshError = null;
    private final Runnable mSshStartTimeout = new Runnable() {
        @Override
        public void run() {
            if (!mSshStarting) return;
            setSshStarting(false);
            showSshState();
            if (!SshServerService.isRunning()) {
                Toast.makeText(SelectActivity.this, "SSH 服务启动超时", Toast.LENGTH_SHORT).show();
            }
        }
    };

    /** KCP 用同一个端口收（服务端）发（客户端，走 127.0.0.1 回环） */
    private static final int KCP_PORT = 8090;
    /** UDP 状态与收到的数据共用一路，靠「第一条是状态」区分落点 */
    private boolean mUdpStatusShown = false;

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
                    // 第一条是启动状态 → server 文字，之后是收到的数据 → client 文字
                    tv = findViewById(mUdpStatusShown ? R.id.txt_udp_client : R.id.txt_udp_server);
                    mUdpStatusShown = true;
                    tv.setText(msg.obj.toString());
                    break;
                case Receiver.UDP_CLIENT:
                    tv = findViewById(R.id.txt_udp_client);
                    tv.setText(msg.obj.toString());
                    break;
                case Receiver.TOAST:
                case Receiver.KAI_SUBSCRIBE:
                case Receiver.KAI_PUBLISHER:
                    Toast.makeText(getApplicationContext(), msg.obj.toString(), Toast.LENGTH_SHORT).show();
                    break;
                case Receiver.TEXTURE:
                    TextureActivity.log(msg.obj.toString());
                    break;
                case Receiver.KCP_VIEW:
                    mKcpBtn.setText(msg.obj.toString());
                    break;
                case Receiver.KCP_HINT:
                    // 服务端收到什么
                    appendKcpLog(msg.obj.toString());
                    break;
                case Receiver.KCP_CLIENT:
                    // 客户端的 sn / RTT
                    appendKcpStatus(msg.obj.toString());
                    break;
                case Receiver.MSG_HINT:
                    tv = findViewById(R.id.txt_hint);
                    tv.setText(msg.obj.toString());
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
        intentFilter.addAction(HttpServerService.ACTION_STATE);
        intentFilter.addAction(SshServerService.ACTION_STATE);
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
        findViewById(R.id.btn_udp_server).setOnClickListener(v -> {
            wifiLock.acquire();
            // 下一条 UDP_SERVER 是启动状态
            mUdpStatusShown = false;
            NetworkWrapper.startUdpServer(8899);
        });
        findViewById(R.id.btn_udp_client).setOnClickListener(v -> {
            String text = Utils.MD5(mGValue + "").substring(0, 6);
            NetworkWrapper.sendUdpData(text, text.length());
            mGValue++;
            if (wifiLock.isHeld()) {
                wifiLock.release();
            }
        });
        findViewById(R.id.btn_subscribe).setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                String title = getString(R.string.subscribe) + " " + getString(R.string.permission_required);
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage(R.string.overlay_permission_hint)
                        .setPositiveButton(R.string.go_to_settings, (dialog, which) -> {
                            dialog.dismiss();
                            startActivityForResult(
                                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
                                    RequestFloat);
                        })
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                        .show();
            } else {
                startService(mSubscribeIntent);
            }
        });
        findViewById(R.id.btn_publisher).setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                String title = getString(R.string.publish) + " " + getString(R.string.permission_required);
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage(R.string.overlay_permission_hint)
                        .setPositiveButton(R.string.go_to_settings, (dialog, which) -> {
                            dialog.dismiss();
                            startActivityForResult(
                                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
                                    RequestFloat);
                        })
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                        .show();
            } else {
                startService(mPublisherIntent);
            }
        });
        // KCP 服务启停（TCP 服务端代码保留在 native，这里不再调用）
        mKcpServerBtn = findViewById(R.id.btn_kcp_server);
        mKcpServerBtn.setText(R.string.kcp_server);
        mKcpServerBtn.setOnClickListener(v -> toggleKcpServer());
        // KCP 客户端：点一次发一串随机数
        mKcpBtn = findViewById(R.id.btn_kcp_client);
        mKcpBtn.setText(R.string.kcp_client);
        mKcpBtn.setOnClickListener(v -> sendKcpRandom());
        findViewById(R.id.btn_market).setOnClickListener(v ->
                startActivity(new Intent(SelectActivity.this, MarketActivity.class)));
        findViewById(R.id.btn_avatar).setOnClickListener(v ->
                startActivity(new Intent(SelectActivity.this, AvatarActivity.class)));
        findViewById(R.id.btn_deduction).setOnClickListener(v ->
                GameDialog.showDeduction(SelectActivity.this));

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
            Toast.makeText(SelectActivity.this, R.string.http_server_select_hint, Toast.LENGTH_SHORT).show();
            // 打开系统文件夹选择器
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, RequestHttpFolder);
        });
        // HTTP 前台服务只在显式停止时关闭，退出页面不影响后台服务
        findViewById(R.id.btn_http_stop).setOnClickListener(v ->
                HttpServerService.stop(SelectActivity.this));
        // 复制 HTTP 访问地址到剪贴板
        findViewById(R.id.btn_http_copy).setOnClickListener(v -> copyHttpAccessUrl());

        // SSH 服务同样交给前台服务：启动 / 复制连接信息 / 停止
        findViewById(R.id.btn_ssh_server).setOnClickListener(v -> startSshServer());
        findViewById(R.id.btn_ssh_stop).setOnClickListener(v ->
                SshServerService.stop(SelectActivity.this));
        findViewById(R.id.btn_ssh_copy).setOnClickListener(v -> copySshConnectionInfo());

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
        // 页面恢复时同步 HTTP / SSH 前台服务运行状态（若已在后台运行）
        showHttpState();
        showSshState();
    }

    private class BroadcastReceiverClass extends BroadcastReceiver {
        @SuppressLint("SetTextI18n")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (HttpServerService.ACTION_STATE.equals(action)) {
                String state = intent.getStringExtra(HttpServerService.EXTRA_STATE);
                TextView tvStatus = findViewById(R.id.txt_status);
                if (HttpServerService.STATE_STARTED.equals(state)) {
                    Toast.makeText(SelectActivity.this,
                            "HTTP 服务已启动\n访问地址: " + HttpServerService.getAccessUrl(),
                            Toast.LENGTH_LONG).show();
                    showHttpState();
                } else if (HttpServerService.STATE_FAILED.equals(state)) {
                    tvStatus.setText("HTTP 服务启动失败");
                    Toast.makeText(SelectActivity.this,
                            "HTTP 服务启动失败：端口被占用或目录无权访问", Toast.LENGTH_SHORT).show();
                } else if (HttpServerService.STATE_STOPPED.equals(state)) {
                    tvStatus.setText("HTTP 文件服务已停止");
                    showHttpState();
                }
                return;
            }
            if (SshServerService.ACTION_STATE.equals(action)) {
                String state = intent.getStringExtra(SshServerService.EXTRA_STATE);
                TextView tvStatus = findViewById(R.id.txt_status);
                handler.removeCallbacks(mSshStartTimeout);
                if (SshServerService.STATE_STARTED.equals(state)) {
                    setSshStarting(false);
                    mSshError = null;
                    tvStatus.setText("SSH 服务已启动 · " + SshServerService.getConnectionCommand());
                    Toast.makeText(SelectActivity.this, "SSH 服务已启动", Toast.LENGTH_SHORT).show();
                    showSshState();
                } else if (SshServerService.STATE_FAILED.equals(state)) {
                    String reason = intent.getStringExtra(SshServerService.EXTRA_REASON);
                    setSshStarting(false);
                    mSshError = TextUtils.isEmpty(reason) ? "端口被占用或初始化失败" : reason;
                    tvStatus.setText("SSH 服务启动失败");
                    showSshState();   // 失败原因挂在 txt_hint 上，不再弹 Toast
                } else if (SshServerService.STATE_STOPPED.equals(state)) {
                    setSshStarting(false);
                    mSshError = null;
                    tvStatus.setText("SSH 服务已停止");
                    showSshState();
                }
                return;
            }
            if (!SubscribeService.BROADCAST_ACTION.equals(action)) {
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
                    String topic = PubSubSetting.getTopic();
                    if (topic == null || topic.isEmpty()) {
                        Toast.makeText(SelectActivity.this, "publish topic is empty", Toast.LENGTH_SHORT).show();
                    } else {
                        CallbackWrapper.Publish(topic, PubSubSetting.getPayload(), PubSubSetting.getAddr(), PubSubSetting.getPort());
                    }
                }
            } else if (publish != null) {
                Log.i(TAG, "Publish status: " + publish);
            }
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(mSshStartTimeout);
        SelectActivity.this.unregisterReceiver(mBroadcastReceiverClass);
        stopService(mSubscribeIntent);
        stopService(mPublisherIntent);
        if (mFloatService != null) {
            mFloatService.closeWindow();
        }
        // KCP 是进程内的收发线程：退出页面就收掉，免得端口一直占着、线程空转
        if (mKcpServerStart) {
            NetworkWrapper.stopKcpServer();
            mKcpServerStart = false;
        }
        if (mKcpClientStart) {
            NetworkWrapper.stopKcpClient();
            mKcpClientStart = false;
        }
        // HTTP 文件服务由前台服务托管：退出页面不停止，后台继续运行
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showHttpState();
        showSshState();
        // HTTP / SSH 运行中且未处于系统“忽略电池优化”白名单时，前台引导一次
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && (HttpServerService.isRunning() || SshServerService.isRunning())) {
            maybeRequestBatteryOptimization();
        }
        // START_STICKY 重建服务可能晚于页面恢复，稍后再同步一次
        handler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                showHttpState();
                showSshState();
            }
        }, 400);
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
        if (requestCode == RequestBatteryOptimize) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "已允许忽略电池优化，锁屏保活已加强", Toast.LENGTH_SHORT).show();
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
     * 启动前台服务承载的 HTTP 文件服务（进程回收后 START_STICKY 自动续跑）。
     * 启动结果经由 HttpServerService.ACTION_STATE 广播回执刷新 UI。
     */
    private void startHttpServer(Uri treeUri) {
        HttpServerService.start(this, treeUri);
    }

    /**
     * 保活引导：Doze/厂商后台限制会掐断息屏后的联网，导致“服务在跑但连不上”。
     * 已处于“忽略电池优化”白名单或本次进程提示过则跳过，仅引导一次。
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    private void maybeRequestBatteryOptimization() {
        if (sBatteryPromptShown) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        sBatteryPromptShown = true;
        new AlertDialog.Builder(this)
                .setTitle("后台保活")
                .setMessage("为保证锁屏/切后台后 HTTP 文件服务仍可被访问，建议允许本应用“忽略电池优化”，"
                        + "系统将不会在息屏后限制本应用的联网。")
                .setPositiveButton("去设置", (dialog, which) -> {
                    dialog.dismiss();
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    try {
                        startActivityForResult(intent, RequestBatteryOptimize);
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNegativeButton("暂不", (dialog, which) -> dialog.dismiss())
                .show();
    }

    /** KCP 服务启停：收 UDP 8090，收到的内容打到 server 下方文字区 */
    private void toggleKcpServer() {
        if (!mKcpServerStart) {
            int ret = NetworkWrapper.startKcpServer(KCP_PORT);
            if (ret == 0 || ret == 1) {
                mKcpServerStart = true;
                mKcpServerBtn.setText(R.string.kcp_server_stop);
                updateKcpTextVisibility();
            }
        } else {
            NetworkWrapper.stopKcpServer();
            // 服务端停了，客户端跟着一起收
            if (mKcpClientStart) {
                NetworkWrapper.stopKcpClient();
                mKcpClientStart = false;
            }
            mKcpServerStart = false;
            mKcpServerBtn.setText(R.string.kcp_server);
            clearKcpViews();
        }
    }

    /** 停服务时清掉 KCP 两个文字区的内容并把它们收起来 */
    private void clearKcpViews() {
        mKcpLog.clear();
        mKcpStatusLog.clear();
        setKcpText(R.id.txt_kcp_server, "");
        setKcpText(R.id.txt_kcp_client, "");
        updateKcpTextVisibility();
    }

    /** 谁 start 就显示谁的文字区，都停了整行收回 */
    private void updateKcpTextVisibility() {
        View row = findViewById(R.id.kcp_text_row);
        if (row != null) {
            row.setVisibility(mKcpServerStart || mKcpClientStart ? View.VISIBLE : View.GONE);
        }
        setKcpViewVisible(R.id.txt_kcp_server, mKcpServerStart);
        setKcpViewVisible(R.id.txt_kcp_client, mKcpClientStart);
    }

    private void setKcpViewVisible(int id, boolean visible) {
        TextView tv = findViewById(id);
        if (tv != null) {
            tv.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setKcpText(int id, String text) {
        TextView tv = findViewById(id);
        if (tv != null) {
            tv.setText(text);
        }
    }

    /** 每次发送的随机数长度：16 个十六进制字符（= 8 个随机字节） */
    private static final int KCP_RANDOM_CHARS = 16;

    /**
     * KCP 客户端：首次点击顺带把客户端拉起来，之后每次发一串十六进制随机数给本机服务端。
     * 服务端会原样回射，客户端收到后把 sn / RTT 打到 client 下方的文字区。
     */
    @SuppressLint("SetTextI18n")
    private void sendKcpRandom() {
        if (!mKcpClientStart) {
            int ret = NetworkWrapper.startKcpClient("127.0.0.1", KCP_PORT);
            mKcpClientStart = (ret == 0 || ret == 1);
            if (!mKcpClientStart) {
                appendKcpStatus("KCP client start failed(" + ret + ")");
                return;
            }
            updateKcpTextVisibility();
        }
        if (!mKcpServerStart) {
            appendKcpStatus("KCP server not running, start it first");
        }
        // 随机数用十六进制：一眼能看出有没有被截断或错位
        String random = randomHex(KCP_RANDOM_CHARS);
        int ret = NetworkWrapper.sendKcpData(random, random.length());
        if (ret < 0) {
            appendKcpStatus("KCP send failed(" + ret + "): " + random);
        }
    }

    /** 生成十六进制随机串（大写），charCount 是要的字符数（奇数时按 (charCount+1)/2 字节生成后截断） */
    private static String randomHex(int charCount) {
        int byteCount = (charCount + 1) / 2;
        byte[] buf = new byte[byteCount];
        new java.security.SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(byteCount * 2);
        for (byte b : buf) {
            sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
            sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
        }
        return sb.substring(0, Math.min(charCount, sb.length()));
    }

    /** KCP server 按钮下方滚动显示最近 6 条（服务端收到什么） */
    private void appendKcpLog(String line) {
        mKcpLog.add(line);
        while (mKcpLog.size() > 6) {
            mKcpLog.remove(0);
        }
        setKcpText(R.id.txt_kcp_server, TextUtils.join("\n", mKcpLog));
    }

    /** KCP client 按钮下方滚动显示最近 6 条（启动 / 发送 / 回射的 sn 与 RTT） */
    private void appendKcpStatus(String line) {
        mKcpStatusLog.add(line);
        while (mKcpStatusLog.size() > 6) {
            mKcpStatusLog.remove(0);
        }
        setKcpText(R.id.txt_kcp_client, TextUtils.join("\n", mKcpStatusLog));
    }

    /** 根据前台服务快照同步 HTTP 状态：地址卡片与复制/停止按钮可用性 */
    private void showHttpState() {
        boolean running = HttpServerService.isRunning();
        Button stopBtn = findViewById(R.id.btn_http_stop);
        if (stopBtn != null) {
            stopBtn.setEnabled(running);
        }
        Button copyBtn = findViewById(R.id.btn_http_copy);
        if (copyBtn != null) {
            copyBtn.setEnabled(running);
        }
        refreshServiceHint();
    }

    /** 把当前 HTTP 访问地址复制到系统剪贴板 */
    private void copyHttpAccessUrl() {
        if (!HttpServerService.isRunning()) {
            Toast.makeText(this, "HTTP 服务未运行，无法复制", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = HttpServerService.getAccessUrl();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Device2Device HTTP", url));
            Toast.makeText(this, getString(R.string.http_copy_done, url), Toast.LENGTH_LONG).show();
        }
    }

    /** 根据前台服务快照同步 SSH 状态：连接信息卡片与启动/复制/停止按钮可用性 */
    private void showSshState() {
        boolean running = SshServerService.isRunning();
        // 复制/停止这两个操作默认折叠，服务跑起来后才展开
        View actions = findViewById(R.id.ssh_actions);
        if (actions != null) {
            actions.setVisibility(running ? View.VISIBLE : View.GONE);
        }
        Button stopBtn = findViewById(R.id.btn_ssh_stop);
        if (stopBtn != null) {
            stopBtn.setEnabled(running);
        }
        Button copyBtn = findViewById(R.id.btn_ssh_copy);
        if (copyBtn != null) {
            copyBtn.setEnabled(running);
        }
        Button startBtn = findViewById(R.id.btn_ssh_server);
        if (startBtn != null && !mSshStarting) {
            // 运行中置灰并改文案，避免连点反复 startForegroundService 造成端口漂移
            startBtn.setEnabled(!running);
            startBtn.setText(running ? R.string.ssh_server_running : R.string.ssh_server);
        }
        refreshServiceHint();
    }

    /** 启动 SSH 服务：已在运行就只提示，启动过程中置灰按钮防连点 */
    private void startSshServer() {
        if (SshServerService.isRunning()) {
            Toast.makeText(this, "SSH 服务已在运行", Toast.LENGTH_SHORT).show();
            showSshState();
            return;
        }
        setSshStarting(true);
        mSshError = null;      // 重新开始时清掉上一次的错误，别让旧失败信息一直挂着
        TextView tvStatus = findViewById(R.id.txt_status);
        if (tvStatus != null) {
            tvStatus.setText("SSH 服务启动中…");
        }
        refreshServiceHint();
        SshServerService.start(SelectActivity.this);
        // 服务被系统限制启动等情况下可能一直收不到广播，到点也要把按钮放出来
        handler.removeCallbacks(mSshStartTimeout);
        handler.postDelayed(mSshStartTimeout, 8000);
    }

    private void setSshStarting(boolean starting) {
        mSshStarting = starting;
        Button startBtn = findViewById(R.id.btn_ssh_server);
        if (startBtn == null) {
            return;
        }
        startBtn.setEnabled(!starting);
        startBtn.setText(starting ? "SSH 服务启动中…" : getString(R.string.ssh_server));
    }

    /**
     * 状态卡片由 HTTP 与 SSH 共用：谁在跑就拼谁的连接信息（两个都跑就都显示），
     * 都停了留个空白占位，避免两个服务互相把对方那行覆盖掉。
     */
    @SuppressLint("SetTextI18n")
    private void refreshServiceHint() {
        TextView tv = findViewById(R.id.txt_hint);
        if (tv == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (HttpServerService.isRunning()) {
            sb.append("🌐 HTTP 文件服务\n访问地址: ").append(HttpServerService.getAccessUrl())
                    .append("\n共享目录: ").append(HttpServerService.getFolder());
        }
        if (SshServerService.isRunning()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("🔐 SSH 服务\n").append(SshServerService.getConnectionCommand())
                    .append("\n登录密码: ").append(SshServerService.getPassword())
                    .append("\n根目录: ").append(SshServerService.getRootDir());
        }
        // 启动失败的原因也挂在状态卡片上：通知/Toast 一闪而过，这里能一直看着排查
        if (!TextUtils.isEmpty(mSshError)) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("⚠️ SSH 服务启动失败\n").append(mSshError);
        }
        // KCP 的收发内容不再进这里：各自显示在 server / client 按钮下方的文字区
        tv.setText(sb.length() == 0 ? " " : sb.toString());
    }

    /** 复制 SSH 连接命令（带密码）到剪贴板 */
    private void copySshConnectionInfo() {
        if (!SshServerService.isRunning()) {
            Toast.makeText(this, R.string.ssh_not_running, Toast.LENGTH_SHORT).show();
            return;
        }
        String command = SshServerService.getConnectionCommand()
                + "   # password: " + SshServerService.getPassword();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Device2Device SSH", command));
            Toast.makeText(this, "连接信息已复制", Toast.LENGTH_SHORT).show();
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
