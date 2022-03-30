package com.tsymiar.devidroid.service;

import static com.tsymiar.devidroid.service.FloatingService.BROADCAST_ACTION;

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
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.tsymiar.devidroid.R;
import com.tsymiar.devidroid.data.PubSubSetting;

public class PublishDialog extends Service {

    private WindowManager windowManager;
    private LayoutInflater layoutInflater;
    View floatView;

    EditText editTopic = null;
    EditText editPayload = null;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        layoutInflater = LayoutInflater.from(this);
    }

    @SuppressLint("InflateParams")
    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        floatView = layoutInflater.inflate(R.layout.dialog_publisher, null);

        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutParams.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        layoutParams.format = PixelFormat.RGBA_8888;
        layoutParams.gravity = Gravity.RIGHT | Gravity.CENTER;
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        Display display = windowManager.getDefaultDisplay();
        layoutParams.width = (int)(display.getWidth() * 0.5);
        layoutParams.height = (int)(display.getHeight() * 0.3);
        layoutParams.alpha = 1.0f;
        layoutParams.x = 0;
        layoutParams.y = 0;
        floatView.setFocusableInTouchMode(true);
        floatView.setOnTouchListener(new FloatingService.FloatingOnTouchListener(windowManager, layoutParams, false));
        if (Settings.canDrawOverlays(this)) {
            windowManager.addView(floatView, layoutParams);
            windowManager.getDefaultDisplay();
        }

        editTopic = floatView.findViewById(R.id.edit_pub_topic);
        editPayload = floatView.findViewById(R.id.edit_payload);

        floatView.findViewById(R.id.btn_pub_finish).setOnClickListener(v -> closeWindow());
        floatView.findViewById(R.id.btn_pub_confirm).setOnClickListener(
                v -> {
                    if (editTopic != null)
                        PubSubSetting.setTopic(editTopic.getText().toString());
                    if (editPayload != null)
                        PubSubSetting.setPayload(editPayload.getText().toString());
                    Intent data = new Intent();
                    data.putExtra("Publish", "SUCCESS");
                    data.setAction(BROADCAST_ACTION);
                    getApplicationContext().sendBroadcast(data);
                }
        );
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void closeWindow() {
        windowManager.removeView(floatView);
    }
}
