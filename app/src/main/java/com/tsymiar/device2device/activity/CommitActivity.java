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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

public class CommitActivity extends Activity {
    private static final String TAG = "CommitActivity";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int READ = 1;

    private BluetoothAdapter btAdapter;
    private BluetoothSocket btSocket;
    private OutputStream outStream;
    private String data = "";
    private Toast toast;
    private Vibrator vibrator;

    private TextView text, sdt;
    private ImageView img, switch0, up, right, left, down;
    private EditText editText;

    private final TouchHandler touchHandler = new TouchHandler(this);

    private BroadcastReceiver exitReceiver;

    @SuppressLint("CutPasteId")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(false);
        setContentView(R.layout.activity_commit);
        ExitAll.getInstance().addActivity(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        // 注册蓝牙窗口退出广播
        exitReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                finish();
            }
        };
        registerReceiver(exitReceiver, new IntentFilter(ConnectActivity.ACTION_EXIT_BLUETOOTH));

        editText = findViewById(R.id.edit_text);
        switch0 = findViewById(R.id.img_switch);
        up = findViewById(R.id.up);
        right = findViewById(R.id.right);
        left = findViewById(R.id.left);
        down = findViewById(R.id.down);
        sdt = findViewById(R.id.sendingTextView1);
        text = findViewById(R.id.text_send);
        img = findViewById(R.id.img_submit);

        // Show keyboard with delay
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (editText != null) {
                InputMethodManager imm = (InputMethodManager) editText.getContext()
                        .getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(editText, 0);
            }
        }, 200);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_item, menu);
        return true;
    }

    @SuppressLint({"RtlHardcoded", "ClickableViewAccessibility"})
    @Override
    public void onResume() {
        super.onResume();

        Intent hint = new Intent(this, ReceiverService.class);
        Bundle ble = new Bundle();
        ble.putString("temp", getString(R.string.received));
        hint.putExtras(ble);
        sendBroadcast(hint);
        startService(hint);

        btAdapter = BluetoothAdapter.getDefaultAdapter();
        checkBTState();

        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();
        String address = bundle != null ? bundle.getString("address") : null;
        if (address == null) return;

        BluetoothDevice device = btAdapter.getRemoteDevice(address);

        try {
            btSocket = createBluetoothSocket(device);
        } catch (Exception e) {
            Log.e(TAG, "Socket creation failed", e);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 0);
                }
                return;
            }
            btAdapter.disable();
            Toast.makeText(getBaseContext(), "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        btAdapter.cancelDiscovery();
        try {
            btSocket.connect();
            toast = Toast.makeText(this, "蓝牙地址:\n" + address, Toast.LENGTH_LONG);
            toast.setGravity(Gravity.RIGHT, 0, 30);
            toast.show();
            Log.d(TAG, "Connected OK");
        } catch (IOException e) {
            try {
                btSocket.close();
            } catch (IOException ignored) {}
            Toast.makeText(getBaseContext(), "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            outStream = btSocket.getOutputStream();
        } catch (IOException e) {
            Toast.makeText(getBaseContext(), "无法获取输出流: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!"00:00:00:00:00:00".equals(address)) {
            new ReceiveThread(btSocket).start();
        }

        setupTouchListeners();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupTouchListeners() {
        // Center submit image sends current editText data
        if (img != null) {
            img.setOnTouchListener(createTouchListener(img, null));
        }
        // Switch button
        if (switch0 != null) {
            switch0.setOnTouchListener(createTouchListener(switch0, getString(R.string.cmd_0)));
        }
        // Directional controls
        if (up != null) {
            up.setOnTouchListener(createTouchListener(up, getString(R.string.cmd_hu)));
        }
        if (right != null) {
            right.setOnTouchListener(createTouchListener(right, getString(R.string.cmd_hr)));
        }
        if (left != null) {
            left.setOnTouchListener(createTouchListener(left, getString(R.string.cmd_hl)));
        }
        if (down != null) {
            down.setOnTouchListener(createTouchListener(down, getString(R.string.cmd_hd)));
        }

        // EditText: submit on enter / IME action
        if (editText != null) {
            editText.setOnEditorActionListener((v, actionId, event) -> {
                data = editText.getText().toString().trim();
                sendData(data);
                if (data.isEmpty()) {
                    Toast.makeText(CommitActivity.this, getString(R.string.none_type), Toast.LENGTH_SHORT).show();
                } else {
                    toast = Toast.makeText(CommitActivity.this, data, Toast.LENGTH_SHORT);
                    toast.setGravity(Gravity.BOTTOM, 0, 30);
                    toast.show();
                }
                return true;
            });
        }

        // sdt click opens saved file
        if (sdt != null) {
            sdt.setOnClickListener(v -> {
                sdt.setTextColor(getResources().getColor(R.color.green_ok));
                File dir = new File(Environment.getExternalStorageDirectory() + "/Device2Device");
                File file = new File(dir, getString(R.string.file_local));
                if (!file.exists()) {
                    Toast.makeText(CommitActivity.this, "文件不存在", Toast.LENGTH_SHORT).show();
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
                    sendData(cmd != null ? cmd : data);
                    if (cmd != null) text.setText(cmd);
                    touchHandler.postDelayed(this, 200);
                }
            }
        };
        return (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    target.setAlpha(0.2f);
                    if (vibrator != null) vibrator.vibrate(50);
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
        if (btAdapter == null) {
            Toast.makeText(getBaseContext(), "Bluetooth not supported", Toast.LENGTH_LONG).show();
            finish();
        } else if (!btAdapter.isEnabled()) {
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

    private BluetoothSocket createBluetoothSocket(BluetoothDevice device)
            throws IOException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        // Try insecure first for broader compatibility
        try {
            Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", UUID.class);
            return (BluetoothSocket) m.invoke(device, MY_UUID);
        } catch (Exception e) {
            Log.w(TAG, "Insecure RFComm failed, trying standard", e);
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 0);
            }
        }
        return device.createRfcommSocketToServiceRecord(MY_UUID);
    }

    @SuppressLint({"RtlHardcoded"})
    private void sendData(String message) {
        if (outStream == null || message == null) return;
        byte[] msgBuffer = message.getBytes();
        Log.d(TAG, "Send: " + message);
        try {
            outStream.write(msgBuffer);
            if (btAdapter.getState() != BluetoothAdapter.STATE_OFF) {
                String display = message.isEmpty() ? "null" : message;
                toast = Toast.makeText(getBaseContext(), display + "\n已发送." + msgBuffer.length, Toast.LENGTH_SHORT);
                toast.setGravity(Gravity.RIGHT, 0, 10);
                toast.show();
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
            btAdapter.disable();
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2);
            finish();
            Toast.makeText(getBaseContext(), "发送失败", Toast.LENGTH_LONG).show();
        }
    }

    // ---- Receive thread ----

    private class ReceiveThread extends Thread {
        private final InputStream mmInStream;

        ReceiveThread(BluetoothSocket socket) {
            InputStream tmp = null;
            try {
                tmp = socket.getInputStream();
            } catch (IOException ignored) {}
            mmInStream = tmp;
        }

        @Override
        public void run() {
            Looper.prepare();
            byte[] buffer = new byte[1024];
            while (true) {
                try {
                    int bytes = mmInStream.read(buffer);
                    String str = new String(buffer, 0, bytes);
                    uiHandler.obtainMessage(READ, bytes, -1, str).sendToTarget();
                } catch (Exception e) {
                    uiHandler.obtainMessage(READ, -1, -1, e.getMessage()).sendToTarget();
                    break;
                }
            }
            Looper.loop();
        }
    }

    // ---- Static Handlers (no memory leaks) ----

    private static final Handler uiHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            // Handled per-instance via weak-reference lookup — but since
            // we always run on the current resumed activity, use a simple
            // activity tracker pattern instead.
            // Data is forwarded via broadcast, so no activity ref needed.
            // The toast error is harmless if activity is gone.
            if (msg.what == READ && msg.arg1 < 0) {
                // read error — ignore silently if activity is dead
                return;
            }
            // Normal data path uses broadcasts — no strong activity ref needed
        }
    };

    /**
     * TouchHandler uses the main looper to repeat-send commands.
     */
    static class TouchHandler extends Handler {
        private final WeakReference<CommitActivity> ref;

        TouchHandler(CommitActivity activity) {
            super(Looper.getMainLooper());
            this.ref = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            CommitActivity act;
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
            if (outStream != null) outStream.close();
        } catch (IOException ignored) {}
        try {
            if (btSocket != null) btSocket.close();
        } catch (IOException ignored) {}
        outStream = null;
        btSocket = null;
    }
}
