package com.tsymiar.device2device.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.activity.SelectActivity;
import com.tsymiar.device2device.utils.Utils;

/**
 * HTTP 文件服务前台服务。
 *
 * 目的：让 SelectActivity 退出/锁屏后服务仍能后台运行，并通过常驻通知
 * 降低进程被系统回收的概率。退出页面不再自动停止服务，只能显式点击
 * 「停止 HTTP」或系统杀掉进程后由 START_STICKY 从持久化的目录 URI 重建。
 */
public class HttpServerService extends Service {
    private static final String TAG = "HttpServerService";
    public static final int HTTP_PORT = 8080;

    private static final String CHANNEL_ID = "http_file_server";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREFS = "http_server_prefs";
    private static final String PREFS_TREE_URI = "tree_uri";

    public static final String ACTION_START = "com.tsymiar.device2device.http.ACTION_START";
    public static final String ACTION_STOP = "com.tsymiar.device2device.http.ACTION_STOP";
    public static final String ACTION_STATE = "com.tsymiar.device2device.http.ACTION_STATE";
    public static final String EXTRA_TREE_URI = "tree_uri";
    public static final String EXTRA_STATE = "state";
    public static final String STATE_STARTED = "started";
    public static final String STATE_STOPPED = "stopped";
    public static final String STATE_FAILED = "failed";

    /** 同进程内的状态快照，供 SelectActivity 读取恢复 UI */
    private static volatile boolean sRunning = false;
    private static volatile String sAccessUrl = "";
    private static volatile String sFolder = "";

    public static boolean isRunning() {
        return sRunning;
    }

    public static String getAccessUrl() {
        return sAccessUrl;
    }

    public static String getFolder() {
        return sFolder;
    }

    /** 启动服务（必须从前台组件调用，Android 12+ 禁止后台启动前台服务） */
    public static void start(Context context, Uri treeUri) {
        if (treeUri == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREFS_TREE_URI, treeUri.toString()).apply();
        Intent intent = new Intent(context, HttpServerService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TREE_URI, treeUri.toString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** 停止服务（清理持久化目录，避免 START_STICKY 误重启） */
    public static void stop(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(PREFS_TREE_URI).apply();
        context.stopService(new Intent(context, HttpServerService.class));
    }

    private HttpBrowserService httpBrowserService;
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

        // 重新启动场景：系统在进程被回收后按 START_STICKY 重投递（intent 可能为 null），
        // 此时从持久化目录恢复，让服务自动续跑。
        String uriStr = intent != null ? intent.getStringExtra(EXTRA_TREE_URI) : null;
        if (uriStr == null) {
            uriStr = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(PREFS_TREE_URI, null);
        }
        if (uriStr == null) {
            Log.w(TAG, "no tree uri, http service will stop");
            stopSelf();
            return START_NOT_STICKY;
        }
        startServer(Uri.parse(uriStr));
        return START_STICKY;
    }

    private synchronized void startServer(Uri treeUri) {
        // 换目录重启：先停掉旧实例再启动新的
        if (httpBrowserService != null) {
            httpBrowserService.stop();
            httpBrowserService = null;
        }

        HttpBrowserService service = new HttpBrowserService(getApplicationContext(), treeUri, HTTP_PORT);
        String url = "http://" + Utils.getLocalWifiIp(this) + ":" + HTTP_PORT + "/";
        service.setAccessUrl(url);

        if (service.start()) {
            httpBrowserService = service;
            sRunning = true;
            sAccessUrl = url;
            sFolder = service.getRootDisplayName();
            enterForeground(url, sFolder);
            acquireKeepAliveLocks();
            Log.i(TAG, "HTTP 文件服务已启动: " + url + " 根目录: " + sFolder);
            broadcastState(STATE_STARTED);
        } else {
            String reason = "start failed: treeUri=" + treeUri + ", url=" + url;
            Log.e(TAG, reason);
            sRunning = false;
            sAccessUrl = "";
            sFolder = "";
            broadcastState(STATE_FAILED);
            stopSelf();
        }
    }

    private synchronized void stopServer() {
        if (httpBrowserService != null) {
            httpBrowserService.stop();
            httpBrowserService = null;
        }
        releaseKeepAliveLocks();
        if (sRunning) {
            sRunning = false;
            broadcastState(STATE_STOPPED);
        }
        sAccessUrl = "";
        sFolder = "";
    }

    private void enterForeground(String url, String folder) {
        if (notificationManager == null) {
            notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "HTTP 文件服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("HTTP 文件服务后台运行常驻通知");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, SelectActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, piFlags);

        // 通知栏“停止服务”按钮：直接向本服务投递 ACTION_STOP
        Intent stopIntent = new Intent(this, HttpServerService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("HTTP 文件服务运行中")
                .setContentText("访问地址 " + url + " · " + folder)
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
     * - PARTIAL_WAKE_LOCK：锁屏后 CPU 不被挂起，HTTP accept/读写线程持续运行；
     * - WifiLock(WIFI_MODE_FULL_HIGH_PERF)：防止屏幕关闭后 Wi-Fi 被系统降频/休眠，
     *   否则会出现“服务在跑却连不上”的现象。
     * 结合前台服务 + START_STICKY，锁屏/应用退出后服务继续保活。
     */
    private void acquireKeepAliveLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "device2device:http-server");
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
                        "device2device:http-server");
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
        } catch (Exception ignored) {}
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {}
    }

    private void broadcastState(String state) {
        Intent intent = new Intent(ACTION_STATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATE, state);
        if (STATE_FAILED.equals(state)) {
            intent.putExtra("reason", "端口被占用或目录无权访问");
        }
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
