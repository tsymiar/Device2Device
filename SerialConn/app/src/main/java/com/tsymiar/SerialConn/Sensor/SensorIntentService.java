package com.tsymiar.SerialConn.Sensor;

import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import com.tsymiar.SerialConn.DeviceListActivity;
import com.tsymiar.SerialConn.R;

import java.io.IOError;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public class SensorIntentService extends IntentService {

    private static final String TAG = "SensorIntentService";
    private BluetoothAdapter btAdapter = null;
    private BluetoothSocket btSocket = null;
    private OutputStream outStream = null;
    String address;
    String msg;
    String s;
    boolean flag;
    BroadcastReceiver cmdReceiver;
    Context mBase;
    // SPP UUID service
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothSocket createBluetoothSocket(BluetoothDevice device) throws IOException {
        if (Build.VERSION.SDK_INT >= 10) {
            try {
                final Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", new Class[]{UUID.class});
                return (BluetoothSocket) m.invoke(device, MY_UUID);
            } catch (Exception e) {
                Log.e(TAG, "Could not create Insecure RFComm Connection", e);
            }
        }
        return device.createRfcommSocketToServiceRecord(MY_UUID);
    }

    public SensorIntentService() {
        super("SensorIntentService");
        Log.i(TAG, this + " is constructed");
    }

    /*
    // 反射获取资源id
    public static int getCompentID(String packageName, String className,String idName) {
        int id = 0;
        try {
            Class<?> cls = Class.forName( packageName + ".R$" + className);
            id = cls.getField(idName).getInt(cls);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return id;
    }
    */
    @Override
    protected void onHandleIntent(Intent intent) {
        String address = intent.getStringExtra("address");
        // 定义NotificationManager
        String notice = Context.NOTIFICATION_SERVICE;
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(notice);
        // 定义通知栏展现的内容信息
        CharSequence tickerText = "Sensors";
        long when = System.currentTimeMillis();
        Notification notification = new Notification(R.id.notification, tickerText, when);
        // 定义下拉通知栏时要展现的内容信息
        Context context = getApplicationContext();
        CharSequence contentTitle = "SensorIntentService";
        CharSequence contentText = "后台服务已开启。详情:" + address;
        Intent notificationIntent = new Intent(this, DeviceListActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        //notification.setLatestEventInfo(context, contentTitle, contentText,contentIntent);
        // 用mNotificationManager的notify方法通知用户生成标题栏消息通知
        mNotificationManager.notify(0, notification);
    }

    // dialog窗口。
    public void dialog() {
        Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Service");
        builder.setMessage("你确定要关闭服务吗?");
        builder.setNegativeButton("×", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                return;
            }
        });
        builder.setPositiveButton("√", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                flag = false;
                stopSelf();
            }
        });

        final AlertDialog dialog = builder.create();
        // 在dialog  show方法之前添加如下代码，表示该dialog是一个系统的dialog**
        dialog.getWindow().setType((WindowManager.LayoutParams.TYPE_SYSTEM_ALERT));
        new Thread() {
            public void run() {
                SystemClock.sleep(4000);
                Looper.prepare();
                dialog.show();
                Looper.loop();
            }
        }.start();
    }

    private void checkBTState() {

        // Check for Bluetooth support and then check to make sure it is turned on
        // Emulator doesn't support Bluetooth and will return null
        if (btAdapter == null) {
            Toast.makeText(getApplicationContext(), "Fatal Error" + "Bluetooth not support", Toast.LENGTH_SHORT).show();
        } else {
            if (btAdapter.isEnabled()) {
                Log.d(TAG, "...Bluetooth ON...");
            } else {
                // Prompt user to turn on Bluetooth
                Intent intent = new Intent(this, SensorListActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        }
    }

    @Override
    public Intent registerReceiver(
            BroadcastReceiver receiver, IntentFilter filter) {
        return mBase.registerReceiver(receiver, filter);
    }

    // 接收Activity传送过来的命令
    private class CommandReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String address = intent.getStringExtra("address"/* -1*/);//获取Extra
            msg = address;
        }

        // 前台Activity调用startService时，该方法自动执行
        public int onStartCommand(Intent intent, int flags, int startId) {//重写onStartCommand方法
            cmdReceiver = new CommandReceiver();
            IntentFilter filter = new IntentFilter();//创建IntentFilter对象

            /**
             *注册一个广播，用于接收Activity传送过来的命令，控制Service的行为，如：发送数据，停止服务等
             *addAction一般用包名+文件名，避免重复
             *如果重复，Android系统在运行期会显示一个接收相同Action的服务程序列表，供用户选择。
             *注册同一个action的程序，能否同时接收广播，待测试.....
             **/
            filter.addAction("com.TsyQi909006258.Sensor.MyService");
            //注册Broadcast Receiver
            registerReceiver(cmdReceiver, filter);
            doJob();//调用方法启动线程
            return onStartCommand(intent, flags, startId);
        }

        //方法：
        public void doJob() {
            new Thread() {
                public void run() {
                    ArrayList<HashMap<String, String>> data;
                    while (flag) {
                        try {//睡眠一段时间
                            Thread.sleep(5000);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }.start();
        }
    }

    private void sendData(String s) {

        String hint = "数据发送不成功。详情：";
        byte[] msgBuffer = s.getBytes();
        Log.d(TAG, "...Send data: " + s + "...");
        try {
            outStream.write(msgBuffer);
        } catch (IOException e) {
            String msg = "In onResume() and an exception occurred during write: " + e.getMessage();
            if (address.equals("00:00:00:00:00:00"))
                msg = msg + ".\n\nUpdate your server address from 00:00:00:00:00:00 to the correct address on line 35 in the java code";
            this.btAdapter.disable();
            msg = msg + ".\n\nCheck that the SPP UUID: " + MY_UUID.toString() + " exists on server.\n\n";
            Toast.makeText(getBaseContext(), hint + "\n" + msg, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Called when the service is first created.
     **/
    public void connect(Intent intent) {
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        checkBTState();
        // ok=true;
        // if(ok){
        address = intent.getStringExtra("address");
        try // if(address.equals("00:00:00:00:00:00")) 
        {
            BluetoothDevice device = btAdapter.getRemoteDevice(address);
            System.out.println("Sevice_address---------------'" + address);
            //   Toast.makeText(MyService.this, address, Toast.LENGTH_SHORT).show();
            //   Two things are needed to make a connection:
            //   A MAC address, which we got above.
            //   A Service ID or UUID.  In this case we are using the
            //   UUID for SPP.
            String msg = "In onResume() and an exception occurred during write: ";
            try {
                btSocket = createBluetoothSocket(device);
            } catch (IOException e) {
                if (address.equals("00:00:00:00:00:00"))
                    msg = msg + ".\n\nUpdate your server address from 00:00:00:00:00:00 to the correct address on line 35 in the java code";
                this.btAdapter.disable();
                msg = msg + ".\n\nCheck that the SPP UUID: " + MY_UUID.toString() + " exists on server.\n\n";
                Toast.makeText(getBaseContext(), "\n" + msg + e.getMessage() + ".", Toast.LENGTH_LONG).show();
            }

            try {
                btSocket = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Toast.makeText(getBaseContext(), "Fatal Error" + "In onResume() and socket create failed: " + e.getMessage() + ".", Toast.LENGTH_LONG).show();
            }

            // Discovery is resource intensive.  Make sure it isn't going on
            // when you attempt to connect and pass your message.
            btAdapter.cancelDiscovery();

            // Establish the connection.  This will block until it connects.
            Log.d(TAG, "...Connecting...");
            try {
                btSocket.connect();
                Log.d(TAG, "...Connection ok...");
            } catch (IOException e) {
                try {
                    btSocket.close();
                } catch (IOException e2) {
                    Toast.makeText(getBaseContext(), "Fatal Error" + "In onResume() and unable to close socket during connection failure" + e2.getMessage() + ".", Toast.LENGTH_LONG).show();
                }
            }

            // Create a data stream so we can talk to server.
            Log.d(TAG, "...Create Socket...");

            try {
                outStream = btSocket.getOutputStream();
            } catch (IOException e) {
                Toast.makeText(getBaseContext(), "Fatal Error" + "In onResume() and output stream creation failed:" + e.getMessage() + ".", Toast.LENGTH_LONG).show();
                btAdapter = BluetoothAdapter.getDefaultAdapter();
            }
        } catch (IllegalArgumentException e) {
            Toast.makeText(getBaseContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
        }
        // Set up a pointer to the remote node using it's address.
        // }ok=false;
    }

    @Override
    public void onStart(Intent intent, int startId) {
        // TODO: Implement this method
        super.onStart(intent, startId);
        connect(intent);
        try {
            // s=intent.getStringExtra("s").toString();
            // sendData(s);
        } catch (IOError e) {
            e.printStackTrace();
            Toast.makeText(getBaseContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /*广播类*/
    public class StaticReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra(s);
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
        }
    }

    public void registerBoradcastReceiver() {
        IntentFilter myIntentFilter = new IntentFilter();
        // myIntentFilter.addAction("broadcast");
        // register
        registerReceiver(mBroadcastReceiver, myIntentFilter);
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals("broadcast")) {
                Toast.makeText(SensorIntentService.this, "处理广播", Toast.LENGTH_SHORT);
                sendData(s);
            }
        }
    };
/*
  服务与activity绑定时才调用
	@Override
	public IBinder onBind(Intent intent) {
		Bundle bundle = intent.getExtras(); 
		//接收字符串
		String address = bundle.getString("address"); 
		/**接收int类型数据
		 *int numVal = bundle.getInt("intValue");
		 *接收字节流，你可以把文件放入字节流
		 *byte[] bytes = bundle.getByteArray("bytesValue");
		 *//*
		return null;
	}
*/

    @Override
    public void onDestroy() {
        // TODO: Implement this methodD
        dialog();
        super.onDestroy();
    }
}
