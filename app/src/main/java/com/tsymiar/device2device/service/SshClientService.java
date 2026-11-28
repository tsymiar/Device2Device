package com.tsymiar.device2device.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.activity.SshClientActivity;
import com.tsymiar.device2device.utils.SshClientHolder;

/** SSH 连接的前台服务：状态栏常驻通知，下拉点击即可回到会话界面。 */
public class SshClientService extends Service {
    private static final String CHANNEL_ID = "ssh_client_foreground";
    private static final int NOTIFICATION_ID = 1002;

    public static final String ACTION_START = "com.tsymiar.device2device.ssh.START";
    public static final String ACTION_STOP = "com.tsymiar.device2device.ssh.STOP";
    public static final String EXTRA_TITLE = "title";

    public static void start(Context ctx, String title) {
        Intent i = new Intent(ctx, SshClientService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_TITLE, title);
        ctx.startForegroundService(i);
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, SshClientService.class);
        i.setAction(ACTION_STOP);
        ctx.stopService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        // 进程被杀后服务重启时连接已不存在，别留一个误导性的常驻通知
        if (!SshClientHolder.get().core().isConnected()) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        String title = intent.getStringExtra(EXTRA_TITLE);
        if (TextUtils.isEmpty(title)) {
            title = getString(R.string.ssh_client_title);
        }
        startForeground(NOTIFICATION_ID, buildNotification(title));
        return START_STICKY;
    }

    private Notification buildNotification(String title) {
        Intent open = new Intent(this, SshClientActivity.class);
        String sid = SshClientHolder.get().getSessionId();
        if (!TextUtils.isEmpty(sid)) {
            open.putExtra("conn_id", sid);
        }
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(getString(R.string.ssh_notify_title))
                .setContentText(title)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void createChannel() {
        com.tsymiar.device2device.utils.NotificationHelper.ensureChannel(
                this, CHANNEL_ID, getString(R.string.ssh_notify_channel),
                NotificationManager.IMPORTANCE_LOW, null, false);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
