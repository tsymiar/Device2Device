package com.tsymiar.device2device.service;

import android.annotation.SuppressLint;
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
import android.os.Vibrator;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import com.tsymiar.device2device.R;

public class WindowService extends Service {
    private WindowManager wm;
    private WindowManager.LayoutParams wmParams;
    private View dialog;
    private boolean added;
    private TextView textView;

    private float mTouchStartX, mTouchStartY;
    private float x, y;
    private long mLastTime, mCurTime;

    private final WinHandler handler = new WinHandler(this);

    @SuppressLint("InflateParams")
    @Override
    public void onCreate() {
        super.onCreate();
        dialog = LayoutInflater.from(this).inflate(R.layout.dialog_view_text, null);
        int heightPx = (int) (70 * getResources().getDisplayMetrics().density);
        dialog.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, heightPx));
        textView = dialog.findViewById(R.id.text);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        setView();
        if (intent != null) {
            Bundle bundle = intent.getExtras();
            String temp = bundle != null ? bundle.getString("t") : null;
            if (temp != null) {
                handler.obtainMessage(0, temp).sendToTarget();
            }
        }
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (textView != null) {
            textView.setOnClickListener(v -> {
                mLastTime = mCurTime;
                mCurTime = System.currentTimeMillis();
                if (vibrator != null) vibrator.vibrate(50);
                if (mCurTime - mLastTime < 300 && wm != null) {
                    wm.removeView(dialog);
                    added = false;
                }
            });
        }
        return START_STICKY;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setView() {
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
        wmParams.x = 250;
        wmParams.y = 90;
        wmParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        wmParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        wmParams.format = PixelFormat.RGBA_8888;

        if (added) {
            wm.updateViewLayout(dialog, wmParams);
        } else {
            wm.addView(dialog, wmParams);
            added = true;
        }

        dialog.setOnTouchListener((v, event) -> {
            x = event.getRawX();
            y = event.getRawY();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mTouchStartX = event.getX();
                    mTouchStartY = event.getY() + dialog.getHeight() / 2f;
                    break;
                case MotionEvent.ACTION_MOVE:
                    textView.setTextColor(Color.rgb(184, 134, 11));
                    updateViewPosition();
                    break;
                case MotionEvent.ACTION_UP:
                    textView.setTextColor(getResources().getColor(R.color.white));
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
            wm.updateViewLayout(dialog, wmParams);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (wm != null && dialog != null) {
            try {
                wm.removeView(dialog);
            } catch (IllegalArgumentException ignored) {}
        }
        added = false;
        super.onDestroy();
    }

    static class WinHandler extends Handler {
        private final WeakReference<WindowService> ref;

        WinHandler(WindowService service) {
            super(Looper.getMainLooper());
            this.ref = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            WindowService service = ref.get();
            if (service == null || service.textView == null) return;
            if (msg.what == 0 && msg.obj instanceof String) {
                service.textView.setText((String) msg.obj);
            }
        }
    }
}
