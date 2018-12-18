package com.tsymiar.connect;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.Toast;

public class ConnectActivity extends Activity {
	final Activity me = this;
	public static final String TAG = "Connection";
    public static final String SYSTEM_EXIT = "exit";
    private MyReceiver receiver;
    private long mCurTime;
    private MainActivity mainActivity=new MainActivity();
    // Intent request codes
    private static final int REQUEST_DISCOVER_DEVICE = 1;
    private static final int REQUEST_ENABLE_BT = 2;
    private int r=0;

    // Member object for the chat services
    // private BluetoothMsgService mChatService = null;

	// Local Bluetooth adapter
    private BluetoothAdapter mBluetoothAdapter = null;

	/** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_begin);
        IntentFilter filter = new IntentFilter();
        filter.addAction(SYSTEM_EXIT);
        receiver = new MyReceiver();
        this.registerReceiver(receiver, filter);
        Log.d(TAG, "Connection Activity Created");
		ExitApplication.getInstance().addActivity(this);
       // getWindow().setBackgroundDrawableResource(R.drawable.tetris_bg);//Draw background
        Button hostBtn = (Button)findViewById(R.id.btn_host);
        Button joinBtn = (Button)findViewById(R.id.btn_join);
        assert hostBtn != null;
        hostBtn.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				Log.d(TAG, "ensure discoverable");
				if (mBluetoothAdapter.getScanMode() !=
					BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
					Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
					discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
					startActivityForResult(discoverableIntent, REQUEST_DISCOVER_DEVICE);

					}
				else {
					Toast toast;
					toast=Toast.makeText(ConnectActivity.this, R.string.canbefind, Toast.LENGTH_SHORT);
					toast.show();
					toast.setGravity(Gravity.CENTER,0,0);
				}
			}
		});

        assert joinBtn != null;
        joinBtn.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				if (!mBluetoothAdapter.isEnabled()) {
					Toast toast;
					toast=Toast.makeText(ConnectActivity.this, R.string.retry, Toast.LENGTH_SHORT);
					toast.show();
					toast.setGravity(Gravity.CENTER,0,0);
					Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
					startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
					// Otherwise, setup the chat session
				} else {
					// Launch the DeviceListActivity to see devices and do scan
					Intent serverIntent = new Intent(me, DeviceListActivity.class);
					startActivityForResult(serverIntent, REQUEST_DISCOVER_DEVICE);
				}
			}
		});

        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.unsupport, Toast.LENGTH_SHORT).show();
            onPause();
        }
    }

    public boolean onCreateOptionsMenu(Menu menu)
    {
		// getMenuInflater().inflate(R.menu.menu_main,menu);
        getMenuInflater().inflate(R.menu.menu_item,menu);
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.item:
				{
					Intent intent = new Intent(this, NewActivity.class);
					startActivity(intent);
				}
			}
        // Handle item selection 
        switch (item.getItemId()) {
            case R.id.url:
                mainActivity.writer(me);
                return true;
            case R.id.feedback:
                mainActivity.feedback(me);
                return true;
            case R.id.more:
                mainActivity.more(me);
                return true;
			case R.id.exit:
				mainActivity.exit(me);
				return true;
            default:
                return super.onOptionsItemSelected(item);
        }
	}

    private class MyReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    }

    @Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onActivityResult " + resultCode);
		r++;
        switch (requestCode) {
			case REQUEST_DISCOVER_DEVICE:
				// When the request to enable Bluetooth returns
                if (resultCode == 0) {
					// User did not enable Bluetooth or an error occured
					Log.d(TAG, "Device not Available");
				}
                break;
			case REQUEST_ENABLE_BT:
				// When the request to enable Bluetooth returns
                switch (resultCode) {
                    case Activity.RESULT_OK:
                        // Bluetooth is now enabled, so set up a chat session
                        break;
                    default:
                        // User did not enable Bluetooth or an error occured
                        Log.d(TAG, "BT not enabled");
                        Toast.makeText(this, R.string.openbt, Toast.LENGTH_SHORT).show();
                        if (r <= 1) {
                            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
                        }
                        break;
                }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // If BT is not on, request that it be enabled.
        // setupChat() will then be called during onActivityResult
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        // Otherwise, setup the chat session
        }
    }

    @Override
    public boolean onKeyDown ( int keyCode, KeyEvent event){

        /* Called when the activity is first created. */
        long mLastTime = mCurTime;
        mCurTime = System.currentTimeMillis();

        if (keyCode == KeyEvent.KEYCODE_BACK) {

            if (mCurTime - mLastTime < 800) {
               this.finish();
                return true;
            } else if (mCurTime - mLastTime >= 800) {
                Toast.makeText(ConnectActivity.this, R.string.dbclick, Toast.LENGTH_SHORT).show();
                return true;
            }

        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        this.unregisterReceiver(receiver);
        super.onDestroy();
        this.finish();
    }
}
