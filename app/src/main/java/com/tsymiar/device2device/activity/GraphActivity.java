package com.tsymiar.device2device.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.acceleration.SensorFragment;
import com.tsymiar.device2device.view.AltitudeView;
import com.tsymiar.device2device.view.BubbleLevelView;
import com.tsymiar.device2device.view.CompassView;
import com.tsymiar.device2device.view.MagneticView;
import com.tsymiar.device2device.view.ProximityView;
import com.tsymiar.device2device.view.StepView;

/**
 * 传感器页：上半屏是五个仪表/数值卡片——指南针（方向）、水平仪、海拔（GPS）、
 * 磁场强度、步数；下面那块是原来的加速度曲线（点 Start Chart 载入 SensorFragment）。
 */
public class GraphActivity extends AppCompatActivity implements SensorEventListener {

    /** 传感器回调很密（几十 Hz），画面按 ~20fps 更新足够顺滑，省电 */
    private static final long MIN_UI_INTERVAL_MS = 50;
    /** 一阶低通系数：加速度与方位角都先滤一次，读数才不会抖 */
    private static final float ALPHA = 0.15f;

    private static final int RC_LOCATION_STEP = 1001;
    private static final long GPS_MIN_TIME_MS = 1000L;
    private static final float GPS_MIN_DISTANCE_M = 1f;

    private SensorManager mSensorManager;
    private LocationManager mLocationManager;
    private CompassView mCompass;
    private AltitudeView mAltitude;
    private BubbleLevelView mLevel;
    private MagneticView mMagnetic;
    private StepView mStep;
    private ProximityView mProximity;

    /** 卡片最大化用的整屏容器（activity_graph.xml 里的 zoom_host） */
    private FrameLayout mZoomHost;
    /** 当前被搬进整屏容器的仪表：null 表示没在放大 */
    private View mZoomed;
    private ViewGroup mZoomedParent;
    private int mZoomedIndex;
    private ViewGroup.LayoutParams mZoomedParams;

    private Sensor mAccelSensor;
    private Sensor mMagSensor;
    private Sensor mStepSensor;
    private Sensor mProxSensor;

    private final float[] mAccel = new float[3];      // 低通后的加速度（重力方向）
    private final float[] mMag = new float[3];        // 低通后的磁场
    private final float[] mRotation = new float[9];
    private final float[] mOrientation = new float[3];
    private boolean mAccelReady = false;
    private boolean mMagReady = false;

    /** 方位角平滑用的 sin/cos 分量：直接平均角度会在 0/360 处跳变 */
    private float mSinSum = 0f;
    private float mCosSum = 1f;
    private boolean mHeadingReady = false;

    private long mLastUiUpdate;

    private final LocationListener mLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            if (mAltitude != null) {
                mAltitude.setAltitude((float) location.getAltitude());
                mAltitude.setAvailable(true);
            }
            if (mCompass != null) {
                mCompass.setLocation(location.getLatitude(), location.getLongitude());
            }
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            if (mAltitude != null) {
                mAltitude.setAvailable(hasLocationPermission());
            }
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            if (mAltitude != null) {
                mAltitude.setAvailable(false);
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_graph);

        mCompass = findViewById(R.id.compass_view);
        mAltitude = findViewById(R.id.altitude_view);
        mLevel = findViewById(R.id.level_view);
        mMagnetic = findViewById(R.id.magnetic_view);
        mStep = findViewById(R.id.step_view);
        mProximity = findViewById(R.id.proximity_view);

        Button open = findViewById(R.id.bt);
        open.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (savedInstanceState == null) {
                    getSupportFragmentManager().beginTransaction()
                            .replace(R.id.fragment_container, new SensorFragment())
                            .commit();
                }
            }
        });

        setupCardZoom();

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        mAccelSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mMagSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        mStepSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        mProxSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        // 硬件缺哪个就把对应仪表置成占位状态，别让卡片空着转
        mCompass.setAvailable(mAccelSensor != null && mMagSensor != null);
        mAltitude.setAvailable(hasLocationPermission() && isGpsEnabled());
        mLevel.setAvailable(mAccelSensor != null);
        mMagnetic.setAvailable(mMagSensor != null);
        mStep.setAvailable(mStepSensor != null);
        mProximity.setAvailable(mProxSensor != null);

        if (!hasLocationPermission() || !hasStepPermission()) {
            requestSensorPermissions();
        }
    }

    /**
     * 指南针 / 水平仪：点整张卡片把里面的仪表搬到整屏容器里显示，
     * 整屏容器本身（含里面的仪表）再点一下就还原——传感器回调拿的还是同一个 View，
     * 所以搬走期间读数照样实时更新。
     */
    private void setupCardZoom() {
        mZoomHost = findViewById(R.id.zoom_host);
        View compassCard = findViewById(R.id.compass_card);
        View levelCard = findViewById(R.id.level_card);
        if (compassCard != null) {
            compassCard.setOnClickListener(v -> toggleZoom(mCompass));
        }
        if (levelCard != null) {
            levelCard.setOnClickListener(v -> toggleZoom(mLevel));
        }
        if (mZoomHost != null) {
            mZoomHost.setOnClickListener(v -> restoreZoom());
        }
    }

    /** 已经是它就收回去，否则先还原上一个再放大这个 */
    private void toggleZoom(View view) {
        if (mZoomHost == null || view == null) return;
        if (mZoomed == view) {
            restoreZoom();
            return;
        }
        if (mZoomed != null) {
            restoreZoom();
        }
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent == null) return;
        mZoomed = view;
        mZoomedParent = parent;
        mZoomedIndex = parent.indexOfChild(view);
        mZoomedParams = view.getLayoutParams();
        parent.removeView(view);
        mZoomHost.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (view instanceof CompassView) {
            ((CompassView) view).setZoomed(true);
        } else if (view instanceof BubbleLevelView) {
            ((BubbleLevelView) view).setZoomed(true);
        }
        mZoomHost.setVisibility(View.VISIBLE);
        mZoomHost.bringToFront();
    }

    /** 把仪表按原来的位置塞回卡片，整屏容器收起来 */
    private void restoreZoom() {
        if (mZoomed == null) return;
        View view = mZoomed;
        if (view instanceof CompassView) {
            ((CompassView) view).setZoomed(false);
        } else if (view instanceof BubbleLevelView) {
            ((BubbleLevelView) view).setZoomed(false);
        }
        mZoomHost.removeView(view);
        if (mZoomedParent != null) {
            mZoomedParent.addView(view, Math.min(mZoomedIndex, mZoomedParent.getChildCount()),
                    mZoomedParams);
        }
        mZoomed = null;
        mZoomedParent = null;
        mZoomedParams = null;
        mZoomHost.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (mZoomed != null) {
            restoreZoom();
            return;
        }
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        register(mAccelSensor);
        register(mMagSensor);
        register(mStepSensor);
        register(mProxSensor);
        startGpsUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }
        stopGpsUpdates();
    }

    private void register(Sensor sensor) {
        if (mSensorManager != null && sensor != null) {
            mSensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasStepPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isGpsEnabled() {
        return mLocationManager != null
                && mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    private void requestSensorPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACTIVITY_RECOGNITION},
                RC_LOCATION_STEP);
    }

    @SuppressLint("MissingPermission")
    private void startGpsUpdates() {
        if (mLocationManager == null || !hasLocationPermission()) {
            return;
        }
        mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                GPS_MIN_TIME_MS, GPS_MIN_DISTANCE_M, mLocationListener, Looper.getMainLooper());
    }

    private void stopGpsUpdates() {
        if (mLocationManager != null) {
            mLocationManager.removeUpdates(mLocationListener);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != RC_LOCATION_STEP || grantResults.length == 0) {
            return;
        }
        for (int i = 0; i < permissions.length; i++) {
            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])
                    && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                mAltitude.setAvailable(isGpsEnabled());
                startGpsUpdates();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor != null && sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            mCompass.setAccuracy(accuracy);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null) return;
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                lowPass(mAccel, event.values, ALPHA);
                mAccelReady = true;
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                lowPass(mMag, event.values, ALPHA);
                mMagReady = true;
                mCompass.setAccuracy(event.accuracy);
                if (mMagnetic != null) {
                    float field = (float) Math.sqrt(mMag[0] * mMag[0]
                            + mMag[1] * mMag[1] + mMag[2] * mMag[2]);
                    mMagnetic.setField(field);
                }
                break;
            case Sensor.TYPE_STEP_COUNTER:
                if (mStep != null) {
                    mStep.setSteps((long) event.values[0]);
                }
                return;
            case Sensor.TYPE_PROXIMITY:
                if (mProximity != null) {
                    mProximity.setDistance(event.values[0]);
                }
                return;
            default:
                return;
        }
        long now = System.currentTimeMillis();
        if (now - mLastUiUpdate < MIN_UI_INTERVAL_MS) return;
        mLastUiUpdate = now;
        updateHeading();
        updateLevel();
    }

    /** 加速度 + 磁场 → 旋转矩阵 → 方位角；用 sin/cos 分量做平滑，避免跨 0° 跳变 */
    private void updateHeading() {
        if (!mAccelReady || !mMagReady) return;
        if (!SensorManager.getRotationMatrix(mRotation, null, mAccel, mMag)) return;
        SensorManager.getOrientation(mRotation, mOrientation);
        double rad = mOrientation[0];
        if (!mHeadingReady) {
            mSinSum = (float) Math.sin(rad);
            mCosSum = (float) Math.cos(rad);
            mHeadingReady = true;
        } else {
            mSinSum += ((float) Math.sin(rad) - mSinSum) * ALPHA;
            mCosSum += ((float) Math.cos(rad) - mCosSum) * ALPHA;
        }
        float azimuth = (float) Math.toDegrees(Math.atan2(mSinSum, mCosSum));
        if (azimuth < 0) azimuth += 360f;
        mCompass.setAzimuth(azimuth);
    }

    /**
     * 水平仪的两个倾角：静止时加速度计量的就是重力方向，
     * atan2(侧向分量, 竖直分量) 即为该方向的倾角。
     */
    @SuppressLint("SetTextI18n")
    private void updateLevel() {
        if (!mAccelReady) return;
        float x = mAccel[0];
        float y = mAccel[1];
        float z = mAccel[2];
        float tiltX = (float) Math.toDegrees(Math.atan2(x, z));
        float tiltY = (float) Math.toDegrees(Math.atan2(y, z));
        mLevel.setTilt(tiltX, tiltY);
    }

    private static void lowPass(float[] out, float[] in, float alpha) {
        if (in == null || in.length < 3) return;
        for (int i = 0; i < 3; i++) {
            out[i] += (in[i] - out[i]) * alpha;
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
