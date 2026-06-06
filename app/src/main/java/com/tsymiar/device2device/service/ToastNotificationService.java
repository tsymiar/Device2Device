package com.tsymiar.device2device.service;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.tsymiar.device2device.R;

public class ToastNotificationService extends Service {

    public static final String TOAST_ACTION = "com.tsymiar.device2device.TOAST";
    public static final String EXTRA_MESSAGE = "toast_message";

    private WindowManager windowManager;
    private View floatView;
    private WindowManager.LayoutParams layoutParams;
    private boolean isShowing = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable dismissRunnable = new Runnable() {
        @Override
        public void run() {
            hideToast();
        }
    };

    private final BroadcastReceiver toastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (TOAST_ACTION.equals(intent.getAction())) {
                String message = intent.getStringExtra(EXTRA_MESSAGE);
                if (message != null && !message.isEmpty()) {
                    showToast(message);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);

        IntentFilter filter = new IntentFilter(TOAST_ACTION);
        registerReceiver(toastReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @SuppressLint("RtlHardcoded")
    private void showToast(String message) {
        if (!Settings.canDrawOverlays(this)) return;

        // 移除已有悬浮窗
        if (isShowing) {
            hideToast();
        }

        // 使用 dialog_view_text 布局（圆角背景 + 文字阴影），高度动态设置
        floatView = LayoutInflater.from(this).inflate(R.layout.dialog_view_text, null);
        int heightPx = (int) (70 * getResources().getDisplayMetrics().density);
        floatView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, heightPx));
        TextView textView = floatView.findViewById(R.id.text);
        textView.setText(message);

        layoutParams = new WindowManager.LayoutParams();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutParams.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        layoutParams.format = PixelFormat.TRANSLUCENT;
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        layoutParams.x = (int) (16 * metrics.density);
        layoutParams.y = (int) (60 * metrics.density);

        windowManager.addView(floatView, layoutParams);
        isShowing = true;

        // 3秒后自动消失
        handler.removeCallbacks(dismissRunnable);
        handler.postDelayed(dismissRunnable, 3000);
    }

    private void hideToast() {
        handler.removeCallbacks(dismissRunnable);
        if (floatView != null && isShowing) {
            try {
                windowManager.removeView(floatView);
            } catch (Exception ignored) {
            }
            floatView = null;
            isShowing = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(dismissRunnable);
        try {
            unregisterReceiver(toastReceiver);
        } catch (Exception ignored) {
        }
        hideToast();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
