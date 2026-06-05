package com.tsymiar.device2device.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.activity.DevicesActivity;

import java.lang.ref.WeakReference;

public class ReceiverService extends Service {
    private static final String CHANNEL_ID = "receiver_channel";
    private WindowManager wm;
    private WindowManager.LayoutParams wmParams;
    private View view;
    private boolean added;

    private float mTouchStartX, mTouchStartY;
    private TextView myview;
    private float x, y;
    private long mLastTime, mCurTime;

    private final RecvHandler handler = new RecvHandler(this);

    @SuppressLint("InflateParams")
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForegroundService();
        view = LayoutInflater.from(this).inflate(R.layout.float_text, null);
        myview = view.findViewById(R.id.text);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.title),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Receiver service notification");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void startForegroundService() {
        Intent notificationIntent = new Intent(this, DevicesActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder
                .setAutoCancel(true)
                .setContentTitle(getText(R.string.edit_text))
                .setContentText(getText(R.string.back))
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.drawable.none)
                .setWhen(System.currentTimeMillis())
                .build();

        startForeground(1, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createView();
        if (intent != null) {
            Bundle bundle = intent.getExtras();
            String temp = bundle != null ? bundle.getString("temp") : null;
            if (temp != null) {
                handler.obtainMessage(0, temp).sendToTarget();
            }
        }
        if (myview != null) {
            myview.setOnClickListener(v -> {
                mLastTime = mCurTime;
                mCurTime = System.currentTimeMillis();
                if (mCurTime - mLastTime < 300 && wm != null) {
                    wm.removeView(view);
                    added = false;
                }
            });
        }
        return START_STICKY;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createView() {
        // 悬浮窗权限检查，Android 6+ 需要手动授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            return;
        }

        wm = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        wmParams = new WindowManager.LayoutParams();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wmParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            wmParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
        }
        wmParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        wmParams.gravity = Gravity.LEFT | Gravity.TOP;
        wmParams.x = 0;
        wmParams.y = 0;
        wmParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        wmParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        wmParams.format = PixelFormat.RGBA_8888;

        if (added) {
            wm.updateViewLayout(view, wmParams);
        } else {
            wm.addView(view, wmParams);
            added = true;
        }

        view.setOnTouchListener((v, event) -> {
            x = event.getRawX();
            y = event.getRawY();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mTouchStartX = event.getX();
                    mTouchStartY = event.getY() + view.getHeight() / 2f;
                    break;
                case MotionEvent.ACTION_MOVE:
                    myview.setTextColor(Color.rgb(0, 255, 0));
                    updateViewPosition();
                    break;
                case MotionEvent.ACTION_UP:
                    myview.setTextColor(Color.rgb(255, 255, 255));
                    updateViewPosition();
                    mTouchStartX = mTouchStartY = 0;
                    break;
            }
            return true;
        });
    }

    private void updateViewPosition() {
        if (wm != null && wmParams != null) {
            wmParams.x = (int) (x - mTouchStartX);
            wmParams.y = (int) (y - mTouchStartY);
            wm.updateViewLayout(view, wmParams);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (wm != null && view != null) {
            try {
                wm.removeView(view);
            } catch (IllegalArgumentException ignored) {}
        }
        added = false;
        super.onDestroy();
    }

    static class RecvHandler extends Handler {
        private final WeakReference<ReceiverService> ref;

        RecvHandler(ReceiverService service) {
            super(Looper.getMainLooper());
            this.ref = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            ReceiverService service = ref.get();
            if (service == null || service.myview == null) return;
            if (msg.what == 0 && msg.obj instanceof String) {
                service.myview.setText((String) msg.obj);
            }
        }
    }
}
