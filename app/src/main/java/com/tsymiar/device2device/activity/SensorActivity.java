package com.tsymiar.device2device.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.tsymiar.device2device.R;

import java.util.List;

public class SensorActivity extends Activity {

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sensor);

        final TextView list = findViewById(R.id.hello);
        final TextView bar = findViewById(R.id.bar);
        list.setMovementMethod(ScrollingMovementMethod.getInstance());

        SensorManager mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> deviceSensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        bar.setText("手机上共有" + deviceSensors.size() + "个传感器：");

        StringBuilder sb = new StringBuilder();
        int index = 0;
        for (Sensor s : deviceSensors) {
            index++;
            sb.append("\n──────────────────────────────\n")
                    .append("● #").append(index).append(" ")
                    .append(getSensorName(s.getType())).append("\n")
                    .append("  编号：").append(s.getType()).append("\n")
                    .append("  命名：").append(s.getName()).append("\n")
                    .append("  版本：").append(s.getVersion()).append("\n")
                    .append("  供应商：").append(s.getVendor()).append("\n");
        }
        list.setText(sb.toString());
    }

    private static String getSensorName(int type) {
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER:         return "加速度传感器-accelerometer";
            case Sensor.TYPE_GYROSCOPE:             return "陀螺仪-gyroscope";
            case Sensor.TYPE_LIGHT:                 return "环境光线感应器-light";
            case Sensor.TYPE_MAGNETIC_FIELD:        return "电磁场-magnetic field";
            case Sensor.TYPE_ORIENTATION:           return "方向感应器-orientation";
            case Sensor.TYPE_GRAVITY:               return "重力感应-gravity";
            case Sensor.TYPE_PRESSURE:              return "压力传感器-pressure";
            case Sensor.TYPE_PROXIMITY:             return "距离传感器-proximity";
            case Sensor.TYPE_TEMPERATURE:           return "温度传感器-temperature";
            case Sensor.TYPE_ROTATION_VECTOR:       return "旋转矢量-ROTATION_VECTOR";
            case Sensor.TYPE_STEP_COUNTER:          return "计步器-STEP COUNTER";
            case Sensor.TYPE_STEP_DETECTOR:         return "探步仪-STEP DETECTOR";
            case Sensor.TYPE_LINEAR_ACCELERATION:   return "线性加速度计-LINEAR_ACCELERATION";
            case Sensor.TYPE_SIGNIFICANT_MOTION:    return "有效移动-SIGNIFICANT_MOTION";
            case Sensor.TYPE_GYROSCOPE_UNCALIBRATED:return "[无标定]陀螺仪-GYROSCOPE_UNCALIBRATED";
            case Sensor.TYPE_ALL:                   return "多區域移動偵測-VMD";
            default:                                return "未命名传感器";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ImageButton close = findViewById(R.id.image);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
    }
}
