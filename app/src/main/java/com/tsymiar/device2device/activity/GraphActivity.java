package com.tsymiar.device2device.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.acceleration.SensorFragment;

public class GraphActivity extends AppCompatActivity {

    TextView mT1, mT2, mT3;

    // 两次检测的时间间隔
    private static final int UPDATE_INTERVAL_TIME = 200;

    // 传感器管理器
    private SensorManager mSensorManager;

    // private Sensor accelerometerSensor, gravitySensor,
    // linearAccelerationSensor;
    SensorAction mSensorAction1, mSensorAction2, mSensorAction3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_graph);
        mT1 = (TextView)findViewById(R.id.t1);
        mT2 = (TextView)findViewById(R.id.t2);
        mT3 = (TextView)findViewById(R.id.t3);
        Button open = (Button)findViewById(R.id.bt);
        open.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (savedInstanceState == null) {
                    getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, new SensorFragment())
                            .commit();
                }
            }
        });

        mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);

        mSensorAction1 = new SensorAction(mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), mT1);
        mSensorAction2 = new SensorAction(mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY), mT2);
        mSensorAction3 = new SensorAction(mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), mT3);
    }

    @Override
    public void onBackPressed() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSensorAction1.register();
        mSensorAction2.register();
        mSensorAction3.register();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSensorAction1.unregister();
        mSensorAction2.unregister();
        mSensorAction3.unregister();
    }

    class SensorAction implements SensorEventListener {

        TextView t;
        Sensor sensor;

        private long lastUpdateTime;

        public SensorAction(Sensor sensor, TextView t) {

            this.t = t;
            this.sensor = sensor;
        }

        public void register() {
            mSensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        public void unregister() {
            mSensorManager.unregisterListener(this);
        }

        @Override
        public void onAccuracyChanged(Sensor arg1, int arg2) {
            // TODO Auto-generated method stub
        }

        @SuppressLint("SetTextI18n")
        @Override
        public void onSensorChanged(SensorEvent arg3) {
            // 检测时间
            long currentUpdateTime = System.currentTimeMillis();
            // 两次检测的时间间隔
            long timeInterval = currentUpdateTime - lastUpdateTime;
            // 判断是否达到了检测时间间隔
            if (timeInterval < UPDATE_INTERVAL_TIME)
                return;
            // 更新last时间
            lastUpdateTime = currentUpdateTime;
            t.setText("X:" + arg3.values[0] + "\nY:" + arg3.values[1] + "\nZ:" + arg3.values[2]);
            // Toast.makeText(MainActivity.this,""+arg3.values[0],Toast.LENGTH_SHORT).show();
        }
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        // getMenuInflater().inflate(R.menu.menu_main,menu);
        getMenuInflater().inflate(R.menu.options, menu);
        return true;
    }

    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        return itemSelected(item, GraphActivity.this);
    }

    @SuppressLint("NonConstantResourceId")
    public boolean itemSelected(MenuItem item, Activity activity) {
        Intent intent;
        // Handle item selection
        int itemId = item.getItemId();
        if (itemId == R.id.sensor) {
            intent = new Intent(activity, SensorActivity.class);
            activity.startActivity(intent);
        } else if (itemId == R.id.feedback) {
            intent = new Intent(activity, BuggerActivity.class);
            activity.startActivity(intent);
        } else if (itemId == R.id.url) {
            intent = new Intent(activity, MyGitActivity.class);
            activity.startActivity(intent);
        } else if (itemId == R.id.more) {
            intent = new Intent(activity, ThanksActivity.class);
            activity.startActivity(intent);
        } else if (itemId == R.id.exit) {
            activity.finish();
        } else if (itemId != R.id.item) {
            Toast.makeText(GraphActivity.this, "select item", Toast.LENGTH_SHORT).show();
        } else {
            return false;
        }
        return true;
    }
}
