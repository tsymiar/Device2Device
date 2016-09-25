package com.TsyQi.MyClasses;

import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;

import static android.content.Context.SENSOR_SERVICE;

@TargetApi(Build.VERSION_CODES.CUPCAKE)
public class MySensor implements SensorEventListener {

	android.hardware.Sensor sensor;
	Context context;
	SensorManager sensorManager;
	private long lastUpdateTime;
	private static final int UPTATE_INTERVAL_TIME = 30;
	public double[] linear_acceleration = new double[3];
	public double[] gravity=new double[3];

	public MySensor(android.hardware.Sensor sensor) {
		this.sensor = sensor;
	}

	public void register() {
		sensorManager = (SensorManager) this.context.getSystemService(SENSOR_SERVICE);
		sensorManager.registerListener( this, sensor, SensorManager.SENSOR_DELAY_GAME);
	}

	public void unregister() {
		sensorManager.unregisterListener(this);
	}
	@Override
	public void onAccuracyChanged(android.hardware.Sensor arg0, int arg1) {
		// TODO Auto-generated method stub
	}
	@Override
	public void onSensorChanged(SensorEvent arg) {
		// TODO Auto-generated method stub
		// 检测时间
		long currentUpdateTime = System.currentTimeMillis();
		// 检测的时间间隔
		long timeInterval = currentUpdateTime - lastUpdateTime;
		// 判断是否达到了检测时间间隔
		if (timeInterval < UPTATE_INTERVAL_TIME)
			return;
		// 当前的时间变成last时间
		lastUpdateTime = currentUpdateTime;

		final float alpha = 0.8f;

		gravity[0] = alpha * gravity[SensorManager.DATA_X] + (1 - alpha) * arg.values[SensorManager.DATA_X];
		gravity[1] = alpha * gravity[SensorManager.DATA_Y] + (1 - alpha) * arg.values[SensorManager.DATA_Y];
		gravity[2] = alpha * gravity[SensorManager.DATA_Z] + (1 - alpha) * arg.values[SensorManager.DATA_Z];

		linear_acceleration[0] = arg.values[SensorManager.DATA_X] - gravity[SensorManager.DATA_X];
		linear_acceleration[1] = arg.values[SensorManager.DATA_Y] - gravity[SensorManager.DATA_Y];
		linear_acceleration[2] = arg.values[SensorManager.DATA_Z] - gravity[SensorManager.DATA_Z];
	}
}
