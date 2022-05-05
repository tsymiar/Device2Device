package com.tsymiar.SerialConn;

import android.annotation.SuppressLint;
import android.app.Notification;
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
import android.os.Message;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class ReceiverService extends Service {
    WindowManager wm = null;

    WindowManager.LayoutParams wmParams = null;

    View view;
    Bundle bundle = new Bundle();
    boolean added = false;

    private String temp = null;
    private float mTouchStartX;
    private float mTouchStartY;
    private TextView myview = null;
    private float x;
    private float y;
    private long mLastTime, mCurTime;

    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.M)
    @SuppressLint("InflateParams")
    @Override
    public void onCreate() {

        super.onCreate();
        Context context = this.getApplicationContext();
        Intent notificationIntent = new Intent(this, DeviceListActivity.class);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context)
                .setAutoCancel(true)
                .setContentTitle(getText(R.string.edit_text))
                .setContentText(getText(R.string.back))
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.drawable.ooopic)
                .setWhen(System.currentTimeMillis())
                .build();
        if (nm != null) {
            nm.notify(1, notification);
        }
        startForeground(1, notification);
        view = LayoutInflater.from(this).inflate(R.layout.text_float, null);
        myview = (TextView) view.findViewById(R.id.notification);
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        createView();
        bundle = intent.getExtras();
        temp = bundle != null ? bundle.getString("temp") : null;
        if ((bundle != null) && (temp != null)) {
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
        }
        myview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mLastTime = mCurTime;
                mCurTime = System.currentTimeMillis();
                if (mCurTime - mLastTime < 300) wm.removeView(view);
            }
        });
    }

    @SuppressLint("HandlerLeak")
    Handler handler = new Handler() {
        @SuppressLint("SetTextI18n")
        @Override
        public void handleMessage(Message msg) {

            if (msg.what == 0) {
                myview.setText(temp + "");
            }
            super.handleMessage(msg);
        }
    };

    @SuppressLint("WrongConstant")
    private void createView() {
        wm = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);

        wmParams = new WindowManager.LayoutParams();
        wmParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
        wmParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        wmParams.gravity = 51;

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
        view.setOnTouchListener(new View.OnTouchListener() {

            public boolean onTouch(View v, MotionEvent event) {

                x = event.getRawX();
                y = event.getRawY();

                switch (event.getAction()) {

                    case MotionEvent.ACTION_DOWN:
                        mTouchStartX = event.getX();
                        mTouchStartY = event.getY() + view.getHeight() / 2.f;
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
            }
        });
    }

    private void updateViewPosition() {

        wmParams.x = (int) (x - mTouchStartX);
        wmParams.y = (int) (y - mTouchStartY);
        wm.updateViewLayout(view, wmParams);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
