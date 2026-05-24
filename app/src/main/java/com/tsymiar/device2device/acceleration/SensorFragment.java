package com.tsymiar.device2device.acceleration;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.acceleration.Voice.Voice;
import com.tsymiar.device2device.service.ToastNotificationService;

import java.util.ArrayList;

public class SensorFragment extends Fragment implements SensorEventListener {

    private Handler mHandler;
    private NewChart mView;
    private float upX;
    private float upY;
    private float downX;
    private float downY;
    Chart chart0;
    // 传感器管理器
    private SensorManager sensorManager;
    // 两次检测的时间间隔
    private static final int UPDATE_INTERVAL_TIME = 200;
    private long lastUpdateTime;
    public double[] linear_acceleration = new double[3];
    public double[] gravity = new double[3];

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
        mHandler.post(new SensorFragment.TimerProcess());

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

    @RequiresApi(api = Build.VERSION_CODES.CUPCAKE)
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

    private class TimerProcess implements Runnable {

        @Override
        public void run() {
            mHandler.postDelayed(this, 100);
            mView.invalidate();
        }
    }

    class NewChart extends View {

        private int chartH, chartW;
        private int offsetLeft, offsetTop;
        private int textOffset;
        private float mXStep;
        private static final int MAX_POINTS = 30;

        private ArrayList<PointF> mPlist;
        private ArrayList<PointF> mPlist0;
        private ArrayList<Float> mRawY;
        private ArrayList<Float> mRawY0;

        // Y轴自适应缩放
        private float mYRange = 2.5f;
        private float mTargetYRange = 2.5f;
        private static final float Y_RANGE_MIN = 2.5f;
        private static final float Y_LERP_SPEED = 0.12f;

        // 复用Paint对象，避免每帧new
        private Paint mLinePaint;
        private Paint mLinePaint0;
        private Paint mGridPaint;
        private Paint mTextPaint;
        private Paint mGrayPaint;
        private Paint mBgPaint;
        private Paint mBorderPaint;
        private Paint mLegendPaint;
        private Paint mLegendAccent;

        // 复用Path对象
        private Path mGridPath;
        private Rect mChartRect;

        // 曲线Path，用于贝塞尔平滑绘制
        private Path mCurvePath;
        private Path mCurvePath0;

        double M;

        public NewChart(Context context) {
            super(context);
            mPlist = new ArrayList<PointF>();
            mPlist0 = new ArrayList<PointF>();
            mRawY = new ArrayList<Float>();
            mRawY0 = new ArrayList<Float>();
            initPaints();
        }

        private void initPaints() {
            // 曲线1：黄色线性加速度
            mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mLinePaint.setColor(Color.YELLOW);
            mLinePaint.setStyle(Paint.Style.STROKE);
            mLinePaint.setStrokeWidth(3f);
            mLinePaint.setStrokeCap(Paint.Cap.ROUND);
            mLinePaint.setStrokeJoin(Paint.Join.ROUND);

            // 曲线2：品红重力基准
            mLinePaint0 = new Paint(Paint.ANTI_ALIAS_FLAG);
            mLinePaint0.setColor(Color.MAGENTA);
            mLinePaint0.setStyle(Paint.Style.STROKE);
            mLinePaint0.setStrokeWidth(3f);
            mLinePaint0.setStrokeCap(Paint.Cap.ROUND);
            mLinePaint0.setStrokeJoin(Paint.Join.ROUND);

            // 网格虚线
            mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mGridPaint.setColor(Color.DKGRAY);
            mGridPaint.setStyle(Paint.Style.STROKE);
            mGridPaint.setStrokeWidth(1.5f);
            mGridPaint.setPathEffect(new DashPathEffect(new float[]{6, 4}, 0));

            // 文字
            mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mTextPaint.setColor(Color.WHITE);
            mTextPaint.setStyle(Paint.Style.FILL);
            mTextPaint.setTextSize(22f);

            // 图例
            mLegendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mLegendPaint.setColor(Color.CYAN);
            mLegendPaint.setStyle(Paint.Style.FILL);
            mLegendPaint.setTextSize(18f);

            mLegendAccent = new Paint(Paint.ANTI_ALIAS_FLAG);
            mLegendAccent.setColor(Color.GREEN);
            mLegendAccent.setStyle(Paint.Style.FILL);
            mLegendAccent.setTextSize(22f);

            mGrayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mGrayPaint.setColor(Color.LTGRAY);
            mGrayPaint.setTextSize(16f);

            mBgPaint = new Paint();
            mBgPaint.setColor(Color.argb(40, 255, 255, 255));
            mBgPaint.setStyle(Paint.Style.FILL);

            mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBorderPaint.setColor(Color.WHITE);
            mBorderPaint.setStyle(Paint.Style.STROKE);
            mBorderPaint.setStrokeWidth(1.5f);

            mGridPath = new Path();
            mCurvePath = new Path();
            mCurvePath0 = new Path();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            // 根据实际View尺寸自适应
            float density = getResources().getDisplayMetrics().density;
            offsetLeft = (int)(60 * density);
            offsetTop = (int)(60 * density);
            textOffset = (int)(24 * density);
            chartW = w - offsetLeft - (int)(16 * density);
            chartH = h - offsetTop - (int)(80 * density);
            mXStep = chartW / (float)(MAX_POINTS - 1);
            mChartRect = new Rect(offsetLeft, offsetTop, offsetLeft + chartW, offsetTop + chartH);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // 先更新数据，再渲染
            updateLineData();
            updateLine0Data();
            updateYRange();

            drawTable(canvas);
            drawSmoothCurve(canvas, mPlist, mLinePaint);
            drawSmoothCurve(canvas, mPlist0, mLinePaint0);
            drawLegend(canvas);
        }

        private void updateLineData() {
            float rawY = (float) linear_acceleration[0] + 0.55f;
            float py = offsetTop + chartH / 2f - rawY * chartH / (2f * mYRange);
            PointF p = new PointF(offsetLeft + chartW, py);

            mPlist.add(p);
            mRawY.add(rawY);
            // 左移旧数据点，步长由图表宽度动态计算，确保曲线填满整个坐标区
            for (int i = 0; i < mPlist.size() - 1; i++) {
                mPlist.get(i).x -= mXStep;
            }
            // 移除溢出左边缘的数据点
            while (mPlist.size() > 1 && mPlist.get(0).x < offsetLeft) {
                mPlist.remove(0);
                mRawY.remove(0);
            }

            M = linear_acceleration[0];
            if (M >= 7.0f) {
                Intent intent = new Intent(getActivity(), Voice.class);
                requireActivity().startService(intent);

                Intent toastIntent = new Intent(ToastNotificationService.TOAST_ACTION);
                toastIntent.putExtra(ToastNotificationService.EXTRA_MESSAGE,
                        "加速度过大: " + String.format("%.1f", M) + " m/s²");
                requireActivity().sendBroadcast(toastIntent);
            }
        }

        private void updateLine0Data() {
            float rawY0 = (float) (9.8f - gravity[2]) / 20;
            float py0 = offsetTop + chartH / 2f - rawY0 * chartH / (2f * mYRange);
            PointF p0 = new PointF(offsetLeft + chartW, py0);

            mPlist0.add(p0);
            mRawY0.add(rawY0);
            for (int i = 0; i < mPlist0.size() - 1; i++) {
                mPlist0.get(i).x -= mXStep;
            }
            while (mPlist0.size() > 1 && mPlist0.get(0).x < offsetLeft) {
                mPlist0.remove(0);
                mRawY0.remove(0);
            }
        }

        // Y轴自适应缩放：追踪所有采样点纵坐标，超出范围时自动扩展刻度
        private void updateYRange() {
            float maxAbs = 0;
            for (Float v : mRawY) {
                maxAbs = Math.max(maxAbs, Math.abs(v));
            }
            for (Float v : mRawY0) {
                maxAbs = Math.max(maxAbs, Math.abs(v));
            }
            // 超出当前范围85%时扩展，数据回落时收缩至默认值
            if (maxAbs > mYRange * 0.85f) {
                mTargetYRange = Math.max(maxAbs * 1.2f, Y_RANGE_MIN);
            } else {
                mTargetYRange = Y_RANGE_MIN;
            }
            mYRange += (mTargetYRange - mYRange) * Y_LERP_SPEED;

            // 用新的刻度重新投影所有采样点
            float scale = chartH / (2f * mYRange);
            float midY = offsetTop + chartH / 2f;
            for (int i = 0; i < mPlist.size(); i++) {
                mPlist.get(i).y = midY - mRawY.get(i) * scale;
            }
            for (int i = 0; i < mPlist0.size(); i++) {
                mPlist0.get(i).y = midY - mRawY0.get(i) * scale;
            }
        }

        // 贝塞尔平滑曲线
        private void drawSmoothCurve(Canvas canvas, ArrayList<PointF> points, Paint paint) {
            if (points.size() < 2) return;

            mCurvePath.rewind();
            mCurvePath.moveTo(points.get(0).x, points.get(0).y);

            if (points.size() == 2) {
                mCurvePath.lineTo(points.get(1).x, points.get(1).y);
            } else {
                for (int i = 0; i < points.size() - 1; i++) {
                    float x1 = points.get(i).x;
                    float y1 = points.get(i).y;
                    float x2 = points.get(i + 1).x;
                    float y2 = points.get(i + 1).y;

                    // 使用控制点平滑过渡
                    float cx = (x1 + x2) / 2f;
                    mCurvePath.quadTo(x1, y1, cx, (y1 + y2) / 2f);
                }
                mCurvePath.lineTo(points.get(points.size() - 1).x,
                        points.get(points.size() - 1).y);
            }
            canvas.drawPath(mCurvePath, paint);
        }

        private void drawTable(Canvas canvas) {
            if (mChartRect == null) return;

            // 图表背景
            canvas.drawRect(mChartRect, mBgPaint);

            // 外框
            canvas.drawRect(mChartRect, mBorderPaint);

            // 标题
            mTextPaint.setTextSize(22f);
            mTextPaint.setColor(Color.WHITE);
            canvas.drawText("传感器实时曲线", offsetLeft + chartW / 2f - 80, offsetTop - 16, mTextPaint);

            // Y轴刻度（自适应缩放）
            mTextPaint.setTextSize(18f);
            float yRange = mYRange;
            float yStep = yRange / 5f;

            for (int j = 5; j >= -5; j--) {
                float val = j * yStep;
                int y = (int)(offsetTop + chartH / 2f - val / yRange * chartH / 2f);
                String label = (val == 0) ? "0" : String.format("%+.1f", val);
                canvas.drawText(label, offsetLeft - textOffset - 20, y + 5, mTextPaint);
            }

            // 单位标识
            canvas.drawText(String.format("±%.1f", yRange), offsetLeft + chartW / 2f - 30,
                    offsetTop + chartH / 2f - 50, mGrayPaint);
            canvas.drawText("m/s²", offsetLeft - textOffset - 28, offsetTop + chartH / 2f + 30, mTextPaint);

            // 水平网格虚线
            mGridPath.rewind();
            for (int i = 1; i < 10; i++) {
                float y = offsetTop + chartH / 10f * i;
                mGridPath.moveTo(offsetLeft, y);
                mGridPath.lineTo(offsetLeft + chartW, y);
            }
            canvas.drawPath(mGridPath, mGridPaint);
        }

        private void drawLegend(Canvas canvas) {
            float ly = offsetTop + chartH + 32;
            // 标题
            mLegendAccent.setColor(Color.GREEN);
            canvas.drawText("图例：", offsetLeft, ly, mLegendAccent);

            // 线性加速度
            float lx1 = offsetLeft + 70;
            float lx2 = lx1 + 50;
            mLinePaint.setColor(Color.YELLOW);
            canvas.drawLine(lx1, ly - 8, lx2, ly - 8, mLinePaint);
            mLegendPaint.setColor(Color.CYAN);
            canvas.drawText("Leaner-acceleration", lx2 + 10, ly - 2, mLegendPaint);

            // 重力基准线
            ly += 28;
            mLinePaint0.setColor(Color.MAGENTA);
            canvas.drawLine(lx1, ly - 8, lx2, ly - 8, mLinePaint0);
            mLegendPaint.setColor(Color.CYAN);
            canvas.drawText("重力感应基准线", lx2 + 10, ly - 2, mLegendPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    upX = event.getX();
                    upY = event.getY();
                    invalidate();
                    break;
            }
            return true;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.CUPCAKE)
    class Chart implements SensorEventListener {

        Sensor sensor;

        public Chart(Sensor sensor) {

            this.sensor = sensor;
        }

        public void register() {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        }

        public void unregister() {
            sensorManager.unregisterListener(this);
        }

        @Override
        public void onAccuracyChanged(Sensor arg0, int arg1) {
            // TODO Auto-generated method stub
        }

        @Override
        public void onSensorChanged(SensorEvent arg) {
            // TODO Auto-generated method stub
            // 检测时间
            long currentUpdateTime = System.currentTimeMillis();
            // 两次检测的时间间隔
            long timeInterval = currentUpdateTime - lastUpdateTime;
            // 判断是否达到了检测时间间隔
            if (timeInterval < UPDATE_INTERVAL_TIME)
                return;
            // 当前时间变成last时间
            lastUpdateTime = currentUpdateTime;

            final float alpha = 0.8f;

            // Use values[0/1/2] instead of deprecated DATA_X/Y/Z
            gravity[0] = alpha * gravity[0] + (1 - alpha) * arg.values[0];
            gravity[1] = alpha * gravity[1] + (1 - alpha) * arg.values[1];
            gravity[2] = alpha * gravity[2] + (1 - alpha) * arg.values[2];

            linear_acceleration[0] = arg.values[0] - gravity[0];
            linear_acceleration[1] = arg.values[1] - gravity[1];
            linear_acceleration[2] = arg.values[2] - gravity[2];

            //Toast.makeText(SensorChart.this,""+arg.values[0],Toast.LENGTH_SHORT).show();
        }
    }
}
