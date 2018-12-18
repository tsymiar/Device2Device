package com.tsymiar.connect;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Vibrator;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class TransferActivity extends Activity {
    private static final String TAG = "ChatAct";
    private BluetoothAdapter btAdapter = null;
    private BluetoothSocket btSocket = null;
    private OutputStream outStream = null;
    private String data = "1";
    private Toast toast;
    private int READ = 1;
    private String s;
    private boolean h;
    private TextView sdt;
    ImageView img;
    ImageView switch0;
    ImageView up;
    ImageView right;
    ImageView left;
    ImageView down;
    TextView st;
    EditText editText;
    MainActivity mainActivity = new MainActivity();
    // SPP UUID service
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private Vibrator vibrator;

    /**
     * Called when the activity is first created.
     **/
    @SuppressLint("CutPasteId")
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(false);
        setContentView(R.layout.activity_transfer);
        ExitApplication.getInstance().addActivity(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        editText = (EditText) findViewById(R.id.edit_text);
        switch0 = (ImageView) findViewById(R.id.switch0);
        up = (ImageView) findViewById(R.id.up);
        right = (ImageView) findViewById(R.id.right);
        left = (ImageView) findViewById(R.id.left);
        down = (ImageView) findViewById(R.id.down);
        st = (TextView) findViewById(R.id.sendingTextView1);

        sdt = (TextView) findViewById(R.id.sdt);
        img = (ImageView) findViewById(R.id.imageView2);
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            public void run() {
                assert editText != null;
                InputMethodManager inputmanager = (InputMethodManager) editText.getContext().getSystemService(TransferActivity.INPUT_METHOD_SERVICE);
                if (inputmanager != null) {
                    inputmanager.showSoftInput(editText, 0);
                }
            }
        }, 200);

    }

    public boolean onCreateOptionsMenu(Menu menu) {
        // getMenuInflater().inflate(R.menu.menu_main,menu);
        getMenuInflater().inflate(R.menu.menu_item, menu);
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.item: {
                Intent intent = new Intent(this, NewActivity.class);
                startActivity(intent);
            }
        }
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.url:
                mainActivity.writer(this);
                return true;
            case R.id.feedback:
                mainActivity.feedback(this);
                return true;
            case R.id.more:
                mainActivity.more(this);
                return true;
            case R.id.exit:
                mainActivity.exit(this);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @SuppressLint({"RtlHardcoded", "WrongConstant", "ClickableViewAccessibility"})
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    @Override
    public void onResume() {
        super.onResume();
        Intent hint = new Intent(TransferActivity.this, ReceiverService.class);
        Bundle ble = new Bundle();
        ble.putString("temp", getString(R.string.datarecv));
        hint.putExtras(ble);
        sendBroadcast(hint);
        startService(hint);
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        checkBTState();
        ////
        Log.d(TAG, "...onResume - try connect...");
        Intent intent = this.getIntent();
        Bundle bundle = intent.getExtras();
        String address = bundle != null ? bundle.getString("address") : null;
        // Set up a pointer to the remote node using it's address.
        BluetoothDevice device = btAdapter.getRemoteDevice(address);
        //   Two things are needed to make a connection:
        //   A MAC address, which we got above.
        //   A Service ID or UUID.  In this case we are using the
        //   UUID for SPP.

        try {
            System.out.println("Sevice_address---------------'" + address);
            String msg = "接收意外中断: ";

            try {
                btSocket = createBluetoothSocket(device);
            } catch (IOException e) {
                this.btAdapter.disable();
                msg = msg + "串口已关闭。";
                Toast.makeText(getBaseContext(), "\n" + msg + e.getMessage() + ".", Toast.LENGTH_SHORT).show();
            }

            try {
                btSocket = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Toast.makeText(getBaseContext(), e.getMessage() + ".", Toast.LENGTH_SHORT).show();
            }
            // Discovery is resource intensive.  Make sure it isn't going on
            // when you attempt to connect and pass your message.
            btAdapter.cancelDiscovery();
            // Establish the connection.  This will block until it connects.
            Log.d(TAG, "...Connecting...");
            try {
                btSocket.connect();
                toast = Toast.makeText(TransferActivity.this, "蓝牙地址为:\n" + address, Toast.LENGTH_LONG);
                toast.setGravity(Gravity.RIGHT, 0, 30);
                toast.show();
                Log.d(TAG, "...Connection ok...");
            } catch (IOException e) {
                try {
                    btSocket.close();
                    if (btAdapter.getState() == BluetoothAdapter.STATE_DISCONNECTED)
                        Toast.makeText(getBaseContext(), "\t本次连接不成功,请返回多试几次。\n" + e.getMessage() + ".", Toast.LENGTH_SHORT).show();
                } catch (IOException e2) {
                    Toast.makeText(getBaseContext(), e2.getMessage() + ".", Toast.LENGTH_SHORT).show();
                }
            }
            // Create a data stream so we can talk to server.
            Log.d(TAG, "...Create Socket...");

            try {
                outStream = btSocket.getOutputStream();
            } catch (IOException e) {
                errorExit(e.getMessage(), ".");
            }
            if (!(address != null && address.equals("00:00:00:00:00:00")))
                new RecieveThread(btSocket).start();
        } catch (IOError ignored) {
        }
        final ImageView imageView2;
        imageView2 = (ImageView) findViewById(R.id.imageView2);
        Button btn0 = (Button) findViewById(R.id.btn0);

        assert imageView2 != null;
        imageView2.setOnTouchListener(new View.OnTouchListener() {

            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_MOVE:
                        imageView2.setAlpha(50);
                        vibrator.vibrate(50);
                        new Thread() {
                            public void run() {
                                Message msg = new Message();
                                msg.what = 0;
                                i2.sendMessage(msg);
                                try {
                                    sleep(200);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }.start();
                        break;
                    case MotionEvent.ACTION_UP:
                        imageView2.setAlpha(255);
                        break;
                }
                return true;
            }
        });
        // 监听回车键
        assert editText != null;
        editText.setOnEditorActionListener(new OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                data = editText.getText().toString();
                sendData(data);
                if (data.equals(" ") || data.equals("")) {
                    Toast.makeText(TransferActivity.this, "未输入", Toast.LENGTH_SHORT).show();
                } else {
                    toast = Toast.makeText(TransferActivity.this, data, Toast.LENGTH_SHORT);
                    toast.show();
                    toast.setGravity(Gravity.BOTTOM, 0, 30);
                }
                return true;
            }
        });
        assert st != null;
        st.setOnClickListener(new OnClickListener() {

            @SuppressLint("ResourceAsColor")
            public void onClick(View v) {
                st.setTextColor(R.color.green_ok);
                Intent intent = new Intent();
                intent.setAction(android.content.Intent.ACTION_VIEW);
                File file = new File("storage/sdcard0/Device2Device/signal.dat");
                intent.setDataAndType(Uri.fromFile(file), "text/*");
                startActivity(intent);
            }
        });

        assert editText != null;
        editText.setOnEditorActionListener(new OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                data = editText.getText().toString();
                sendData(data);
                if (data.equals(" ") || data.equals("")) {
                    Toast.makeText(TransferActivity.this, getString(R.string.typenone), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getBaseContext(), data + ".", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        assert img != null;
        img.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (data.equals(" ") || data.equals("")) {
                    Toast.makeText(TransferActivity.this, getString(R.string.typenone), Toast.LENGTH_SHORT).show();
                } else {
                    sdt.setText(data);
                }
            }
        });

        assert switch0 != null;
        switch0.setOnTouchListener(new View.OnTouchListener() {

            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_MOVE:
                        switch0.setAlpha(90/*Color.GRAY, PorterDuff.Mode.LIGHTEN*/);
                        vibrator.vibrate(50);
                        new Thread() {
                            public void run() {
                                Message msg = new Message();
                                msg.what = 0;
                                h0.sendMessage(msg);
                                try {
                                    sleep(200);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }.start();
                        break;
                    case MotionEvent.ACTION_UP:
                        switch0.setAlpha(255);
                        break;
                }
                return true;
            }
        });

        assert up != null;
        up.setOnTouchListener(new View.OnTouchListener() {

            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_MOVE:
                        up.setAlpha(50);
                        vibrator.vibrate(50);
                        new Thread() {
                            public void run() {
                                Message msg = new Message();
                                msg.what = 0;
                                hu.sendMessage(msg);
                                try {
                                    sleep(200);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }.start();
                        break;
                    case MotionEvent.ACTION_UP:
                        up.setAlpha(255);
                        break;
                }
                return true;
            }
        });

        assert right != null;
        right.setOnTouchListener(new View.OnTouchListener() {

            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_MOVE:
                        right.setAlpha(50);
                        vibrator.vibrate(50);
                        new Thread() {
                            public void run() {
                                Message msg = new Message();
                                msg.what = 0;
                                hr.sendMessage(msg);
                                try {
                                    sleep(200);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }.start();
                        break;
                    case MotionEvent.ACTION_UP:
                        right.setAlpha(255);
                        break;
                }
                return true;
            }
        });

        assert left != null;
        left.setOnTouchListener(new View.OnTouchListener() {

            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_MOVE:
                        left.setAlpha(50);
                        vibrator.vibrate(50);
                        new Thread() {
                            public void run() {
                                Message msg = new Message();
                                msg.what = 0;
                                hl.sendMessage(msg);
                                try {
                                    sleep(200);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }.start();
                        break;
                    case MotionEvent.ACTION_UP:
                        left.setAlpha(255);
                        break;
                }
                return true;
            }
        });

        down.setOnTouchListener(new View.OnTouchListener() {

            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_MOVE:
                        down.setAlpha(50);
                        vibrator.vibrate(50);
                        new Thread() {
                            public void run() {
                                Message msg = new Message();
                                msg.what = 0;
                                hd.sendMessage(msg);
                                try {
                                    sleep(200);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }.start();
                        break;
                    case MotionEvent.ACTION_UP:
                        down.setAlpha(255);
                        break;
                }
                return true;
            }
        });
    }

    Handler h0 = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            if (msg.what == 0) {
                sendData(getString(R.string.cmd_0));
                sdt.setText(getString(R.string.cmd_0));
            }
            super.handleMessage(msg);
        }
    };

    Handler hu = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            if (msg.what == 0) {
                sendData(getString(R.string.cmd_hu));
                sdt.setText(getString(R.string.cmd_hu));
            }
            super.handleMessage(msg);
        }
    };

    Handler hr = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            if (msg.what == 0) {
                sendData(getString(R.string.cmd_hr));
                sdt.setText(getString(R.string.cmd_hr));
            }
            super.handleMessage(msg);
        }
    };

    Handler hl = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            if (msg.what == 0) {
                sendData(getString(R.string.cmd_hl));
                sdt.setText(getString(R.string.cmd_hl));
            }
            super.handleMessage(msg);
        }
    };

    Handler hd = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            if (msg.what == 0) {
                sendData(getString(R.string.cmd_hd));
                sdt.setText(getString(R.string.cmd_hd));
            }
            super.handleMessage(msg);
        }
    };

    Handler i2 = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            if (msg.what == 0) {
                sendData(data);
            }
            super.handleMessage(msg);
        }
    };

    class RecieveThread extends Thread {

        private final InputStream mmInStream;

        @TargetApi(Build.VERSION_CODES.ECLAIR)
        protected RecieveThread(BluetoothSocket socket) {
            InputStream tmpIn = null;
            // Get the input and output streams, using temp objects because
            // member streams are final
            try {
                tmpIn = socket.getInputStream(); // 获取输入流
            } catch (IOException ignored) {
            }
            mmInStream = tmpIn;
        }

        public void run() {
            Looper.prepare();
            byte[] buffer = new byte[1024]; // buffer store for the stream
            int bytes; // bytes returned from read()
            // Keep listening to the InputStream until an exception occurs
            while (true) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer); // bytes数组返回值，为buffer数组的长度
                    // Send the obtained bytes to the UI activity
                    String str = new String(buffer, 0, bytes);
                    handler.obtainMessage(READ, bytes, -1, str)
                            .sendToTarget();     // 压入消息队列
                } catch (Exception e) {
                    s = e.getMessage();
                    System.out.print(s);
                    h = false;
                    break;
                }
            }
            Looper.loop();
        }
    }

    @SuppressLint("HandlerLeak")
    Handler handler = new Handler() {  //处理消息队列的Handler对象
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            //处理消息
            if (msg.what == READ) {
                String str = (String) msg.obj;    // 类型转化
                Intent ss = new Intent(TransferActivity.this, SaveDataService.class);
                Intent rs = new Intent(TransferActivity.this, ReceiverService.class);
                Bundle ble = new Bundle();
                ble.putString("temp", "" + str);
                rs.putExtras(ble);
                ss.putExtras(ble);
                sendBroadcast(rs);
                sendBroadcast(ss);
                startService(rs);
                startService(ss);
            } else if (!h)
                Toast.makeText(TransferActivity.this, "接收不成功：" + s + ".", Toast.LENGTH_SHORT).show();
        }
    };

    private BluetoothSocket createBluetoothSocket(BluetoothDevice device) throws IOException {
        try {
            final Method  m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", UUID.class);
            return (BluetoothSocket) m.invoke(device, MY_UUID);
        } catch (Exception e) {
            Log.e(TAG, "Could not create Insecure RFComm Connection",e);
        }
        return  device.createRfcommSocketToServiceRecord(MY_UUID);
    }

    private void checkBTState() {

        // Check for Bluetooth support and then check to make sure it is turned on
        // Emulator doesn't support Bluetooth and will return null
        if(btAdapter==null) {
            errorExit("Fatal Error", "Bluetooth not support");
        } else {
            if (btAdapter.isEnabled()) {
                Log.d(TAG, "...Bluetooth ON...");
            } else {
                this.finish();
                // Prompt user to turn on Bluetooth
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, 1);
            }
        }
    }

    private void errorExit(String title, String message){
        Toast.makeText(getBaseContext(), title + " - " + message, Toast.LENGTH_LONG).show();
        finish();
    }

    @SuppressLint("WrongConstant")
    private void sendData(String message) {
        byte[] msgBuffer = message.getBytes();
        String hint="数据发送不成功。";
        Log.d(TAG, "...Send data: " + message + "...");
        try {
            outStream.write(msgBuffer);
            if(message.equals(null)||message.equals(" ")||message.equals(""))message="null";
            if(!(btAdapter.getState()== BluetoothAdapter.STATE_DISCONNECTED)) {
                toast=Toast.makeText(getBaseContext(), message+"\n已发送。", Toast.LENGTH_SHORT);
                toast.setGravity(53,50,100);
                toast.show();
            }
        } catch (IOException e) {
            this.btAdapter.disable();
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, 2);
            this.finish();
            Toast.makeText(getBaseContext(), hint, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.onResume();
    }
}