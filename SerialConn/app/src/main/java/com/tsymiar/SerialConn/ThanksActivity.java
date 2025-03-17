package com.tsymiar.SerialConn;

import android.app.Activity;
import android.os.Bundle;
import androidx.annotation.NonNull;
import android.view.KeyEvent;
import android.widget.Toast;

public class ThanksActivity extends Activity {

    public void onCreate(Bundle savedInstanceState) {
        {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_thank);
            Toast.makeText(getBaseContext(), R.string.developing, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }
}

