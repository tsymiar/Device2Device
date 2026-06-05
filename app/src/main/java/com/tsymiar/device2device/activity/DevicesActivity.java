package com.tsymiar.device2device.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.utils.ExitAll;

import java.util.Set;

public class DevicesActivity extends Activity {
    private static final String TAG = "DevicesActivity";

    private BluetoothAdapter mBtAdapter;
    private ArrayAdapter<String> mNewDevicesArrayAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ExitAll.getInstance().addActivity(this);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.activity_devlist);
        setResult(Activity.RESULT_CANCELED);

        // Scan button
        ImageView scanView = findViewById(R.id.img_search);
        assert scanView != null;
        scanView.setOnClickListener(v -> {
            doDiscovery();
            findViewById(R.id.img_search).setVisibility(View.GONE);
            findViewById(R.id.scan).setVisibility(View.GONE);
            findViewById(R.id.btn1).setVisibility(View.GONE);
            v.setVisibility(View.GONE);
        });

        ArrayAdapter<String> pairedAdapter = new ArrayAdapter<>(this, R.layout.activity_devices);
        mNewDevicesArrayAdapter = new ArrayAdapter<>(this, R.layout.activity_devices);

        ListView pairedList = findViewById(R.id.devlist_paired_devices);
        assert pairedList != null;
        pairedList.setAdapter(pairedAdapter);
        pairedList.setOnItemClickListener(mDeviceClickListener);

        ListView newDevicesList = findViewById(R.id.new_devices);
        assert newDevicesList != null;
        newDevicesList.setAdapter(mNewDevicesArrayAdapter);
        newDevicesList.setOnItemClickListener(mDeviceClickListener);

        // Register receivers
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(mReceiver, filter);
        filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(mReceiver, filter);

        mBtAdapter = BluetoothAdapter.getDefaultAdapter();

        // List paired devices
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 0);
            }
            return;
        }
        Set<BluetoothDevice> pairedDevices = mBtAdapter.getBondedDevices();
        if (!pairedDevices.isEmpty()) {
            findViewById(R.id.list_title_dev).setVisibility(View.VISIBLE);
            for (BluetoothDevice device : pairedDevices) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                pairedAdapter.add(device.getName() + "\n" + device.getAddress());
            }
        } else {
            Toast toast = Toast.makeText(this, R.string.none_paired, Toast.LENGTH_SHORT);
            toast.setGravity(Gravity.CENTER, 0, -100);
            toast.show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Button exitBtn = findViewById(R.id.btn1);
        if (exitBtn != null) {
            exitBtn.setOnClickListener(v -> {
                // 清除可检测性残留的系统UI浮层
                resetScanMode();
                // 退出由 btn_bluetooth 启动的所有窗口
                sendBroadcast(new Intent(ConnectActivity.ACTION_EXIT_BLUETOOTH));
                setResult(Activity.RESULT_FIRST_USER);
                finish();
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBtAdapter != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.BLUETOOTH_SCAN}, 0);
                }
                // Don't return here — still try to unregister
            } else {
                mBtAdapter.cancelDiscovery();
            }
        }
        try {
            unregisterReceiver(mReceiver);
        } catch (IllegalArgumentException ignored) {}
    }

    private void doDiscovery() {
        Log.d(TAG, "doDiscovery()");
        setProgressBarIndeterminateVisibility(true);
        setTitle(R.string.scanning);
        findViewById(R.id.devlist_title_new_devices).setVisibility(View.VISIBLE);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_SCAN}, 0);
            }
            return;
        }
        if (mBtAdapter.isDiscovering()) {
            mBtAdapter.cancelDiscovery();
        }
        mBtAdapter.startDiscovery();
    }

    /**
     * 重置蓝牙扫描模式，清除 ACTION_REQUEST_DISCOVERABLE 残留的系统UI浮层。
     * 通过反射调用 setScanMode 以兼容较新 SDK 中该方法已被移除的情况。
     */
    @SuppressLint("MissingPermission")
    private void resetScanMode() {
        if (mBtAdapter == null || !mBtAdapter.isEnabled()) return;
        try {
            if (mBtAdapter.getScanMode() == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                java.lang.reflect.Method method = BluetoothAdapter.class.getMethod("setScanMode", int.class);
                method.invoke(mBtAdapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE);
                Log.d(TAG, "Scan mode reset to CONNECTABLE");
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot reset scan mode: " + e.getMessage());
        }
    }

    private final AdapterView.OnItemClickListener mDeviceClickListener =
            (av, v, arg2, arg3) -> {
                if (ActivityCompat.checkSelfPermission(getBaseContext(), Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ActivityCompat.requestPermissions(DevicesActivity.this,
                                new String[]{Manifest.permission.BLUETOOTH_SCAN}, 0);
                    }
                    return;
                }
                mBtAdapter.cancelDiscovery();

                String info = ((TextView) v).getText().toString();
                String address = info.substring(info.length() - 17);

                Intent intent = new Intent(DevicesActivity.this, CommitActivity.class);
                Bundle bundle = new Bundle();
                bundle.putString("address", address);
                intent.putExtras(bundle);
                sendBroadcast(intent);
                startActivity(intent);
            };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (ActivityCompat.checkSelfPermission(getBaseContext(), Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ActivityCompat.requestPermissions(DevicesActivity.this,
                                new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 0);
                    }
                    return;
                }
                if (device != null && device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    mNewDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                setProgressBarIndeterminateVisibility(false);
                setTitle(R.string.select_device);
                if (mNewDevicesArrayAdapter.getCount() == 0) {
                    mNewDevicesArrayAdapter.add(getResources().getText(R.string.none_found).toString());
                }
            }
        }
    };
}
