package com.tsymiar.device2device.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.activity.SelectActivity;
import com.tsymiar.device2device.utils.FileMsgStore;
import com.tsymiar.device2device.utils.Utils;
import com.tsymiar.device2device.wrapper.NetworkWrapper;

/**
 * 文件传输「接收端」前台服务。
 *
 * 之前服务端跑在 FileMsgDialog 里：对话框一销毁（返回键 / 旋转 / 切走）
 * 就跟着 onDestroy 停掉，对端自然连不上 —— 这是"本机自测一切正常、
 * 跨设备死活连不上"的另一个根因（自测时对话框一直开着，看不出来）。
 *
 * 现在照 HttpServerService / SshServerService 的套路搬进前台服务：
 * 常驻通知 + START_STICKY，对话框关了照样收；只能显式点「Stop Server」停止。
 */
public class FileMsgServerService extends Service {
    private static final String TAG = "FileMsgServerService";

    private static final String CHANNEL_ID = "file_msg_server";
    private static final int NOTIFICATION_ID = 1003;
    private static final String PREFS = "file_msg_server_prefs";
    private static final String PREFS_PORT = "port";

    public static final String ACTION_START = "com.tsymiar.device2device.filemsg.ACTION_START";
    public static final String ACTION_STOP = "com.tsymiar.device2device.filemsg.ACTION_STOP";
    public static final String ACTION_STATE = "com.tsymiar.device2device.filemsg.ACTION_STATE";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_REASON = "reason";
    public static final String STATE_STARTED = "started";
    public static final String STATE_STOPPED = "stopped";
    public static final String STATE_FAILED = "failed";

    private static volatile boolean sRunning = false;
    private static volatile int sPort = 0;

    public static boolean isRunning() {
        return sRunning;
    }

    public static int getPort() {
        return sPort;
    }

    /** 对端要填的地址：ip:port */
    public static String getEndpoint(Context context) {
        return Utils.getLocalWifiIp(context) + ":" + sPort;
    }

    /** 启动服务（必须前台组件调用：Android 12+ 禁止后台启动前台服务） */
    public static void start(Context context, int port) {
        if (port <= 0) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(PREFS_PORT, port).apply();
        Intent intent = new Intent(context, FileMsgServerService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PORT, port);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** 停止服务（清掉持久化的端口，避免 START_STICKY 误重启） */
    public static void stop(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(PREFS_PORT).apply();
        context.stopService(new Intent(context, FileMsgServerService.class));
    }

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
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int port = intent != null ? intent.getIntExtra(EXTRA_PORT, 0) : 0;
        if (port <= 0) {
            // 进程被回收后按 START_STICKY 重投递时 intent 可能为 null，从持久化端口恢复
            port = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(PREFS_PORT, 0);
        }
        if (port <= 0) {
            Log.w(TAG, "no port persisted, stopping");
            stopSelf();
            return START_NOT_STICKY;
        }
        startServer(port);
        return START_STICKY;
    }

    private synchronized void startServer(int port) {
        // 换端口重启：先停掉旧实例
        NetworkWrapper.stopFileMsgServer();

        FileMsgStore.applySavePath(getApplicationContext());
        int ret = NetworkWrapper.startFileMsgServer(port);
        if (ret >= 0) {
            sRunning = true;
            sPort = port;
            enterForeground(port);
            acquireKeepAliveLocks();
            Log.i(TAG, "file msg server started on port " + port);
            broadcastState(STATE_STARTED, null);
        } else {
            String reason = "start failed (ret=" + ret + ")，端口 " + port + " 可能被占用";
            Log.e(TAG, reason);
            sRunning = false;
            sPort = 0;
            broadcastState(STATE_FAILED, reason);
            stopSelf();
        }
    }

    private synchronized void stopServer() {
        NetworkWrapper.stopFileMsgServer();
        releaseKeepAliveLocks();
        if (sRunning) {
            sRunning = false;
            sPort = 0;
            broadcastState(STATE_STOPPED, null);
        }
    }

    private void enterForeground(int port) {
        if (notificationManager == null) {
            notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "文件接收服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("文件接收服务后台运行常驻通知");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, SelectActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, piFlags);

        Intent stopIntent = new Intent(this, FileMsgServerService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("文件接收服务运行中")
                .setContentText("对端连接 " + getEndpoint(this)
                        + " · 保存到 " + FileMsgStore.publicDir())
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

    /**
     * 保活锁组合：
     * - PARTIAL_WAKE_LOCK：锁屏后 CPU 不被挂起，accept / 读写线程持续运行；
     * - WifiLock(FULL_HIGH_PERF)：防止息屏后 Wi-Fi 被降频休眠，
     *   否则会出现"服务在跑但对端连不上"（本机 127.0.0.1 自测发现不了）。
     */
    private void acquireKeepAliveLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "device2device:file-msg-server");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "acquire wake lock failed: " + e.getMessage());
        }
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "device2device:file-msg-server");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "acquire wifi lock failed: " + e.getMessage());
        }
    }

    private void releaseKeepAliveLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
    }

    private void broadcastState(String state, String reason) {
        Intent intent = new Intent(ACTION_STATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATE, state);
        if (reason != null) intent.putExtra(EXTRA_REASON, reason);
        if (STATE_STARTED.equals(state)) intent.putExtra(EXTRA_PORT, sPort);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        stopServer();
        stopForeground(true);
        super.onDestroy();
    }
}
