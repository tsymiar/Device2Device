package com.tsymiar.device2device.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.wrapper.NetworkWrapper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 分享发送的前台服务：状态栏实时进度，支持取消与删除远程残留（裸线程会被系统冻结网络） */
public class ShareSendService extends Service {
    private static final String TAG = "ShareSendService";
    private static final String CHANNEL_ID = "file_msg_share";
    private static final int NOTIFICATION_ID = 1004;
    /** 取消后等「删除远程文件」的时长 */
    private static final long DELETE_GRACE_MS = 60 * 1000;

    private static final String ACTION_START = "com.tsymiar.device2device.share.ACTION_START";
    private static final String ACTION_CANCEL = "com.tsymiar.device2device.share.ACTION_CANCEL";
    private static final String ACTION_DELETE = "com.tsymiar.device2device.share.ACTION_DELETE";
    private static final String EXTRA_IP = "ip";
    private static final String EXTRA_PORT = "port";
    private static final String EXTRA_FILES = "files";
    private static final String EXTRA_TOTAL = "total";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean cancelRequested;
    private volatile boolean cancelled;
    private volatile int fileIndex;
    private volatile int fileTotal;
    private volatile String currentFile = "";
    private volatile String peer = "";
    private Thread worker;
    private NotificationManager notificationManager;

    /** 浮窗选中设备后调用 */
    public static void start(Context context, String ip, int port, List<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        Intent intent = new Intent(context, ShareSendService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_IP, ip)
                .putExtra(EXTRA_PORT, port)
                .putStringArrayListExtra(EXTRA_FILES, new ArrayList<>(paths));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        com.tsymiar.device2device.utils.NotificationHelper.ensureChannel(
                this, CHANNEL_ID, getString(R.string.file_msg_share_channel),
                NotificationManager.IMPORTANCE_LOW, null, false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancelRequested = true;
            // 立刻让 native 在下一个分片边界停下
            new Thread(NetworkWrapper::cancelFileTransfer, "share-cancel").start();
            updateNotification(getString(R.string.file_msg_share_cancelling), 0, true, true);
            return START_NOT_STICKY;
        }
        if (ACTION_DELETE.equals(action)) {
            deleteRemoteAndStop();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent != null ? intent.getAction() : null)) {
            final String ip = intent.getStringExtra(EXTRA_IP);
            final int port = intent.getIntExtra(EXTRA_PORT, 0);
            final ArrayList<String> files = intent.getStringArrayListExtra(EXTRA_FILES);
            if (ip == null || port <= 0 || files == null || files.isEmpty()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            peer = ip + ":" + port;
            fileTotal = files.size();
            startForeground(NOTIFICATION_ID, buildNotification(
                    getString(R.string.file_msg_share_starting, peer), 0, false, true));
            startWorker(ip, port, files);
        }
        return START_NOT_STICKY;
    }

    private void startWorker(final String ip, final int port, final List<String> files) {
        if (worker != null) return;
        worker = new Thread(() -> runSend(ip, port, files), "share-send");
        worker.start();
        handler.post(progressTick);
    }

    private void runSend(String ip, int port, List<String> files) {
        int connect = NetworkWrapper.connectFileMsgServer(ip, port);
        Log.i(TAG, "connect(" + ip + ":" + port + ")=" + connect);
        if (connect < 0) {
            finishWith(getString(R.string.file_msg_share_failed, connect), false);
            return;
        }
        int ok = 0;
        int code = 0;
        for (int i = 0; i < files.size(); i++) {
            if (cancelRequested) break;
            fileIndex = i;
            currentFile = new File(files.get(i)).getName();
            int ret = NetworkWrapper.postLocalFile(files.get(i));
            Log.i(TAG, "postLocalFile(" + currentFile + ")=" + ret);
            if (ret == -2) {
                cancelled = true;
                break;
            }
            if (ret < 0) {
                code = ret;
                break;
            }
            ok++;
        }
        if (cancelled) {
            // 保留连接：对端还挂着半截文件，等用户决定是否删除
            updateNotification(getString(R.string.file_msg_share_cancelled, currentFile),
                    0, true, false);
            handler.postDelayed(this::disconnectAndStop, DELETE_GRACE_MS);
            return;
        }
        if (code < 0) {
            finishWith(getString(R.string.file_msg_share_failed, code), false);
        } else if (ok < files.size()) {
            finishWith(getString(R.string.file_msg_share_partial, ok, files.size()), false);
        } else {
            finishWith(getString(R.string.file_msg_share_done, ok, files.size()), true);
        }
    }

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (worker == null || !worker.isAlive() || cancelled || cancelRequested) return;
            long[] progress = readProgress();
            int pct = progress[1] > 0 ? (int) (progress[0] * 100 / progress[1]) : 0;
            updateNotification(getString(R.string.file_msg_share_notify_progress,
                    fileIndex + 1, fileTotal, currentFile, pct), pct, true, true);
            handler.postDelayed(this, 500);
        }
    };

    /** native 的 "current|total"（字节） */
    private long[] readProgress() {
        long[] out = new long[]{0, 0};
        String raw = NetworkWrapper.getFileTransferProgress();
        if (raw == null) return out;
        int bar = raw.indexOf('|');
        if (bar <= 0) return out;
        try {
            out[0] = Long.parseLong(raw.substring(0, bar).trim());
            out[1] = Long.parseLong(raw.substring(bar + 1).trim());
        } catch (NumberFormatException ignored) {
        }
        return out;
    }

    private void deleteRemoteAndStop() {
        final String name = currentFile;
        new Thread(() -> {
            int ret = name == null || name.isEmpty() ? -1 : NetworkWrapper.deletePartialFile(name);
            Log.i(TAG, "deletePartialFile(" + name + ")=" + ret);
            disconnectAndStop();
        }, "share-delete").start();
    }

    private void finishWith(String text, boolean success) {
        handler.removeCallbacks(progressTick);
        handler.removeCallbacksAndMessages(null);
        updateNotification(text, success ? 100 : 0, false, true);
        new Thread(NetworkWrapper::disconnectFileMsg, "share-disconnect").start();
        // 结果留几秒再收起
        handler.postDelayed(this::stopSelf, success ? 4000 : 8000);
    }

    private void disconnectAndStop() {
        handler.removeCallbacksAndMessages(null);
        new Thread(NetworkWrapper::disconnectFileMsg, "share-disconnect").start();
        stopSelf();
    }

    private void updateNotification(String text, int pct, boolean ongoing, boolean withCancel) {
        Log.d(TAG, "notify: " + text + " (" + pct + "%, ongoing=" + ongoing + ")");
        if (notificationManager == null) return;
        Notification notification = buildNotification(text, pct, ongoing, withCancel);
        if (ongoing) {
            startForeground(NOTIFICATION_ID, notification);
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification);
            stopForeground(false);
        }
    }

    private Notification buildNotification(String text, int pct, boolean ongoing, boolean withCancel) {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(getString(R.string.file_msg_share_title, peer))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setShowWhen(false);
        if (pct > 0) {
            builder.setProgress(100, pct, false);
        } else if (ongoing) {
            builder.setProgress(0, 0, true);
        }
        if (withCancel) {
            PendingIntent cancel = PendingIntent.getService(this, 1,
                    new Intent(this, ShareSendService.class).setAction(ACTION_CANCEL), piFlags);
            builder.addAction(new Notification.Action.Builder(null,
                    getString(R.string.file_msg_share_action_cancel), cancel).build());
        } else if (cancelled) {
            // 取消后给一次「删除远程文件」
            PendingIntent delete = PendingIntent.getService(this, 2,
                    new Intent(this, ShareSendService.class).setAction(ACTION_DELETE), piFlags);
            builder.addAction(new Notification.Action.Builder(null,
                    getString(R.string.file_msg_share_action_delete), delete).build());
        }
        return builder.build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
