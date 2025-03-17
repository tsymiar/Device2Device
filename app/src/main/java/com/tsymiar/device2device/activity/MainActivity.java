package com.tsymiar.device2device.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.tsymiar.device2device.R;

public class MainActivity extends Activity {

    protected Animation animation;
    private ImageView toTestAnimation;
    private long mCurTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findMyImg();
        Animation(R.anim.animation);
    }

    @Override
    protected void onResume() {
        super.onResume();

        ViewGroup vg = findViewById(R.id.anim);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(MainActivity.this, EntryActivity.class));
        }, 1000);

        assert vg != null;
        vg.setOnClickListener(arg0 -> {
            Animation(0);
            MainActivity.this.finish();
        });
    }

    private void findMyImg() {
        toTestAnimation = findViewById(R.id.ofofof);
    }

    private void Animation(int i) {

        if (i != 0) {
            animation = AnimationUtils.loadAnimation(this, i);
            toTestAnimation.startAnimation(animation);
        } else {
            AnimationUtils.makeOutAnimation(this, true);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        /* Called when the activity is first created. */
        long mLastTime = mCurTime;
        mCurTime = System.currentTimeMillis();
        if ((keyCode == KeyEvent.KEYCODE_BACK) && (mCurTime - mLastTime >= 800)) {
            Toast.makeText(MainActivity.this, R.string.exitapp, Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        finish();
    }
}
