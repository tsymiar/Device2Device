package com.tsymiar.SerialConn.Sensor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.TextView;

import com.tsymiar.SerialConn.DeviceListActivity;
import com.tsymiar.SerialConn.R;

import java.util.List;

public class SensorListActivity extends Activity {

    private static final int REQUEST_DISCOVER_DEVICE = 1;
    private String TAG = "SensorListActivity";
    final Activity me = this;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sensor);
        final TextView list = (TextView) findViewById(R.id.hello);
        final TextView bar = (TextView) findViewById(R.id.bar);
        list.setMovementMethod(ScrollingMovementMethod.getInstance());
        // 获得传感器列表。
        SensorManager mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> deviceSensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        bar.setText("手机上共有" + deviceSensors.size() + "个传感器：");
        // 显示每个传感器的具体信息
        for (Sensor s : deviceSensors) {

            String tempString = "\n" + "\t\t\t设备名称：" + s.getName() + "\n" + "\t\t\t设备版本：" + s.getVersion() + "\n" + "\t\t\t供应商："
                    + s.getVendor() + "\n";
            String m = "\n\t\t传感器编号：";
            switch (s.getType()) {
                case Sensor.TYPE_ACCELEROMETER:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t加速度传感器-accelerometer" + tempString);
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t陀螺仪-gyroscope" + tempString);
                    break;
                case Sensor.TYPE_LIGHT:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t环境光线感应器-light" + tempString);
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t电磁场-magnetic field" + tempString);
                    break;
                case Sensor.TYPE_ORIENTATION:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t方向感应器-orientation" + tempString);
                    break;
                case Sensor.TYPE_GRAVITY:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t重力感应-gravity" + tempString);
                    break;
                case Sensor.TYPE_PRESSURE:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t压力传感器-pressure" + tempString);
                    break;
                case Sensor.TYPE_PROXIMITY:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t距离传感器-proximity" + tempString);
                    break;
                case Sensor.TYPE_TEMPERATURE:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t温度传感器-temperature" + tempString);
                    break;
                case Sensor.TYPE_ROTATION_VECTOR:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t旋转矢量-ROTATION_VECTOR" + tempString);
                    break;
                case Sensor.TYPE_STEP_COUNTER:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t计步器-STEP COUNTER" + tempString);
                    break;
                case Sensor.TYPE_STEP_DETECTOR:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t探步仪-STEP DETECTOR" + tempString);
                    break;
                case Sensor.TYPE_LINEAR_ACCELERATION:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t线性加速度计-LINEAR_ACCELERATION    " + tempString);
                    break;
                case Sensor.TYPE_SIGNIFICANT_MOTION:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t有效移动-SIGNIFICANT_MOTION" + tempString);
                    break;
                case Sensor.TYPE_GYROSCOPE_UNCALIBRATED:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t[无标定]陀螺仪-GYROSCOPE_UNCALIBRATED" + tempString);
                    break;
                case Sensor.TYPE_ALL:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t多區域移動偵測-VMD" + tempString);
                    break;

                default:
                    list.setText(list.getText().toString() + m + s.getType() + "\n\t未命名传感器" + tempString);
                    break;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ImageButton close = (ImageButton) findViewById(R.id.image);
        close.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onActivityResult " + resultCode);
        if (requestCode == REQUEST_DISCOVER_DEVICE) {// When the request to enable Bluetooth returns
            if (resultCode != 0) {
                Intent init = new Intent(me, DeviceListActivity.class);
                startActivity(init);
            } else {
                // User did not enable Bluetooth or an error occured
                Log.d(TAG, "Device not Available");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

}
