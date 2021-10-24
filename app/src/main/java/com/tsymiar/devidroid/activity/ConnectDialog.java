package com.tsymiar.devidroid.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import com.tsymiar.devidroid.R;
import com.tsymiar.devidroid.data.PubSubSetting;

public class ConnectDialog extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getCanonicalName();

    EditText g_editAddr = null;
    EditText g_editPort = null;
    EditText g_editTopic = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);

        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_connect);

        WindowManager windowManager=getWindowManager();
        Display display=windowManager.getDefaultDisplay();
        WindowManager.LayoutParams params=getWindow().getAttributes();
        params.height=(int)(display.getHeight()*0.5);
        params.width=(int)(display.getWidth()*0.5);
        params.alpha=1.0f;
        getWindow().setAttributes(params);
        getWindow().setGravity(Gravity.CENTER);

        findViewById(R.id.btn_confirm).setOnClickListener(
                v -> {
                    if (g_editAddr != null)
                        PubSubSetting.setAddr(g_editAddr.getText().toString());
                    if (g_editPort != null)
                        PubSubSetting.setPort(Integer.parseInt(g_editPort.getText().toString()));
                    if (g_editTopic != null)
                        PubSubSetting.setTopic(g_editTopic.getText().toString());
                    Intent data = new Intent();
                    data.putExtra("ConnectDialog", "SUCCESS");
                    setResult(Activity.RESULT_OK, data);
                    Log.i(TAG, PubSubSetting.getSetting().toString());
                    finish();
                }
        );
    }

    @Override
    protected void onResume() {
        g_editAddr = findViewById(R.id.edit_addr);
        g_editPort = findViewById(R.id.edit_port);
        g_editTopic = findViewById(R.id.edit_topic);
        if (!PubSubSetting.getAddr().isEmpty() && g_editAddr != null) {
            g_editAddr.setText(PubSubSetting.getAddr());
        }
        super.onResume();
    }
}
