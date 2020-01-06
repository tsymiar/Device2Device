package com.tsymiar.devidroid.activity;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.tsymiar.devidroid.R;
import com.tsymiar.devidroid.utils.MethodUtil;
import com.tsymiar.devidroid.wrapper.CallbackWrapper;
import com.tsymiar.devidroid.wrapper.TimeWrapper;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // a call to a native method
        TextView tv = findViewById(R.id.sample_text);
        tv.setText((new CallbackWrapper()).stringFromJNI());

        CallbackWrapper.initJvmEnv(MethodUtil.TAG);
        CallbackWrapper.callJavaMethod("hello", 0, "non-static call", false);
        CallbackWrapper.callJavaMethod("welcome", 2, "callJavaStaticMethod!", true);

        TimeWrapper.getBootTimestamp();

        Button button = findViewById(R.id.button);
        button.setOnClickListener(
                v -> startActivity(new Intent(MainActivity.this, TextureActivity.class))
        );
        Button btnAudio = findViewById(R.id.btn_audio);
        btnAudio.setOnClickListener(
                v -> startActivity(new Intent(MainActivity.this, AudioActivity.class))
        );
    }
}
