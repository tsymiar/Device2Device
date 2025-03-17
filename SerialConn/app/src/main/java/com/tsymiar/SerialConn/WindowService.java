package com.tsymiar.SerialConn;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

@SuppressLint("Registered")
public class WindowService extends Service {
    WindowManager wm = null;

    WindowManager.LayoutParams wmParams = null;

    View dialog;
    boolean set = false;

    private String temp = null;
    private float mTouchStartX;
    private float mTouchStartY;
    private TextView textView = null;
    private float x;
    private float y;
    private long mLastTime, mCurTime;

    @SuppressLint("InflateParams")
    @Override
    public void onCreate() {

        super.onCreate();
        dialog = LayoutInflater.from(this).inflate(R.layout.float_text, null);
        textView = dialog.findViewById(R.id.text);
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        setView();
        Bundle bundle = intent.getExtras();
        temp = bundle != null ? bundle.getString("t") : null;
        new Thread() {
            public void run() {
                Message msg = new Message();
                msg.what = 0;
                handler.sendMessage(msg);
                try {
                    sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }.start();
        final Vibrator vibrator = (Vibrator) this.getSystemService(VIBRATOR_SERVICE);
        textView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mLastTime = mCurTime;
                mCurTime = System.currentTimeMillis();
                if (vibrator != null) {
                    vibrator.vibrate(50);
                }
                if (mCurTime - mLastTime < 300) {
                    wm.removeView(dialog);
                    set = false;
                }
            }
        });
    }

    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == 0) {
                textView.setText(temp + "");
            }
            super.handleMessage(msg);
        }
    };

    private void setView() {
        wm = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);

        wmParams = new WindowManager.LayoutParams();
        wmParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
        wmParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        wmParams.gravity = Gravity.LEFT | Gravity.TOP;

        wmParams.x = 250;
        wmParams.y = 90;

        wmParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        wmParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        wmParams.format = PixelFormat.RGBA_8888;
        if (set) {
            wm.updateViewLayout(dialog, wmParams);
        } else {
            wm.addView(dialog, wmParams);
            set = true;
        }
        dialog.setOnTouchListener(new View.OnTouchListener() {

            @SuppressLint("ClickableViewAccessibility")
            public boolean onTouch(View v, MotionEvent event) {

                x = event.getRawX();
                y = event.getRawY();

                switch (event.getAction()) {

                    case MotionEvent.ACTION_DOWN:
                        mTouchStartX = event.getX();
                        mTouchStartY = event.getY() + dialog.getHeight() / 2.f;
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
            }
        });
    }

    private void updateViewPosition() {
        wmParams.x = (int) (x - mTouchStartX);
        wmParams.y = (int) (y - mTouchStartY);
        wm.updateViewLayout(dialog, wmParams);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        wm.removeView(dialog);
        stopSelf();
    }
}
