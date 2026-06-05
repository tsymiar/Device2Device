package com.tsymiar.device2device.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.tsymiar.device2device.R;

public class ConnectActivity extends Activity {
    public static final String TAG = "Connection";
    public static final String ACTION_EXIT_BLUETOOTH = "com.tsymiar.device2device.EXIT_BLUETOOTH";

    private static final int REQUEST_DISCOVER_DEVICE = 1;
    private static final int REQUEST_ENABLE_BT = 2;

    private BluetoothAdapter mBluetoothAdapter;
    private int retryCount;
    private BroadcastReceiver exitReceiver;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_entry);

        // 注册蓝牙窗口退出广播
        exitReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                resetScanMode();
                finish();
            }
        };
        registerReceiver(exitReceiver, new IntentFilter(ACTION_EXIT_BLUETOOTH));

        // 弹窗尺寸适配，透明背景让 CardView 浮在暗色遮罩上
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.85);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            mBluetoothAdapter = bm.getAdapter();
        }

        // "可检测性" 按钮
        findViewById(R.id.btn_host).setOnClickListener(v -> {
            if (ActivityCompat.checkSelfPermission(getBaseContext(), Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ActivityCompat.requestPermissions(ConnectActivity.this,
                            new String[]{Manifest.permission.BLUETOOTH_SCAN}, 0);
                }
                return;
            }
            if (mBluetoothAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
                startActivityForResult(intent, REQUEST_DISCOVER_DEVICE);
            } else {
                Toast toast = Toast.makeText(ConnectActivity.this, R.string.can_be_find, Toast.LENGTH_SHORT);
                toast.setGravity(Gravity.CENTER, 0, 0);
                toast.show();
            }
        });

        // "搜索设备" 按钮
        findViewById(R.id.btn_join).setOnClickListener(v -> {
            if (!mBluetoothAdapter.isEnabled()) {
                Toast toast = Toast.makeText(ConnectActivity.this, R.string.retry, Toast.LENGTH_SHORT);
                toast.setGravity(Gravity.CENTER, 0, 0);
                toast.show();
                Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                if (ActivityCompat.checkSelfPermission(getBaseContext(), Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ActivityCompat.requestPermissions(ConnectActivity.this,
                                new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 0);
                    }
                    return;
                }
                startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
            } else {
                startActivityForResult(new Intent(this, DevicesActivity.class), REQUEST_DISCOVER_DEVICE);
            }
        });

        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.unsupported, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        retryCount++;
        if (requestCode == REQUEST_DISCOVER_DEVICE) {
            // DevicesActivity 返回退出信号，级联关闭自身
            if (resultCode == Activity.RESULT_FIRST_USER) {
                resetScanMode();
                finish();
                return;
            }
            // 可检测性权限未确认直接返回，重置扫描模式清除可能残留的系统UI浮层
            if (resultCode == Activity.RESULT_CANCELED) {
                resetScanMode();
            }
        }
        if (requestCode == REQUEST_ENABLE_BT && resultCode != Activity.RESULT_OK) {
            Toast.makeText(this, R.string.open_bluetooth, Toast.LENGTH_SHORT).show();
            if (retryCount <= 1) {
                Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ActivityCompat.requestPermissions(ConnectActivity.this,
                                new String[]{Manifest.permission.BLUETOOTH_CONNECT}, requestCode);
                    }
                    return;
                }
                startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exitReceiver != null) {
            try {
                unregisterReceiver(exitReceiver);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mBluetoothAdapter != null && !mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ActivityCompat.requestPermissions(ConnectActivity.this,
                            new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 0);
                }
                return;
            }
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        }
    }

    /**
     * 重置蓝牙扫描模式，清除 ACTION_REQUEST_DISCOVERABLE 残留的系统UI浮层。
     * 通过反射调用 setScanMode 以兼容较新 SDK 中该方法已被移除的情况。
     */
    @SuppressLint("MissingPermission")
    private void resetScanMode() {
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) return;
        try {
            if (mBluetoothAdapter.getScanMode() == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                java.lang.reflect.Method method = BluetoothAdapter.class.getMethod("setScanMode", int.class);
                method.invoke(mBluetoothAdapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE);
                Log.d(TAG, "Scan mode reset to CONNECTABLE");
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot reset scan mode: " + e.getMessage());
        }
    }
}
