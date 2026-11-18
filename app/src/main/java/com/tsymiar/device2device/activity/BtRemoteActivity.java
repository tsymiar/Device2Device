package com.tsymiar.device2device.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Vibrator;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.graphics.PorterDuff;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.utils.ExitAll;
import com.tsymiar.device2device.service.ReceiverService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BtRemoteActivity extends Activity {
    private static final String TAG = "BtRemoteActivity";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int READ = 1;

    private BluetoothAdapter mBtAdapter;
    private BluetoothSocket mBtSocket;
    private OutputStream mOutStream;
    private String mData = "";
    private Toast mToast;
    private Vibrator mVibrator;

    private TextView mText, mSdt;
    /** 收到的字符命令累积在这里，通过 startService 送到已有的悬浮窗 */
    private String mReceivedText = "";
    private ImageView mImg, mSwitch0, mUp, mRight, mLeft, mDown;
    private EditText mEditText;

    private final TouchHandler touchHandler = new TouchHandler(this);
    /** 接收线程把读到的字符命令送回主线程；弱引用持有 Activity，不因线程存活而泄漏 */
    private final BtUiHandler uiHandler = new BtUiHandler(this);
    private boolean mSwitchToggled = false;

    private BroadcastReceiver exitReceiver;

    @SuppressLint("CutPasteId")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(false);
        setContentView(R.layout.activity_serial);
        ExitAll.getInstance().addActivity(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        mVibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        // 注册蓝牙窗口退出广播
        exitReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                finish();
            }
        };
        registerReceiver(exitReceiver, new IntentFilter(BtDialogActivity.ACTION_EXIT_BLUETOOTH));

        mEditText = findViewById(R.id.edit_text);
        mSwitch0 = findViewById(R.id.img_switch);
        mUp = findViewById(R.id.up);
        mRight = findViewById(R.id.right);
        mLeft = findViewById(R.id.left);
        mDown = findViewById(R.id.down);
        mSdt = findViewById(R.id.tv_sending);
        mText = findViewById(R.id.text_send);
        mImg = findViewById(R.id.img_submit);

        // Show keyboard with delay
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (mEditText != null) {
                InputMethodManager imm = (InputMethodManager) mEditText.getContext()
                        .getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(mEditText, 0);
            }
        }, 200);

        // Shrink directional controls by 50% relative to their layout size
        shrinkView(mSwitch0);
        shrinkView(mUp);
        shrinkView(mDown);
        shrinkView(mLeft);
        shrinkView(mRight);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.options, menu);
        return true;
    }

    @SuppressLint({"RtlHardcoded", "ClickableViewAccessibility"})
    @Override
    public void onResume() {
        super.onResume();

        Intent hint = new Intent(this, ReceiverService.class);
        Bundle ble = new Bundle();
        ble.putString("cmd", getString(R.string.received));
        hint.putExtras(ble);
        sendBroadcast(hint);
        startService(hint);

        mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        checkBTState();

        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();
        String address = bundle != null ? bundle.getString("address") : null;
        if (address == null) return;
        // 返回前台时若上一条 SPP 连接还活着，不要重复 connect（否则旧 socket 泄漏、接收线程双开）
        if (mBtSocket != null && mBtSocket.isConnected()) return;

        BluetoothDevice device = mBtAdapter.getRemoteDevice(address);
        Log.i(TAG, "connect start: address=" + address + " type=" + device.getType()
                + " bond=" + device.getBondState());

        final List<BluetoothSocket> candidates = createCandidateSockets(device);
        if (candidates.isEmpty()) {
            Toast.makeText(this, "无法创建蓝牙连接", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Toast.makeText(this, "连接中:\n" + address, Toast.LENGTH_SHORT).show();
        /**
         * BluetoothSocket.connect() 是阻塞的：它要跟对端握手，对端不应答时能卡几十秒。
         * 放在 onResume() 里跑就是主线程卡死 —— 输入派发超过 5 秒系统就按 ANR 把进程杀掉，
         * 用户看到的就是"一点连接 app 直接没了"。实测三次 ANR 全死在这一行。
         * 所以连接整个挪到后台线程，结果回到主线程再 Toast / finish / 起接收线程。
         */
        new Thread(() -> {
            BluetoothSocket connected = null;
            String err = null;
            // 标准 SPP UUID 常常因为远端没注册 SDP 而失败，逐个回退到反射指定 RFCOMM 通道
            for (BluetoothSocket candidate : candidates) {
                try {
                    // cancelDiscovery 在 Android 12+ 需要 BLUETOOTH_SCAN，没权限就别调，免得抛 SecurityException
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                            == PackageManager.PERMISSION_GRANTED) {
                        mBtAdapter.cancelDiscovery();
                    }
                    candidate.connect();
                    connected = candidate;
                    err = null;
                    break;
                } catch (Exception e) {
                    if (err == null) err = e.getMessage();
                    Log.w(TAG, "connect attempt failed: " + candidate + " -> " + e.getMessage());
                    try {
                        candidate.close();
                    } catch (IOException ignored) {}
                }
            }
            for (BluetoothSocket candidate : candidates) {
                if (candidate == connected) continue;
                try {
                    candidate.close();
                } catch (IOException ignored) {}
            }
            final boolean ok = connected != null;
            final String error = err;
            final BluetoothSocket connectedSocket = connected;
            runOnUiThread(() -> onConnectResult(ok, error, address, connectedSocket));
        }, "bt-connect").start();
    }

    /**
     * 连接结果回到主线程处理。
     *
     * 用局部变量 socket 而不是 mBtSocket：连接期间 onDestroy() 可能已经跑过 closeSocket()
     * 把字段置空了，那时再去读字段就是 NPE。
     */
    private void onConnectResult(boolean connected, String error, String address, BluetoothSocket socket) {
        Log.i(TAG, "connect result: connected=" + connected + " error=" + error
                + " address=" + address);
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (!connected) {
            Toast.makeText(getBaseContext(), "连接失败: " + error, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        mBtSocket = socket;
        if (mText != null) {
            mText.setText("已连接: " + address);
        }
        mToast = Toast.makeText(this, "蓝牙地址:\n" + address, Toast.LENGTH_LONG);
        mToast.setGravity(Gravity.RIGHT, 0, 30);
        mToast.show();

        try {
            mOutStream = socket.getOutputStream();
        } catch (IOException e) {
            Toast.makeText(getBaseContext(), "无法获取输出流: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!"00:00:00:00:00:00".equals(address)) {
            new ReceiveThread(socket).start();
        }

        setupTouchListeners();
    }

    private void shrinkView(View view) {
        if (view != null) {
            view.post(() -> {
                int halfW = view.getWidth() * 3 / 4;
                int halfH = view.getHeight() * 3 / 4;
                if (halfW <= 0 || halfH <= 0) return;
                ViewGroup.LayoutParams lp = view.getLayoutParams();
                lp.width = halfW;
                lp.height = halfH;
                view.setLayoutParams(lp);
            });
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupTouchListeners() {
        // Center submit image sends current editText data
        if (mImg != null) {
            mImg.setOnTouchListener(createTouchListener(mImg, null));
        }
        // Switch button: toggle color on each click, no ACTION_UP handling
        if (mSwitch0 != null) {
            mSwitch0.setOnClickListener(v -> {
                mSwitchToggled = !mSwitchToggled;
                if (mSwitchToggled) {
                    mSwitch0.setColorFilter(getResources().getColor(R.color.green_ok), PorterDuff.Mode.SRC_IN);
                } else {
                    mSwitch0.clearColorFilter();
                }
            });
        }
        // Directional controls
        if (mUp != null) {
            mUp.setOnTouchListener(createTouchListener(mUp, getString(R.string.cmd_hu)));
        }
        if (mRight != null) {
            mRight.setOnTouchListener(createTouchListener(mRight, getString(R.string.cmd_hr)));
        }
        if (mLeft != null) {
            mLeft.setOnTouchListener(createTouchListener(mLeft, getString(R.string.cmd_hl)));
        }
        if (mDown != null) {
            mDown.setOnTouchListener(createTouchListener(mDown, getString(R.string.cmd_hd)));
        }

        // EditText: submit on enter / IME action
        if (mEditText != null) {
            mEditText.setOnEditorActionListener((v, actionId, event) -> {
                mData = mEditText.getText().toString().trim();
                sendData(mData);
                if (mData.isEmpty()) {
                    Toast.makeText(BtRemoteActivity.this, getString(R.string.none_type), Toast.LENGTH_SHORT).show();
                } else {
                    mToast = Toast.makeText(BtRemoteActivity.this, mData, Toast.LENGTH_SHORT);
                    mToast.setGravity(Gravity.BOTTOM, 0, 30);
                    mToast.show();
                }
                return true;
            });
        }

        // mSdt click opens saved file
        if (mSdt != null) {
            mSdt.setOnClickListener(v -> {
                mSdt.setTextColor(getResources().getColor(R.color.green_ok));
                File dir = new File(Environment.getExternalStorageDirectory() + "/Device2Device");
                File file = new File(dir, getString(R.string.file_local));
                if (!file.exists()) {
                    Toast.makeText(BtRemoteActivity.this, "文件不存在", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent fi = new Intent(Intent.ACTION_VIEW);
                fi.setDataAndType(Uri.fromFile(file), "text/*");
                fi.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(fi);
            });
        }
    }

    /**
     * Creates a touch listener that repeats sending a command via Handler.
     */
    @SuppressLint("ClickableViewAccessibility")
    private View.OnTouchListener createTouchListener(final ImageView target, final String cmd) {
        final Runnable repeater = new Runnable() {
            @Override
            public void run() {
                if (target.isPressed()) {
                    String data;
                    if (cmd != null) {
                        data = cmd;
                    } else {
                        data = mEditText != null ? mEditText.getText().toString().trim() : "";
                        mData = data;
                    }
                    sendData(data);
                    if (cmd != null) mText.setText(cmd);
                    touchHandler.postDelayed(this, 200);
                }
            }
        };
        return (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    target.setAlpha(0.2f);
                    if (mVibrator != null) mVibrator.vibrate(50);
                    touchHandler.removeCallbacks(repeater);
                    touchHandler.post(repeater);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    touchHandler.removeCallbacks(repeater);
                    target.setAlpha(1.0f);
                    break;
                default:
                    break;
            }
            return true;
        };
    }

    private void checkBTState() {
        if (mBtAdapter == null) {
            Toast.makeText(getBaseContext(), "Bluetooth not supported", Toast.LENGTH_LONG).show();
            finish();
        } else if (!mBtAdapter.isEnabled()) {
            finish();
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 0);
                }
                return;
            }
            startActivityForResult(enableIntent, 1);
        }
    }

    /** 按兼容性列出 SPP socket：标准 UUID + 反射指定的 RFCOMM 通道 */
    private List<BluetoothSocket> createCandidateSockets(BluetoothDevice device) {
        List<BluetoothSocket> list = new ArrayList<>();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return list;
        }
        try {
            list.add(device.createInsecureRfcommSocketToServiceRecord(MY_UUID));
        } catch (Exception e) {
            Log.w(TAG, "createInsecureRfcomm failed: " + e.getMessage());
        }
        try {
            list.add(device.createRfcommSocketToServiceRecord(MY_UUID));
        } catch (Exception e) {
            Log.w(TAG, "createRfcomm failed: " + e.getMessage());
        }
        // 非标准 SPP 设备常把串口开在 RFCOMM 1/2/3 通道上，反射是最后手段
        try {
            Method method = device.getClass().getMethod("createRfcommSocket", int.class);
            for (int channel : new int[]{1, 2, 3}) {
                try {
                    list.add((BluetoothSocket) method.invoke(device, channel));
                } catch (Exception e) {
                    Log.w(TAG, "reflect createRfcommSocket(" + channel + ") failed: "
                            + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "no reflection createRfcommSocket: " + e.getMessage());
        }
        return list;
    }

    @SuppressLint({"RtlHardcoded"})
    private void sendData(String message) {
        if (mOutStream == null || message == null) return;
        byte[] msgBuffer = message.getBytes(StandardCharsets.UTF_8);
        try {
            mOutStream.write(msgBuffer);
            mOutStream.flush();
            if (mText != null) {
                mText.setText("已发送: " + (message.isEmpty() ? "NULL" : message));
            }
            if (mBtAdapter.getState() != BluetoothAdapter.STATE_OFF) {
                String display = message.isEmpty() ? "NULL" : message;
                mToast = Toast.makeText(getBaseContext(), "  " + display + "\n" + msgBuffer.length + " 字节已发送", Toast.LENGTH_SHORT);
                mToast.setGravity(Gravity.RIGHT, 0, 10);
                mToast.show();
            }
        } catch (IOException e) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 0);
                }
                return;
            }
            mBtAdapter.disable();
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2);
            finish();
            Toast.makeText(getBaseContext(), "发送失败", Toast.LENGTH_LONG).show();
        }
    }

    // ---- Receive thread ----

    private class ReceiveThread extends Thread {
        private final InputStream mmInStream;

        ReceiveThread(BluetoothSocket socket) {
            InputStream instr = null;
            try {
                instr = socket.getInputStream();
            } catch (IOException ignored) {}
            mmInStream = instr;
        }

        @Override
        public void run() {
            if (mmInStream == null) return;
            byte[] buffer = new byte[1024];
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int bytes = mmInStream.read(buffer);
                    if (bytes <= 0) break;
                    String str = new String(buffer, 0, bytes, StandardCharsets.UTF_8);
                    uiHandler.obtainMessage(READ, bytes, -1, str).sendToTarget();
                } catch (Exception e) {
                    uiHandler.obtainMessage(READ, -1, -1, e.getMessage()).sendToTarget();
                    break;
                }
            }
        }
    }

    // ---- Handlers ----

    /** 接收线程回传 UI 的弱引用 Handler */
    private static class BtUiHandler extends Handler {
        private final WeakReference<BtRemoteActivity> ref;

        BtUiHandler(BtRemoteActivity activity) {
            super(Looper.getMainLooper());
            ref = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            BtRemoteActivity activity = ref.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            if (msg.what != READ) return;
            if (msg.arg1 < 0) {
                activity.onReadError(String.valueOf(msg.obj));
            } else if (msg.obj instanceof String) {
                activity.onReceived((String) msg.obj);
            }
        }
    }

    /** 把收到的字符命令送到悬浮窗 ReceiverService */
    private void onReceived(String text) {
        if (text == null || text.isEmpty()) return;
        mReceivedText += (mReceivedText.isEmpty() ? "" : "\n") + text.trim();
        if (mReceivedText.length() > 4000) {
            mReceivedText = mReceivedText.substring(mReceivedText.length() - 4000);
        }
        Intent hint = new Intent(this, ReceiverService.class);
        hint.putExtra("cmd", mReceivedText);
        startService(hint);
        if (mText != null && mText.getText().toString().startsWith("已连接")) {
            mText.setText("收到: " + text.trim());
        }
    }

    private void onReadError(String error) {
        if (mText != null) {
            mText.setText("蓝牙连接断开: " + error);
        }
        onReceived("[断开] " + error);
    }

    /**
     * TouchHandler uses the main looper to repeat-send commands.
     */
    static class TouchHandler extends Handler {
        private final WeakReference<BtRemoteActivity> ref;

        TouchHandler(BtRemoteActivity activity) {
            super(Looper.getMainLooper());
            this.ref = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            BtRemoteActivity act;
            act = ref.get();
            if (act == null) return;
            if (msg.what == 0 && msg.obj instanceof String) {
                act.sendData((String) msg.obj);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Cancel any pending repeat-send callbacks
        touchHandler.removeCallbacksAndMessages(null);
        // NOTE: do NOT call onResume() here — that caused infinite recursion
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeSocket();
        touchHandler.removeCallbacksAndMessages(null);
        if (exitReceiver != null) {
            try {
                unregisterReceiver(exitReceiver);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void closeSocket() {
        try {
            if (mOutStream != null) mOutStream.close();
        } catch (IOException ignored) {}
        try {
            if (mBtSocket != null) mBtSocket.close();
        } catch (IOException ignored) {}
        mOutStream = null;
        mBtSocket = null;
    }
}
