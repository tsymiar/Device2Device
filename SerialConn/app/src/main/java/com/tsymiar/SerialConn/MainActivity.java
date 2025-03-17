package com.tsymiar.SerialConn;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

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
        ExitApplication.getInstance().addActivity(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        ViewGroup vg = findViewById(R.id.anim);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    final int requestCode = 0;
                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.BLUETOOTH}, requestCode);
                }
                return;
            }
            startActivity(new Intent(MainActivity.this, ConnectActivity.class));
        }, 1000);

        assert vg != null;
        vg.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View arg0) {
                //startActivity(new Intent(MainActivity.this, ConnectActivity.class));
                Animation(0);
                //MainActivity.this.finish();
            }
        });
    }

    private void findMyImg() {
        toTestAnimation = findViewById(R.id.ooopic);
    }

    private void Animation(int i) {

        if (i != 0) {
            animation = AnimationUtils.loadAnimation(this, i);
            toTestAnimation.startAnimation(animation);
        } else {
            AnimationUtils.makeOutAnimation(this, true);
        }
    }

    public void exit(Activity activity) {
        Intent rs = new Intent(activity, ReceiverService.class);
        Intent ss = new Intent(activity, SaveDataService.class);
        Bundle bundle = new Bundle();
        String temp = "stop";
        bundle.putString("temp", temp);
        rs.putExtras(bundle);
        ss.putExtras(bundle);
        activity.sendBroadcast(rs);
        activity.sendBroadcast(ss);
        activity.stopService(rs);
        activity.stopService(ss);
        ExitApplication.getInstance().exit();
    }

    @SuppressLint("NonConstantResourceId")
    public boolean ItemSelected(MenuItem item, Activity activity) {
        Intent intent;
        // Handle item selection
        int itemId = item.getItemId();
        if (itemId == R.id.feedback) {
            intent = new Intent(activity, BuggerActivity.class);
            activity.startActivity(intent);
        } else if (itemId == R.id.url) {
            intent = new Intent(activity, ViewGitActivity.class);
            activity.startActivity(intent);
        } else if (itemId == R.id.more) {
            intent = new Intent(activity, ThanksActivity.class);
            activity.startActivity(intent);
        } else if (itemId == R.id.exit) {
            this.exit(activity);
        } else if (itemId != R.id.item) {
            Toast.makeText(MainActivity.this, "select item", Toast.LENGTH_SHORT).show();
        } else {
            return false;
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {

        /* Called when the activity is first created. */
        long mLastTime = mCurTime;
        mCurTime = System.currentTimeMillis();

        if (keyCode == KeyEvent.KEYCODE_BACK) {

            if (mCurTime - mLastTime < 800) {
                Intent intent = new Intent(MainActivity.this, ReceiverService.class);
                Bundle bundle = new Bundle();
                String temp = "stop";
                bundle.putString("temp", temp);
                intent.putExtras(bundle);
                sendBroadcast(intent);
                stopService(intent);
                ExitApplication.getInstance().exit();
                return true;
            } else if (mCurTime - mLastTime >= 800) {
                Toast.makeText(MainActivity.this, R.string.exit_app, Toast.LENGTH_SHORT).show();
                return true;
            }

        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        finish();
    }
}
