package com.tsymiar.device2device.acceleration;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathEffect;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.tsymiar.device2device.R;

import java.util.ArrayList;

public class DefaultFragment extends Fragment implements SensorEventListener {

	private Handler mHandler;
	private NewChart mView;
	private final PointF[] mPoints = new PointF[200];
	Chart chart0;
	ArrayList<Integer> xlist = new ArrayList<Integer>();
	private SensorManager sensorManager;
	private static final int UPDATE_INTERVAL_TIME = 200;
	private long lastUpdateTime;
	private double[] linear_acceleration = new double[3];
	private double[] gravity = new double[3];

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View rootView = inflater.inflate(R.layout.dialog_view_chart, container, false);

		sensorManager = (SensorManager)requireActivity().getSystemService(Context.SENSOR_SERVICE);
		chart0 = new Chart(sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION));

		mView = new NewChart(requireActivity());
		LinearLayout mLayout = rootView.findViewById(R.id.toor);
		mLayout.addView(mView);

		mHandler = new Handler();
		mHandler.post(new TimerProcess());

		return rootView;
	}

	@Override
	public void onResume() {
		super.onResume();
		chart0.register();
		sensorManager.registerListener(this, chart0.sensor, SensorManager.SENSOR_DELAY_GAME);
	}

	@Override
	public void onPause() {
		super.onPause();
		chart0.unregister();
		sensorManager.unregisterListener(this);
		mHandler.removeCallbacksAndMessages(null);
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		if (mHandler != null) {
			mHandler.removeCallbacksAndMessages(null);
		}
	}

	private class TimerProcess implements Runnable {
		@Override
		public void run() {
			if (mView != null) {
				mView.invalidate();
				mHandler.postDelayed(this, 200);
			}
		}
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		long currentUpdateTime = System.currentTimeMillis();
		long timeInterval = currentUpdateTime - lastUpdateTime;
		if (timeInterval < UPDATE_INTERVAL_TIME)
			return;
		lastUpdateTime = currentUpdateTime;

		final float alpha = 0.8f;
		// 修正数组索引错误
		gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0];
		gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1];
		gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2];

		linear_acceleration[0] = event.values[0] - gravity[0];
		linear_acceleration[1] = event.values[1] - gravity[1];
		linear_acceleration[2] = event.values[2] - gravity[2];
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}

	class NewChart extends View {

		private int CHARTH = 900;// 画布高
		private int CHARTW = 700;// 画布宽
		private int OFFSET_LEFT = 170;// 左边缩进170
		private int OFFSET_TOP = 80;// 距离顶部80
		private int TEXT_OFFSET = 40;// 文字距离中心位置40

		private int X_INTERVAL = 20;
		private ArrayList<PointF> mPlist;

		PointF mPoint;
		Paint mPaint = new Paint();
		SensorEvent arg4;

		public NewChart(Context context) {
			super(context);
			mPlist = new ArrayList<PointF>();
		}

		@Override
		protected void onDraw(Canvas canvas) {

			super.onDraw(canvas);

			drawTable(canvas);
			drawCurve(canvas);
			prepareLine();
			initPlist();
		}

		private void drawTable(Canvas canvas) {

			mPaint.setColor(Color.WHITE);

			mPaint.setStyle(Paint.Style.STROKE);
			mPaint.setStrokeWidth(1);
			Rect chartRec = new Rect(OFFSET_LEFT, OFFSET_TOP, CHARTW + OFFSET_LEFT, CHARTH + OFFSET_TOP);
			canvas.drawRect(chartRec, mPaint);

			Path textPaint = new Path();
			mPaint.setStyle(Paint.Style.FILL);
			textPaint.moveTo(400, 40);
			textPaint.lineTo(600, 40);
			mPaint.setTextSize(27);
			mPaint.setAntiAlias(true);
			canvas.drawTextOnPath("线性加速度传感器", textPaint, 0, 0, mPaint);
			mPaint.setTextSize(20);
			for (int j = 5; j >= 1; j--) {
				canvas.drawText("+" + 0.5 * j, OFFSET_LEFT - TEXT_OFFSET, OFFSET_TOP + CHARTH / 10 * (5 - j), mPaint);
			}
			canvas.drawText("0", OFFSET_LEFT - TEXT_OFFSET, OFFSET_TOP + CHARTH / 2, mPaint);
			for (int i = 1; i <= 5; i++) {
				canvas.drawText("-" + 0.5 * i, OFFSET_LEFT - TEXT_OFFSET, OFFSET_TOP + CHARTH / 10 * (i + 5), mPaint);			}

			Path path = new Path();
			PathEffect effect = new DashPathEffect(new float[] { 2, 2, 2, 2 }, 1);
			mPaint.setStyle(Paint.Style.STROKE);
			mPaint.setAntiAlias(true);
			mPaint.setPathEffect(effect);
			for (int i = 1; i < 10; i++) {
				path.moveTo(OFFSET_LEFT, OFFSET_TOP + CHARTH / 10 * i);
				path.lineTo(OFFSET_LEFT + CHARTW, OFFSET_TOP + CHARTH / 10 * i);
				canvas.drawPath(path, mPaint);
			}
		}

		private PointF[] getPoints(ArrayList<PointF> dlk, ArrayList<Integer> xlist) {
			///////////////////////
			int widt = getWidth();
			final float scale = DefaultFragment.this.getResources().getDisplayMetrics().density;
			int widh = (int)(50 * scale + 0.5f);
			for (int i = 0; i < dlk.size(); i++) {
				xlist.add(widh + (widt - widh) / dlk.size() * i);
			}
			PointF[] mPoints = new PointF[dlk.size()];
			for (int i = 0; i < dlk.size(); i++) {
				int ph = (int)(gravity[0] * 100);

				mPoints[i] = new PointF(xlist.get(i), ph);

				// mView.invalidate();
			}
			return mPoints;
		}

		private void drawScrollLine(PointF[] ps, Canvas canvas, Paint paint) {
			PointF startp = new PointF();
			PointF endp = new PointF();
			for (int i = 0; i < ps.length - 1; i++) {
				startp = ps[i];
				endp = ps[i + 1];
				float wt = (startp.x + endp.x) / 2;
				PointF p3 = new PointF();
				PointF p4 = new PointF();
				p3.y = startp.y;
				p3.x = wt;
				p4.y = endp.y;
				p4.x = wt;
				Path path = new Path();
				path.moveTo(startp.x, startp.y);
				// 贝塞尔三次曲线
				path.cubicTo(p3.x, p3.y, p4.x, p4.y, endp.x, endp.y);
				// 贝塞尔二次 path.quadTo();
				canvas.drawPath(path, paint);
			}
		}

		private void drawCurve(Canvas canvas) {

			Paint paint = new Paint();
			paint.setColor(Color.YELLOW);
			paint.setStyle(Paint.Style.FILL);
			paint.setStrokeWidth(2);
			paint.setAntiAlias(true);

			if (mPlist.size() >= 2) {
				for (int i = 0; i < mPlist.size() - 1; i++) {
					drawScrollLine(getPoints(mPlist, xlist), canvas, paint);
					canvas.drawLine(mPlist.get(i).x - X_INTERVAL * 9, mPlist.get(i).y,
							mPlist.get(i + 1).x - X_INTERVAL * 9, mPlist.get(i + 1).y, paint);
				}
			}
		}

		public void prepareLine() {

			float y = (float)linear_acceleration[0] + 0.5f;

			float Py = OFFSET_TOP + (y * (CHARTH - OFFSET_TOP));

			PointF p = new PointF((float)OFFSET_LEFT + CHARTW, Py);
			if (mPlist.size() > 21) {
				mPlist.remove(0);
				for (int i = 0; i < 20; i++) {
					if (i == 0)
						mPlist.get(i).x -= (X_INTERVAL - 2);
					else
						mPlist.get(i).x -= X_INTERVAL;
				}
				mPlist.add(p);
			} else {
				for (int i = 0; i < mPlist.size(); i++) {
					mPlist.get(i).x -= X_INTERVAL;
				}
				mPlist.add(p);
			}
		}

		private void initPlist() {
		}

	}

	class Chart {
		Sensor sensor;

		public Chart(Sensor sensor) {
			this.sensor = sensor;
		}

		public void register() {
			sensorManager.registerListener(DefaultFragment.this, sensor, SensorManager.SENSOR_DELAY_GAME);
		}

		public void unregister() {
			sensorManager.unregisterListener(DefaultFragment.this);
		}
	}
}
