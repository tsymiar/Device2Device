package com.tsymiar.device2device.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.activity.SelectActivity;
import com.tsymiar.device2device.utils.Utils;

import java.io.File;
import java.security.SecureRandom;

/**
 * SSH 服务前台服务（形态对齐 HttpServerService）。
 *
 * 只开放命令执行和交互 shell，登录用「用户名 + 密码」：密码首次启动随机生成并落到
 * SharedPreferences，页面 / 通知只展示连接串，避免通知栏里明文挂着密码。
 * 端口 2222 被占用时依次往后试 10 个，实际端口在状态里回传。
 */
public class SshServerService extends Service {
    private static final String TAG = "SshServerService";
    public static final int SSH_PORT = 2222;
    public static final String DEFAULT_USER = "d2d";

    private static final String CHANNEL_ID = "ssh_server";
    private static final int NOTIFICATION_ID = 1002;
    private static final String PREFS = "ssh_server_prefs";
    private static final String PREF_USER = "user";
    private static final String PREF_PASSWORD = "password";
    private static final int PORT_TRY_COUNT = 10;
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    public static final String ACTION_START = "com.tsymiar.device2device.ssh.ACTION_START";
    public static final String ACTION_STOP = "com.tsymiar.device2device.ssh.ACTION_STOP";
    public static final String ACTION_STATE = "com.tsymiar.device2device.ssh.ACTION_STATE";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_REASON = "reason";
    public static final String STATE_STARTED = "started";
    public static final String STATE_STOPPED = "stopped";
    public static final String STATE_FAILED = "failed";

    /** 同进程内的状态快照，供 SelectActivity 读取恢复 UI */
    private static volatile boolean sRunning = false;
    private static volatile String sUser = DEFAULT_USER;
    private static volatile String sPassword = "";
    private static volatile int sPort = SSH_PORT;
    private static volatile String sRoot = "";
    private static volatile String sCommand = "";

    public static boolean isRunning() {
        return sRunning;
    }

    public static String getUser() {
        return sUser;
    }

    public static String getPassword() {
        return sPassword;
    }

    public static int getPort() {
        return sPort;
    }

    public static String getRootDir() {
        return sRoot;
    }

    /** 形如：ssh d2d@192.168.1.5 -p 2222 */
    public static String getConnectionCommand() {
        return sCommand;
    }

    /** 启动服务（必须从前台组件调用，Android 12+ 禁止后台启动前台服务） */
    public static void start(Context context) {
        Intent intent = new Intent(context, SshServerService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            // 后台启动限制（Android 12+）等场景下系统会直接抛异常，兜住，别让点一下就闪退
            Log.e(TAG, "startForegroundService failed", e);
            try {
                context.startService(intent);
            } catch (Exception ignored) {
                // 系统不允许启动就到此为止，页面 Toast 由调用方处理
            }
        }
    }

    /** 停止服务 */
    public static void stop(Context context) {
        context.stopService(new Intent(context, SshServerService.class));
    }

    /** 重新生成登录密码（服务运行中会顺带重启，让新密码立刻生效） */
    public static void resetPassword(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_PASSWORD, randomPassword()).apply();
        if (sRunning) {
            start(context);
        }
    }

    private SshServerCore core;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private NotificationManager notificationManager;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startServer();
        return START_STICKY;
    }

    private synchronized void startServer() {
        stopCore();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String user = prefs.getString(PREF_USER, DEFAULT_USER);
        String password = prefs.getString(PREF_PASSWORD, null);
        if (password == null || password.isEmpty()) {
            password = randomPassword();
            prefs.edit().putString(PREF_PASSWORD, password).apply();
        }

        // shell 的根目录就用应用的私有外部目录：无需存储权限，也不会碰到别的 App 数据
        File root = getExternalFilesDir(null);
        if (root == null) {
            root = getFilesDir();
        }
        if (!root.exists() && !root.mkdirs()) {
            Log.w(TAG, "root dir unavailable: " + root);
        }

        // Android 12+ 要求 startForegroundService() 之后 5 秒内调用 startForeground()，
        // 而 sshd 首次启动要生成 RSA 主机密钥（慢，还可能几秒），所以先把通知挂上再启服务
        enterForeground("SSH 服务启动中…", root.getAbsolutePath());

        SshServerCore started = null;
        int port = SSH_PORT;
        String reason = "端口 " + SSH_PORT + "~" + (SSH_PORT + PORT_TRY_COUNT - 1) + " 均不可用";
        for (int i = 0; i < PORT_TRY_COUNT && started == null; i++) {
            port = SSH_PORT + i;
            try {
                SshServerCore candidate =
                        new SshServerCore(getApplicationContext(), root, port, user, password);
                if (candidate.start()) {
                    started = candidate;
                }
            } catch (Throwable t) {
                // sshd 在 Android 上偶发抛 RuntimeException / Error（缺算法、密钥文件不可写…），
                // 换端口也没用，直接兜住回传失败原因，不让异常冒到 onStartCommand 里把 App 打挂
                Log.e(TAG, "ssh server init failed on port " + port, t);
                reason = "SSH 初始化失败：" + t;
                break;
            }
        }

        if (started == null) {
            sRunning = false;
            sCommand = "";
            Log.e(TAG, "ssh server failed to bind port " + SSH_PORT + "~" + (SSH_PORT + PORT_TRY_COUNT - 1));
            broadcastState(STATE_FAILED, reason);
            stopForeground(true);
            stopSelf();
            return;
        }

        core = started;
        sRunning = true;
        sUser = user;
        sPassword = password;
        sPort = port;
        sRoot = root.getAbsolutePath();
        sCommand = "ssh " + user + "@" + Utils.getLocalWifiIp(this) + " -p " + port;

        enterForeground(sCommand, root.getAbsolutePath());
        acquireKeepAliveLocks();
        Log.i(TAG, "SSH 服务已启动: " + sCommand + " 根目录: " + sRoot);
        broadcastState(STATE_STARTED);
    }

    private synchronized void stopCore() {
        if (core != null) {
            core.stop();
            core = null;
        }
    }

    private synchronized void stopServer() {
        stopCore();
        releaseKeepAliveLocks();
        if (sRunning) {
            sRunning = false;
            broadcastState(STATE_STOPPED);
        }
        sCommand = "";
    }

    private void enterForeground(String command, String root) {
        if (notificationManager == null) {
            notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "SSH 服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("SSH 服务后台运行常驻通知");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, SelectActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, piFlags);

        Intent stopIntent = new Intent(this, SshServerService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        // 密码不放通知：通知栏对其它应用可见，密码只在页面里给出
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("SSH 服务运行中")
                .setContentText(command + " · " + root)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止服务", stopPi);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        startForeground(NOTIFICATION_ID, builder.build());
    }

    private void acquireKeepAliveLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "device2device:ssh-server");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "acquire wake lock failed: " + e.getMessage());
        }
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "device2device:ssh-server");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "acquire wifi lock failed: " + e.getMessage());
        }
    }

    private void releaseKeepAliveLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    private void broadcastState(String state) {
        broadcastState(state, null);
    }

    private void broadcastState(String state, String reason) {
        Intent intent = new Intent(ACTION_STATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATE, state);
        String text = reason;
        if ((text == null || text.isEmpty()) && STATE_FAILED.equals(state)) {
            text = "端口被占用或 SSH 初始化失败";
        }
        if (text != null && !text.isEmpty()) {
            intent.putExtra(EXTRA_REASON, text);
        }
        sendBroadcast(intent);
    }

    private static String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        stopServer();
        stopForeground(true);
        super.onDestroy();
    }
}
