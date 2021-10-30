package com.tsymiar.devidroid.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import com.tsymiar.devidroid.R;
import com.tsymiar.devidroid.data.PubSubSetting;

public class PublishDialog extends AppCompatActivity {
    private static final String TAG = PublishDialog.class.getCanonicalName();

    EditText g_editTopic = null;
    EditText g_editPayload = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);

        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_publish);

        WindowManager windowManager=getWindowManager();
        Display display=windowManager.getDefaultDisplay();
        WindowManager.LayoutParams params=getWindow().getAttributes();
        params.height=(int)(display.getHeight()*0.4);
        params.width=(int)(display.getWidth()*0.5);
        params.alpha=1.0f;
        getWindow().setAttributes(params);
        getWindow().setGravity(Gravity.CENTER);

        findViewById(R.id.btn_confirm1).setOnClickListener(
                v -> {
                    if (g_editTopic != null)
                        PubSubSetting.setTopic(g_editTopic.getText().toString());
                    if (g_editPayload != null)
                        PubSubSetting.setPayload(g_editPayload.getText().toString());
                    Intent data = new Intent();
                    data.putExtra("Publish", "SUCCESS");
                    setResult(Activity.RESULT_OK, data);
                    finish();
                }
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        g_editTopic = findViewById(R.id.edit_topic1);
        g_editPayload = findViewById(R.id.edit_payload);
    }
}
