package com.tsymiar.device2device.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.TextView;

import com.tsymiar.device2device.R;

import java.util.Locale;

/**
 * 指南针测向页（透明，观感像弹窗）：点部件时才启动，用完即退。
 * 优先用 加速度计 + 磁力计 求旋转矩阵（绝对磁北），无磁力计时回退旋转向量；
 * 方位角取水平投影最长的设备轴 —— 手机放平时是顶边，竖持时是屏幕朝向，避免万向锁。
 */
public class CompassActivity extends Activity implements SensorEventListener {

    private static final long WARMUP_MS = 800;      // 传感器预热，前几帧常是 0°/未收敛
    private static final long STABLE_MS = 600;      // 连续稳定这么久才采信
    private static final long TIMEOUT_MS = 5000;
    private static final long RESULT_MS = 900;      // 结果停留一会儿再退出
    private static final float STABLE_TOL = 2.5f;   // 相邻读数允许摆动的角度

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private SensorManager mSensorManager;
    private TextView mText;
    private float[] mGravity;
    private float[] mGeomagnetic;
    private boolean mFused;                       // 用旋转向量兜底
    private int mMagAccuracy = -1;                 // -1 = 还没回调过精度
    private long mStartAt;
    private long mStableSince;
    private float mLast = Float.NaN;
    private float mPrevDeg = Float.NaN;
    private boolean mDone;
    private final Runnable mTimeout = () -> finishUp(mLast);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mText = new TextView(this);
        mText.setGravity(Gravity.CENTER);
        mText.setTextSize(30);
        mText.setTextColor(0xFFE8EAED);
        mText.setIncludeFontPadding(false);
        mText.setLineSpacing(0f, 1.3f);
        mText.setOnClickListener(v -> {
            if (!mDone) finishUp(mLast);
        });
        setContentView(mText);
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mStartAt = System.currentTimeMillis();
        mStableSince = 0;
        mLast = Float.NaN;
        mPrevDeg = Float.NaN;
        mMagAccuracy = -1;
        mDone = false;
        mGravity = null;
        mGeomagnetic = null;
        mText.setText("测向中…\n手机放平或竖持保持稳定");
        if (mSensorManager == null) {
            mDone = true;
            fail("设备无传感器服务");
            return;
        }
        Sensor accel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor mag = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (accel != null && mag != null) {
            mFused = false;
            mSensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
            mSensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME);
        } else {
            // 没有独立磁力计：旋转向量通常也含磁北，能测就测，测不了明确提示
            Sensor rotation = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rotation == null) {
                rotation = mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
            }
            if (rotation == null) {
                mDone = true;
                fail("设备无方向传感器");
                return;
            }
            mFused = true;
            mSensorManager.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME);
        }
        mHandler.postDelayed(mTimeout, TIMEOUT_MS + 200);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (mDone) return;
        float deg;
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_ORIENTATION) {
            deg = (event.values[0] + 360f) % 360f;      // 已废弃的传感器，values[0] 即方位角
        } else {
            float[] matrix = new float[9];
            if (type == Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values);
            } else {
                if (type == Sensor.TYPE_ACCELEROMETER) {
                    mGravity = event.values.clone();
                } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
                    mGeomagnetic = event.values.clone();
                }
                if (mGravity == null || mGeomagnetic == null) return;
                if (!SensorManager.getRotationMatrix(matrix, null, mGravity, mGeomagnetic)) return;
            }
            deg = azimuthOf(matrix);
        }
        mLast = deg;
        mText.setText(String.format(Locale.US, "%.0f°  %s", deg,
                CompassWidgetProvider.direction(deg)));

        long elapsed = System.currentTimeMillis() - mStartAt;
        if (elapsed < WARMUP_MS) return;
        if (!mFused && mMagAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                && elapsed < TIMEOUT_MS - 1000) {
            mText.setText("磁力计未校准\n请拿手机画「8」字再测");
            return;                                  // 未校准的读数不要写回部件
        }
        float delta = Float.isNaN(mPrevDeg) ? Float.MAX_VALUE : angleDiff(deg, mPrevDeg);
        mPrevDeg = deg;
        if (delta <= STABLE_TOL) {
            if (mStableSince == 0) mStableSince = System.currentTimeMillis();
            if (System.currentTimeMillis() - mStableSince >= STABLE_MS) finishUp(deg);
        } else {
            mStableSince = 0;
        }
    }

    /** 0~360° 的最短夹角（跨越 0° 不会误判成 359°） */
    private static float angleDiff(float a, float b) {
        return Math.abs((a - b + 540f) % 360f - 180f);
    }

    /**
     * 旋转矩阵 R（设备→世界：X 东、Y 北、Z 天）里取水平投影最长的轴算方位：
     * 设备顶边 +Y = (R[1], R[4])，屏幕朝向 -Z = (-R[2], -R[5])。
     * 放平时前者近 1、后者近 0；竖持时反过来，因此两种握姿都能给出稳定读数。
     */
    private static float azimuthOf(float[] r) {
        float topEast = r[1], topNorth = r[4];
        float faceEast = -r[2], faceNorth = -r[5];
        float topLen = topEast * topEast + topNorth * topNorth;
        float faceLen = faceEast * faceEast + faceNorth * faceNorth;
        float east = topLen >= faceLen ? topEast : faceEast;
        float north = topLen >= faceLen ? topNorth : faceNorth;
        if (east * east + north * north < 1e-4f) return 0f;
        return (float) ((Math.toDegrees(Math.atan2(east, north)) + 360) % 360);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) mMagAccuracy = accuracy;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTimeout);
        if (!mDone && mSensorManager != null) mSensorManager.unregisterListener(this);
    }

    /** 结束测量：写回所有指南针部件，结果停留片刻再退出 */
    private void finishUp(float deg) {
        if (mDone) return;
        mDone = true;
        mHandler.removeCallbacks(mTimeout);
        if (mSensorManager != null) mSensorManager.unregisterListener(this);
        if (Float.isNaN(deg)) {
            mText.setText("未能获取方向\n请放平手机，远离磁性物体后重试");
        } else {
            publish(deg);
            mText.setText(String.format(Locale.US, "%.0f°  %s", deg,
                    CompassWidgetProvider.direction(deg)));
        }
        mHandler.postDelayed(this::finish, RESULT_MS + 600);
    }

    private void fail(String message) {
        mText.setText(message);
        mHandler.postDelayed(this::finish, 1500);
    }

    /** 把测得的方位角画到桌面上全部指南针部件 */
    private void publish(float deg) {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        int[] ids = manager.getAppWidgetIds(new ComponentName(this, CompassWidgetProvider.class));
        if (ids == null || ids.length == 0) return;
        Bitmap dial = CompassWidgetProvider.dial(deg);
        for (int id : ids) {
            CompassWidgetProvider.update(this, manager, id, dial);
        }
    }
}
