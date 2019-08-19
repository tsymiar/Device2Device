package com.tsymiar.SerialConn.Sensor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import com.tsymiar.SerialConn.R;
import com.tsymiar.SerialConn.Voice.Voice;

import java.util.ArrayList;

public class SensorActivity extends Activity {

    private Handler mHandler;
    private NewChart mView;
    private float upX;
    private float upY;
    private float downX;
    private float downY;
    private MotionEvent p;
    private String s = "A";
    // private PointF[] mPoints = new PointF[200]; // 声明一个对象，泛型是PointF，并实例化这个泛型对象
    Chart chart0;
    ArrayList<Integer> xlist = new ArrayList<Integer>();
    // 传感器管理器
    private SensorManager sensorManager;
    // 两次检测的时间间隔
    private static final int UPTATE_INTERVAL_TIME = 200;
    private long lastUpdateTime;
    public double[] linear_acceleration = new double[3];
    public double[] gravity = new double[3];

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        chart0 = new Chart(sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION));

        setContentView(R.layout.chart_view);
        mView = new NewChart(this);
        mView.invalidate();
        LinearLayout mLayout = (LinearLayout) findViewById(R.id.toor);
        mLayout.addView(mView);
        mHandler = new Handler();
        mHandler.post(new TimerProcess());
    }

    @Override
    protected void onResume() {
        super.onResume();
        chart0.register();
    }

    @Override
    protected void onPause() {
        super.onPause();
        chart0.unregister();
    }

    private class TimerProcess implements Runnable {

        @Override
        public void run() {
            mHandler.postDelayed(this, 300);
            mView.invalidate();
        }
    }

    class NewChart extends View {

        private int CHARTH = 800;// 画布高
        private int CHARTW = 600;// 画布宽
        private int OFFSET_LEFT = 70;// 距离左边70
        private int OFFSET_TOP = 80;// 距离顶部80
        private int TEXT_OFFSET = 30;// 文字距离中心位置30
        private int X_INTERVAL = 30;

        private ArrayList<PointF> mPlist;
        private ArrayList<PointF> mPlist0;

        PointF mPoint;
        Paint mPaint = new Paint();
        SensorEvent arg4;
        double M;

        public NewChart(Context context) {
            super(context);
            // TODO Auto-generated constructor stub
            mPlist = new ArrayList<PointF>();
            mPlist0 = new ArrayList<PointF>();
        }

        @Override
        protected void onDraw(Canvas canvas) {

            super.onDraw(canvas);

            drawTable(canvas);
            drawCurve(canvas);
            Line();
            drawCurve0(canvas);
            Line0();
            legend(canvas);
            initPlist();
        }

        private void drawTable(Canvas canvas) {
            // 画出外框
            mPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            mPaint.setStrokeWidth(2);
            // 画出一个矩形图
            mPaint.setColor(Color.TRANSPARENT);
            Rect chartRec = new Rect(OFFSET_LEFT, OFFSET_TOP, CHARTW + OFFSET_LEFT, CHARTH + OFFSET_TOP);
            canvas.drawRect(chartRec, mPaint);
            mPaint.setColor(Color.WHITE);// 设置要画出图形的颜色
            // 顶端的文字
            Path textPaint = new Path();
            mPaint.setStyle(Paint.Style.FILL);// 设置画笔的样式
            textPaint.moveTo(250, 44);// 文字排版起始点
            textPaint.lineTo(500, 44);// 结束点
            mPaint.setTextSize(25);// 字号
            mPaint.setAntiAlias(true);// 设置锯齿效果，true消除。
            canvas.drawTextOnPath("传感器实时曲线", textPaint, 0, 0, mPaint);
            // 左侧数字
            for (int j = 5; j >= 1; j--) {
                canvas.drawText("+" + 0.5 * j, OFFSET_LEFT - TEXT_OFFSET - 23, OFFSET_TOP + CHARTH / 10 * (5 - j), mPaint);
            }
            Paint mpt = new Paint();
            mpt.setColor(Color.LTGRAY);
            mpt.setStrokeWidth(5);
            canvas.drawText("9.8(m/s²)", CHARTW / 2, OFFSET_TOP + CHARTH / 2 - 60, mpt);
            canvas.drawText("0", OFFSET_LEFT - TEXT_OFFSET - 20, OFFSET_TOP + CHARTH / 2 - 5, mPaint);
            canvas.drawText("m/s²", OFFSET_LEFT - TEXT_OFFSET - 30, OFFSET_TOP + CHARTH / 2 + 30, mPaint);
            for (int i = 1; i <= 5; i++) {
                canvas.drawText("-" + 0.5 * i, OFFSET_LEFT - TEXT_OFFSET - 20, OFFSET_TOP + CHARTH / 10 * (i + 5), mPaint);
            }
            // 画表格中的虚线
            Path mPath = new Path();
            PathEffect effect = new DashPathEffect(new float[]{2, 2, 2, 2}, 1);
            mpt.setColor(Color.DKGRAY);
            mPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            mPaint.setAntiAlias(true);
            mPaint.setStrokeWidth(2);
            mPaint.setPathEffect(effect);
            for (int i = 1; i <= 9; i++) {
                mPath.moveTo(OFFSET_LEFT, OFFSET_TOP + CHARTH / 10 * i);
                mPath.lineTo(OFFSET_LEFT + CHARTW, OFFSET_TOP + CHARTH / 10 * i);
                canvas.drawPath(mPath, mPaint);
            }
        }

        private PointF[] getpoints(ArrayList<PointF> dlk, ArrayList<Integer> xlist) {
            int widt = getWidth();
            final float scale = SensorActivity.this.getResources().getDisplayMetrics().density;
            int widh = (int) (50 * scale + 0.5f);
            for (int i = 0; i < dlk.size(); i++) {
                xlist.add(widh + (widt - widh) / dlk.size() * i);
            }
            PointF[] mPoints = new PointF[dlk.size()];
            for (int i = 0; i < dlk.size(); i++) {
                int ph = (int) (gravity[0] * 100);

                mPoints[i] = new PointF(xlist.get(i), ph);

                // invalidate();
            }
            return mPoints;
        }

        private void drawscrollline(PointF[] ps, Canvas canvas, Paint paint) {
            PointF startp = new PointF();
            PointF endp = new PointF();
            paint.setStyle(Paint.Style.FILL);
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

            mPaint.setColor(Color.YELLOW);
            mPaint.setStrokeWidth(3);
            mPaint.setAntiAlias(true);

            if (mPlist.size() >= 2) {
                for (int i = 0; i < mPlist.size() - 1; i++) {
                    // drawscrollline(getpoints(mPlist,xlist), canvas , mPaint);
                    canvas.drawLine(mPlist.get(i).x, mPlist.get(i).y, mPlist.get(i + 1).x, mPlist.get(i + 1).y, mPaint);
                }
            }
        }

        private void drawCurve0(Canvas canvas) {

            mPaint.setColor(Color.MAGENTA);
            mPaint.setStrokeWidth(3);
            mPaint.setAntiAlias(true);

            if (mPlist0.size() >= 2) {
                for (int i = 0; i < mPlist0.size() - 1; i++) {
                    canvas.drawLine(mPlist0.get(i).x, mPlist0.get(i).y, mPlist0.get(i + 1).x, mPlist0.get(i + 1).y, mPaint);
                }
            }
        }

        // 拖动事件
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            Path mPath = new Path();
            float currentX;
            float currentY;
            // 当前组件的currentX、currentY两个属性
            currentX = event.getX();
            currentY = event.getY();
            // 返回true表明处理方法已经处理该事件
            switch (event.getAction()) {
                case MotionEvent.ACTION_SCROLL:
                    upX = event.getX();
                    upY = event.getY();
                    p = null;
                    break;
                case MotionEvent.ACTION_OUTSIDE:
                    downX = event.getX();
                    downY = event.getY();
                    // canvas.drawText("", downX, downY, paint);
                    mPath.moveTo(downX, downY);
                    break;
                case MotionEvent.ACTION_MOVE:
                    upX = event.getX();
                    upY = event.getY();
                    mPath.quadTo(downX, downY, upX, upY);
                    // m_canvas.drawPath(mPath, paint);
                    downX = upX;
                    downY = upY;
                    /*
                     * 拖动时坐标相对运动 (x0,y0)保存先前一次事件发生的坐标
                     * 只要计算两个坐标的delta值，然后加到点上，即移动了点。
                     * 然后调用invalidate()让系统调用onDraw()刷新以下屏幕。
                     */
                    invalidate();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    break;
            }
            // 通知改组件重绘
            this.invalidate();
            return true;
        }

        // 图例
        private void legend(Canvas canvas) {

            mPaint.setColor(Color.GREEN);
            Path legPaint = new Path();
            mPaint.setStyle(Paint.Style.FILL);
            legPaint.moveTo(90, CHARTH + OFFSET_TOP * 2 - 20);
            legPaint.lineTo(200, CHARTH + OFFSET_TOP * 2 - 20);
            mPaint.setTextSize(25);
            canvas.drawTextOnPath("图例：", legPaint, 0, 0, mPaint);
            // 图线
            mPaint.setColor(Color.YELLOW);
            canvas.drawLine(80, CHARTH + OFFSET_TOP * 3 - 47, 200, CHARTH + OFFSET_TOP * 3 - 47, mPaint);
            // 文字
            mPaint.setColor(Color.CYAN);
            Path accPath = new Path();
            mPaint.setStyle(Paint.Style.FILL);
            accPath.moveTo(220, CHARTH + OFFSET_TOP * 3 - 50);
            accPath.lineTo(400, CHARTH + OFFSET_TOP * 3 - 50);
            mPaint.setTextSize(20);
            canvas.drawTextOnPath("Leaner-acceleration", accPath, 0, 0, mPaint);
            /////
            mPaint.setColor(Color.MAGENTA);
            canvas.drawLine(80, CHARTH + OFFSET_TOP * 3 + 20, 200, CHARTH + OFFSET_TOP * 3 + 20, mPaint);
            mPaint.setColor(Color.CYAN);
            Path graPath = new Path();
            mPaint.setStyle(Paint.Style.FILL);
            graPath.moveTo(220, CHARTH + OFFSET_TOP * 3 + 25);
            graPath.lineTo(400, CHARTH + OFFSET_TOP * 3 + 25);
            mPaint.setTextSize(20);
            canvas.drawTextOnPath("重力感应基准线", graPath, 0, 0, mPaint);
            mPaint.setColor(Color.BLACK);
        }

        public void Line() {

            float y = (float) linear_acceleration[0] + 0.55f;

            float Py = OFFSET_TOP + (y * (CHARTH - OFFSET_TOP));

            PointF p = new PointF((float) OFFSET_LEFT + CHARTW, Py);
            if (mPlist.size() > 21) {
                mPlist.remove(0);
                for (int i = 0; i < 20; i++) {
                    if (i == 0) mPlist.get(i).x -= (X_INTERVAL - 2);
                    else mPlist.get(i).x -= X_INTERVAL;
                }
                mPlist.add(p);
            } else {
                for (int i = 0; i < mPlist.size(); i++) {
                    mPlist.get(i).x -= X_INTERVAL;
                }
                mPlist.add(p);
            }

            M = linear_acceleration[0];
            if (M >= 7.0f) {
                Intent intent = new Intent(SensorActivity.this, Voice.class);
                startService(intent);
                Intent i = new Intent(SensorActivity.this, SensorIntentService.class);
                i.putExtra("s", s);
                i.setAction("broadcast");
                sendBroadcast(i);
            }
        }

        private void initPlist() {
        }

        public void Line0() {

            float y0 = (float) (9.8f - gravity[2]) / 20;
            float Py0 = OFFSET_TOP + (y0 * (CHARTH - OFFSET_TOP));

            PointF p0 = new PointF((float) OFFSET_LEFT + CHARTW, Py0);
            if (mPlist0.size() > 21) {
                mPlist0.remove(0);
                for (int i = 0; i < 20; i++) {
                    if (i == 0) mPlist0.get(i).x -= (X_INTERVAL - 2);
                    else mPlist0.get(i).x -= X_INTERVAL;
                }
                mPlist0.add(p0);
            } else {
                for (int i = 0; i < mPlist0.size(); i++) {
                    mPlist0.get(i).x -= X_INTERVAL;
                }
                mPlist0.add(p0);
            }
        }
    }

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
            if (timeInterval < UPTATE_INTERVAL_TIME)
                return;
            // 当前时间变成last时间
            lastUpdateTime = currentUpdateTime;

            final float alpha = 0.8f;

            gravity[0] = alpha * gravity[SensorManager.DATA_X] + (1 - alpha) * arg.values[SensorManager.DATA_X];
            gravity[1] = alpha * gravity[SensorManager.DATA_Y] + (1 - alpha) * arg.values[SensorManager.DATA_Y];
            gravity[2] = alpha * gravity[SensorManager.DATA_Z] + (1 - alpha) * arg.values[SensorManager.DATA_Z];

            linear_acceleration[0] = arg.values[SensorManager.DATA_X] - gravity[SensorManager.DATA_X];
            linear_acceleration[1] = arg.values[SensorManager.DATA_Y] - gravity[SensorManager.DATA_Y];
            linear_acceleration[2] = arg.values[SensorManager.DATA_Z] - gravity[SensorManager.DATA_Z];

            //Toast.makeText(SensorChart.this,""+arg.values[0],Toast.LENGTH_SHORT).show();
        }
    }
}
