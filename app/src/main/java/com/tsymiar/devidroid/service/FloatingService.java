package com.tsymiar.devidroid.service;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.tsymiar.devidroid.R;
import com.tsymiar.devidroid.data.PubSubSetting;
import com.tsymiar.devidroid.wrapper.CallbackWrapper;

public class FloatingService extends Service {

    private static final String TAG = FloatingService.class.getCanonicalName();
    public static final String BROADCAST_ACTION = "com.tsymiar.devidroid.broadcast";

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new Binder();
    }

    public class Binder extends android.os.Binder {
        public FloatingService getService() {
            return FloatingService.this;
        }
    }

    private boolean connecting = false;
    private Callback callback;

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public interface Callback {
        void onDataChange(String data);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        connecting = false;
    }

    private WindowManager windowManager = null;
    private LayoutInflater layoutInflater;
    View floatView;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        layoutInflater = LayoutInflater.from(this);
        connecting = true;
        new Thread(() -> {
            int index = 0;
            while (connecting) {
                index++;
                if (callback != null) {
                    callback.onDataChange(index + " " + TAG);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public static class FloatingOnTouchListener implements View.OnTouchListener {
        private int x;
        private int y;
        private final boolean left;
        private final WindowManager windowManager;
        private final WindowManager.LayoutParams layoutParams;

        FloatingOnTouchListener(WindowManager windowManager, WindowManager.LayoutParams layoutParams, boolean left) {
            this.windowManager = windowManager;
            this.layoutParams = layoutParams;
            this.left = left;
        }
        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    x = (int) event.getRawX();
                    y = (int) event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    int nowX = (int) event.getRawX();
                    int nowY = (int) event.getRawY();
                    int movedX = nowX - x;
                    if (!left) {
                        movedX = x - nowX;
                    }
                    int movedY = nowY - y;
                    x = nowX;
                    y = nowY;
                    layoutParams.x = layoutParams.x + movedX;
                    layoutParams.y = layoutParams.y + movedY;
                    windowManager.updateViewLayout(view, layoutParams);
                    break;
                default:
                    break;
            }
            return false;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @SuppressLint({"InflateParams", "RtlHardcoded", "WrongConstant"})
    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        floatView = layoutInflater.inflate(R.layout.subscribe, null);

        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutParams.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        layoutParams.format = PixelFormat.RGBA_8888;
        layoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        layoutParams.flags = WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON; // | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        Display display = windowManager.getDefaultDisplay();
        layoutParams.width = (int)(display.getWidth() * 0.5);
        layoutParams.height = (int)(display.getHeight() * 0.44);
        layoutParams.alpha = 1.0f;
        layoutParams.x = 0;
        layoutParams.y = 0;
        floatView.setFocusableInTouchMode(true);
        floatView.setOnTouchListener(new FloatingOnTouchListener(windowManager, layoutParams, true));
        if (Settings.canDrawOverlays(this)) {
            windowManager.addView(floatView, layoutParams);
            windowManager.getDefaultDisplay();
        }

        EditText editAddr = floatView.findViewById(R.id.edit_addr);
        EditText editPort = floatView.findViewById(R.id.edit_port);
        EditText editTopic = floatView.findViewById(R.id.edit_topic);
        if (!PubSubSetting.getAddr().isEmpty() && editAddr != null) {
            editAddr.setText(PubSubSetting.getAddr());
        }
        floatView.findViewById(R.id.btn_confirm).setOnClickListener(
                v -> {
                    if (editAddr != null)
                        PubSubSetting.setAddr(editAddr.getText().toString());
                    if (editPort != null)
                        PubSubSetting.setPort(Integer.parseInt(editPort.getText().toString()));
                    if (editTopic != null)
                        PubSubSetting.setTopic(editTopic.getText().toString());
                    Intent data = new Intent();
                    data.setAction(BROADCAST_ACTION);
                    data.putExtra("Subscribe", "SUCCESS");
                    getApplicationContext().sendBroadcast(data);
                }
        );
        floatView.findViewById(R.id.btn_minium).setOnClickListener(
                v -> {
                    if (windowManager != null) {
                        windowManager.removeView(floatView);
                    }
                }
        );
        floatView.findViewById(R.id.btn_close).setOnClickListener(
                v -> closeWindow()
        );
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    public void closeWindow() {
        CallbackWrapper.QuitSubscribe();
        if (windowManager != null) {
            windowManager.removeView(floatView);
        }
    }
}
