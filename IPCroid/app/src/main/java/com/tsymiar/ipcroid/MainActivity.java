package com.tsymiar.ipcroid;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // a call to a native method
        TextView tv = findViewById(R.id.sample_text);
        tv.setText((new JniWrapper()).stringFromJNI());
        JniWrapper.initJvmEnv(UtilMethod.TAG);
        JniWrapper.callJavaNonstaticMethod("hello", 0, "non-static call");
        JniWrapper.callJavaStaticMethod("welcome", 2, "callJavaStaticMethod!");
    }
}
