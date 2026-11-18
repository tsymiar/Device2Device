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
import com.tsymiar.device2device.service.ReceiverService;
import com.tsymiar.device2device.utils.ExitAll;

import java.util.Set;

public class BtDevicesActivity extends Activity {
    private static final String TAG = "BtDevicesActivity";

    private BluetoothAdapter mBtAdapter;
    private ArrayAdapter<String> mPairedDevicesArrayAdapter;
    private ArrayAdapter<String> mNewDevicesArrayAdapter;
    /** 未配对设备点击后先配对，配对成功再进 Serial 连接页 */
    private String mPendingConnectAddress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ExitAll.getInstance().addActivity(this);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.activity_device_list);
        setResult(Activity.RESULT_CANCELED);

        ImageView scanView = findViewById(R.id.img_search);
        assert scanView != null;
        scanView.setOnClickListener(v -> {
            doDiscovery();
            findViewById(R.id.img_search).setVisibility(View.GONE);
            findViewById(R.id.scan).setVisibility(View.GONE);
            findViewById(R.id.btn1).setVisibility(View.GONE);
            v.setVisibility(View.GONE);
        });

        mPairedDevicesArrayAdapter = new ArrayAdapter<>(this, R.layout.item_device);
        mNewDevicesArrayAdapter = new ArrayAdapter<>(this, R.layout.item_device);

        ListView pairedList = findViewById(R.id.devlist_paired_devices);
        assert pairedList != null;
        pairedList.setAdapter(mPairedDevicesArrayAdapter);
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
        // 配对状态变化：未配对设备发起 createBond() 后靠它决定何时进连接页
        registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        // ACTION_FOUND 时 getName() 经常还是 null，名字会通过这个广播随后送达
        registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_NAME_CHANGED));

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
                mPairedDevicesArrayAdapter.add(deviceLabel(device));
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
                // 关掉悬浮窗：它是 BtRemoteActivity 拉起的 START_STICKY 前台服务，
                // 不显式 stop 时，Activity 都 finish 了那块浮层还挂在屏幕上
                ReceiverService.stop(BtDevicesActivity.this);
                // 退出由 btn_bluetooth 启动的所有窗口
                sendBroadcast(new Intent(BtDialogActivity.ACTION_EXIT_BLUETOOTH));
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
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot reset scan mode: " + e.getMessage());
        }
    }

    private final AdapterView.OnItemClickListener mDeviceClickListener =
            (av, v, arg2, arg3) -> {
                if (!hasConnectPermission()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ActivityCompat.requestPermissions(BtDevicesActivity.this,
                                new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 0);
                    }
                    return;
                }
                if (ActivityCompat.checkSelfPermission(getBaseContext(), Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED) {
                    mBtAdapter.cancelDiscovery();
                }

                String info = ((TextView) v).getText().toString();
                String address = info.substring(info.length() - 17);
                BluetoothDevice device = mBtAdapter.getRemoteDevice(address);
                Log.i(TAG, "device clicked: address=" + address
                        + " name=" + device.getName() + " type=" + device.getType()
                        + " bond=" + device.getBondState());
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    connectTo(address);
                    return;
                }

                // 搜索到的新设备：先配对。配对结果由 ACTION_BOND_STATE_CHANGED 接手；
                // 不配对直接连 SPP 在多数 Android 版本上会失败（尤其需要 PIN 的设备）。
                mPendingConnectAddress = address;
                String label = device.getName();
                Toast.makeText(this, "正在配对 "
                        + (label == null || label.trim().isEmpty() ? device.getAddress() : label)
                        + "…", Toast.LENGTH_SHORT).show();
                try {
                    if (!device.createBond()) {
                        mPendingConnectAddress = null;
                        Toast.makeText(this, "配对发起失败", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    mPendingConnectAddress = null;
                    Log.e(TAG, "createBond failed: " + address, e);
                    Toast.makeText(this, "配对失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            };

    private boolean hasConnectPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 设备名在发现阶段经常还没解析出来（getName() == null），
     * 先用"未知设备 + MAC"占位，等 ACTION_NAME_CHANGED 到了再替换。
     */
    private String deviceLabel(BluetoothDevice device, String name) {
        String n = name;
        if (n == null || n.trim().isEmpty()) {
            n = getString(R.string.bt_unknown_device);
        }
        return n + "\n" + device.getAddress();
    }

    private String deviceLabel(BluetoothDevice device) {
        return deviceLabel(device, device.getName());
    }

    /** 按 MAC 找到列表行替换标签；新设备列表里没有就补一行 */
    private void updateDeviceLabel(BluetoothDevice device, String name) {
        String label = deviceLabel(device, name);
        replaceOrAdd(mPairedDevicesArrayAdapter, device.getAddress(), label, false);
        replaceOrAdd(mNewDevicesArrayAdapter, device.getAddress(), label, true);
    }

    private void replaceOrAdd(ArrayAdapter<String> adapter, String address,
                              String label, boolean addIfMissing) {
        if (adapter == null) return;
        for (int i = 0; i < adapter.getCount(); i++) {
            String item = adapter.getItem(i);
            if (item != null && item.endsWith(address)) {
                adapter.remove(item);
                adapter.insert(label, i);
                return;
            }
        }
        if (addIfMissing) adapter.add(label);
    }

    /** 配对完成（或本来就是已配对设备）：进 Serial 页由 BtRemoteActivity 建 SPP 连接 */
    private void connectTo(String address) {
        Intent intent = new Intent(BtDevicesActivity.this, BtRemoteActivity.class);
        Bundle bundle = new Bundle();
        bundle.putString("address", address);
        intent.putExtras(bundle);
        startActivity(intent);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (ActivityCompat.checkSelfPermission(getBaseContext(), Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ActivityCompat.requestPermissions(BtDevicesActivity.this,
                                new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 0);
                    }
                    return;
                }
                if (device != null && device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    updateDeviceLabel(device, device.getName());
                }
            } else if (BluetoothDevice.ACTION_NAME_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) return;
                String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                if (name == null || name.trim().isEmpty()) name = device.getName();
                updateDeviceLabel(device, name);
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null || mPendingConnectAddress == null) return;
                if (!mPendingConnectAddress.equals(device.getAddress())) return;
                int state = device.getBondState();
                Log.i(TAG, "bond state changed: address=" + device.getAddress()
                        + " state=" + state + " pending=" + mPendingConnectAddress);
                if (state == BluetoothDevice.BOND_BONDED) {
                    mPendingConnectAddress = null;
                    Toast.makeText(BtDevicesActivity.this, "配对成功，正在连接…", Toast.LENGTH_SHORT).show();
                    connectTo(device.getAddress());
                } else if (state == BluetoothDevice.BOND_NONE) {
                    mPendingConnectAddress = null;
                    Toast.makeText(BtDevicesActivity.this, "配对失败或被取消", Toast.LENGTH_SHORT).show();
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
