package com.tsyqi909006258.yongshuo;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.support.v4.app.Fragment;
import android.support.v4.widget.SwipeRefreshLayout;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.TsyQi.MyClasses.ChartView;
import com.TsyQi.MyClasses.MySensor;
import com.common.logger.Log;

@SuppressWarnings("ALL")
public class ChartFragment extends Fragment {

	public ChartFragment() {
	}


	private boolean flag=true;
	private boolean okay=false;

	private Canvas canvas=new Canvas();
	private Intent intent = new Intent();
	private Matrix matrix = new Matrix();

	private ChartView mView;
	private Handler mHandler;
	private ImageView img;
	private MySensor chart0;
	private Bitmap mBitmap;
	private String temp=null;
	private TextView myview,click;
	private Button stop_bn,exit_bn;
	private BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

	@SuppressLint("InflateParams")
	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	@Override
	public void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);

		SensorManager sensorManager = (SensorManager) getActivity().getSystemService(Context.SENSOR_SERVICE);
        chart0 = new MySensor(sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION));

	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
							 Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_chart, container, false);
	}
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        exit_bn= (Button)getActivity().findViewById(R.id.out_bn);
        stop_bn=(Button)getActivity().findViewById(R.id.stop_bn);
        img=(ImageView)getActivity().findViewById(R.id.imageView);

        mView=new ChartView(getActivity());
        stop_bn.setOnClickListener(new StopButtonListener());
        exit_bn.setOnClickListener(new ExitButtonListener());

        mView.onDraw(canvas);
    }
	public Bitmap getmBitmap() {
		return mBitmap;
	}

	public void setmBitmap(Bitmap mBitmap) {
		this.mBitmap = mBitmap;
	}

	public Bitmap CreatNewPhoto() {
		int width = 600;
		int height = 600;
		Paint mPaint=new Paint();
		Bitmap bitmap = Bitmap.createBitmap(width, height,
				Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		canvas.drawBitmap(mBitmap, matrix, mPaint);
		canvas.save(Canvas.ALL_SAVE_FLAG);
		canvas.restore();
		return bitmap;
	}

	private class TimerProcess implements Runnable{
		@Override
		public void run() {
			mHandler.postDelayed(this,50);
			mView.invalidate();
		}
	}
	private class ExitButtonListener implements OnClickListener{

		@Override
		public void onClick(View v) {
			okay=false;
		}
	}
	private class StopButtonListener implements OnClickListener{

		@Override
		public void onClick(View v) {
			// TODO Auto-generated method stub
			if(flag){
				flag= false;
				mView.invalidate();
				stop_bn.setText(R.string.begin);
			}
			else
			{
				flag=true;
				stop_bn.setText(R.string.stop);
			}
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		final Vibrator vibrator = (Vibrator)getActivity().getSystemService(Service.VIBRATOR_SERVICE);
		//chart0.register();
		Bundle bundle = intent.getExtras();
		if(bundle!=null) {
			temp = bundle.getString("temp");
			new Thread() {
				public void run() {
					Message msg = new Message();
					msg.what = 0;
					handler.sendMessage(msg);
					try {
						sleep(50);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}.start();
		}
		img.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				vibrator.vibrate(50);
				Toast toast = Toast.makeText(getActivity(), getString(R.string.min), Toast.LENGTH_SHORT);
				toast.setGravity(Gravity.BOTTOM, 0, 30);
				toast.show();
				Intent t = new Intent(getActivity(), MainActivity.class);
				Bundle e = new Bundle();
				e.putString("t", "");
				t.putExtras(e);
				getActivity().sendBroadcast(t);
				getActivity().startService(t);
			}
		});
	}

	Handler handler;

	{
		handler = new Handler() {
			@Override
			public void handleMessage(Message msg) {

				if (msg.what == 0)
					myview.setText(temp + "");
				else {
					Intent t = new Intent(getActivity(), MainActivity.class);
					Bundle e = new Bundle();
					e.putString("t", temp + "");
					t.putExtras(e);
					getActivity().sendBroadcast(t);
					getActivity().startService(t);
				}
				super.handleMessage(msg);
			}
		};
	}
	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			getActivity().finish();
		}
		return super.onOptionsItemSelected(item);
	}
}
