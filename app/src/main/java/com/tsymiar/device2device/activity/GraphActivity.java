package com.tsymiar.device2device.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.acceleration.ChartFragment;

public class GraphActivity extends AppCompatActivity {

    TextView t1, t2, t3;

    // 两次检测的时间间隔
    private static final int UPDATE_INTERVAL_TIME = 200;

    // 传感器管理器
    private SensorManager sensorManager;

    // private Sensor accelerometerSensor, gravitySensor,
    // linearAccelerationSensor;
    SensorAction SensorAction1, SensorAction2, SensorAction3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_graph);
        t1 = (TextView)findViewById(R.id.t1);
        t2 = (TextView)findViewById(R.id.t2);
        t3 = (TextView)findViewById(R.id.t3);
        Button open = (Button)findViewById(R.id.bt);
        open.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (savedInstanceState == null) {
                    getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, new ChartFragment())
                            .commit();
                }
            }
        });

        sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);

        SensorAction1 = new SensorAction(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), t1);
        SensorAction2 = new SensorAction(sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY), t2);
        SensorAction3 = new SensorAction(sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), t3);
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
        SensorAction1.register();
        SensorAction2.register();
        SensorAction3.register();
    }

    @Override
    protected void onPause() {
        super.onPause();
        SensorAction1.unregister();
        SensorAction2.unregister();
        SensorAction3.unregister();
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
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        public void unregister() {
            sensorManager.unregisterListener(this);
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
}
