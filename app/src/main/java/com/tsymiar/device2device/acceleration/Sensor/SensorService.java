package com.tsymiar.device2device.acceleration.Sensor;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathEffect;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Vibrator;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.activity.GraphActivity;

import java.util.ArrayList;

@SuppressWarnings("ALL")
public class SensorService extends Service {

    public SensorService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    WindowManager wm = null;
    WindowManager.LayoutParams wmParams = null;
    Intent notificationIntent = null;
    View view;
    boolean okay = false;
    LinearLayout mLayout;

    private String temp = null;
    private float mTouchStartX;
    private float mTouchStartY;
    private TextView myview = null, click;
    private ImageView img;
    private float x;
    private float y;
    private PointF mid = new PointF();
    private Button pause_bn = null;
    private long lastUpdateTime;
    private boolean flag = true;
    private boolean fg = false;
    private boolean f = true;
    private int mode = 0;
    private float distance0;
    private float n = 1, p = 1;
    private float mx = 0;
    private float my = 0;
    private float x2, tX;
    private float y2, tY;
    private float disx;
    private float disy;
    private Handler mHandler;
    private NewChart mView;
    private Chart chart0;
    private Bitmap mBitmap;
    private Matrix matrix = new Matrix();
    private SensorManager sensorManager;
    private SurfaceHolder holder = null;
    public double[] linear_acceleration = new double[3];
    public double[] gravity = new double[3];
    // 声明一个对象，泛型是PointF，并实例化这个泛型对象
    // private PointF[] mPoints = new PointF[200];
    public static final String TAG = "Canvas";
    // 检测的时间间隔
    private static final int UPTATE_INTERVAL_TIME = 30;
    private BluetoothAdapter mBluetoothAdapter = null;

    @SuppressLint("InflateParams")
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @Override
    public void onCreate() {
        super.onCreate();
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification notification = new Notification(R.drawable.you, getText(R.string.app_name), System.currentTimeMillis());
        Notification.Builder builder = new Notification.Builder(SensorService.this);
        if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
            notificationIntent = new Intent(this, GraphActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
            builder.setContentIntent(pendingIntent);
        }
        builder.setAutoCancel(false);
        builder.setSmallIcon(R.drawable.ic_notify);
        builder.setContentTitle(R.string.app_name + " Notification");
        builder.setContentText("You have a new message");
        builder.setTicker("this is ticker text");
        builder.setSubText(getText(R.string.notify));
        builder.setNumber(100);
        builder.build();
        //API 8
        //notification.setLatestEventInfo(this, getText(R.string.AppName),
        //								getText(R.string.notif), pendingIntent);
        startForeground(1, notification);
        manager.notify(11, notification);
        view = LayoutInflater.from(this).inflate(R.layout.dialog_view_text, null);
        myview = (TextView) view.findViewById(R.id.notification);
        click = (TextView) view.findViewById(R.id.click);
        pause_bn = (Button) view.findViewById(R.id.pause_bn);
        Button exit_bn = (Button) view.findViewById(R.id.out_bn);
        img = (ImageView) view.findViewById(R.id.imageView);
        pause_bn.setOnClickListener(new StopButtonListener());
        exit_bn.setOnClickListener(new ExitButtonListener());
        // 初始化SurfaceHolder对象
        SurfaceView surface = (SurfaceView) view.findViewById(R.id.show);
        holder = surface.getHolder();
        holder.setFixedSize(9, 9);  //设小要比实际的绘图位置大一点
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        chart0 = new Chart(sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION));
        // ASurFace asurface=new ASurFace(this);
        matrix.set(surface.getMatrix());
        mView = new NewChart(this);
        mView.invalidate();
        LinearLayout mLayout = (LinearLayout) view.findViewById(R.id.toor);
        mLayout.addView(mView);
        mHandler = new Handler();
        mHandler.post(new TimerProcess());
        fg = true;
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
        Paint mPaint = new Paint();
        Bitmap bitmap = Bitmap.createBitmap(width, height,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(mBitmap, matrix, mPaint);
        // Canvas.ALL_SAVE_FLAG
        canvas.save();
        canvas.restore();
        return bitmap;
    }

    private class TimerProcess implements Runnable {
        @Override
        public void run() {
            mHandler.postDelayed(this, 50);
            mView.invalidate();
        }
    }

    private class ExitButtonListener implements OnClickListener {

        @Override
        public void onClick(View v) {
            wm.removeView(view);
            okay = false;
        }
    }

    private class StopButtonListener implements OnClickListener {

        @Override
        public void onClick(View v) {
            if (flag) {
                flag = false;
                pause_bn.setText(R.string.begin);
            } else {
                flag = true;
                pause_bn.setText(R.string.pause);
            }
        }
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        final Vibrator vibrator = (Vibrator) this.getSystemService(VIBRATOR_SERVICE);
        if (f)
            setView();
        chart0.register();
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
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
                wm.removeView(view);
                vibrator.vibrate(50);
                f = false;
                Toast toast = Toast.makeText(SensorService.this, getString(R.string.min), Toast.LENGTH_SHORT);
                toast.setGravity(Gravity.BOTTOM, 0, 30);
                toast.show();
            }
        });
    }

    Handler handler;

    {
        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {

                /* ////** */
                if (msg.what == 0) if (f)
                    myview.setText(temp + "");
                else {
                    Toast toast = Toast.makeText(SensorService.this, temp + "", Toast.LENGTH_SHORT);
                }
                super.handleMessage(msg);
            }
        };
    }

    private void setView() {
        wm = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);

        wmParams = new WindowManager.LayoutParams();
        wmParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
        wmParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        wmParams.gravity = Gravity.LEFT | Gravity.TOP;

        wmParams.x = 0;
        wmParams.y = 0;

        wmParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        wmParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        wmParams.format = PixelFormat.RGBA_8888;

        if (okay) {
            wm.updateViewLayout(view, wmParams);
        } else {
            wm.addView(view, wmParams);
            okay = true;
        }
        click.setOnTouchListener(new View.OnTouchListener() {

            public boolean onTouch(View v, MotionEvent event) {

                x = event.getRawX();
                y = event.getRawY();

                switch (event.getAction()) {

                    case MotionEvent.ACTION_DOWN:
                        mTouchStartX = event.getX() + 300;
                        mTouchStartY = event.getY() + view.getHeight() / 2;
                        break;

                    case MotionEvent.ACTION_MOVE:
                        updateViewPosition();
                        click.setText("PULL-ing");
                        break;
                    case MotionEvent.ACTION_UP:
                        updateViewPosition();
                        click.setText(R.string.pull);
                        mTouchStartX = mTouchStartY = 0;
                        break;
                }

                return true;
            }
        });
    }

    private void updateViewPosition() {

        wmParams.x = (int) (x - mTouchStartX);
        wmParams.y = (int) (y - mTouchStartY);
        wm.updateViewLayout(view, wmParams);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        chart0.unregister();
        stopSelf();
    }

    private class NewChart extends View {

        private int CHARTH = 300;// 画布高
        private int CHARTW = 500;// 画布宽
        private int OFFSET_LEFT = 70;// 距离左边70
        private int OFFSET_TOP = 80;// 距离顶部80
        private int TEXT_OFFSET = 30;// 文字距离中心位置30
        private int X_INTERVAL = 9;

        private ArrayList<PointF> mPlist;
        private ArrayList<PointF> mPlist0;

        private Paint mPaint = new Paint();

        public NewChart(Context context) {
            super(context);
            mPlist = new ArrayList<>();
            mPlist0 = new ArrayList<>();
        }

        @SuppressLint("DrawAllocation")
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawTable(canvas);
            legend(canvas);
            if (fg) {
                holder.lockCanvas(new Rect(0, 0, 0, 0));
                mPaint.setColor(Color.CYAN);
                int height = 600;
                int width = 600;
                if (flag) {
                    drawDegree(canvas, 1 / n, 1 / n);
                    canvas.drawText(" 🔪f(x)=" + Float.toString(my * n), mx, my, mPaint);
                    canvas.scale(n, n, 0, width / 2);
                } else {
                    switch (mode) {
                        case (1):
                            drawDegree(canvas, disy, disx);
                            canvas.scale(p, p, mid.x, mid.y);
                            canvas.translate(tX - 2 * width / 5, tY - 2 * height / 5);
                            break;
                        case 2:
                            drawDegree(canvas, 1 / p, 1 / p);
                            canvas.scale(p, p, mid.x, mid.y);
                            break;
                    }
                }
                Point(canvas, n);
                Line0(n);
                drawCurve0(canvas);
            } else {
                this.invalidate();
                //	CreatNewPhoto();
            }
        }

        private void drawTable(Canvas canvas) {

            mPaint.setColor(Color.WHITE);// 设置要画出图形的颜色

            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(2);

            Rect chartRec = new Rect(OFFSET_LEFT, OFFSET_TOP + 3, CHARTW + OFFSET_LEFT, CHARTH + OFFSET_TOP + 3);
            // 顶端的文字
            Path textPaint = new Path();
            mPaint.setStyle(Paint.Style.FILL);// 设置画笔的样式
            textPaint.moveTo(250, 44);// 文字排版起始点
            textPaint.lineTo(500, 44);// 结束点
            mPaint.setTextSize(27);// 字号
            mPaint.setAntiAlias(true);// 设置锯齿效果，true消除。
            canvas.drawTextOnPath(getString(R.string.testing), textPaint, 0, 0, mPaint);
            // 左侧数字
            Paint mpt = new Paint();
            mpt.setColor(Color.LTGRAY);
            mpt.setStrokeWidth(5);
            mpt.setTextSize(23);

            // canvas.drawText("9.8(m/s²)", CHARTW/2, OFFSET_TOP + CHARTH/2-60, mpt);
            canvas.drawText("0", OFFSET_LEFT - TEXT_OFFSET - 17, OFFSET_TOP + CHARTH / 2 - 5, mpt);
            canvas.drawText(getString(R.string.range), OFFSET_LEFT - TEXT_OFFSET - 30, OFFSET_TOP + CHARTH / 2 + 22, mpt);

            // 底部单位
            Path oinsPath = new Path();
            Paint oinPaint = new Paint();
            oinPaint.setTextSize(22);
            oinPaint.setStrokeWidth(5);
            oinPaint.setColor(Color.WHITE);
            oinPaint.setStyle(Paint.Style.FILL);
            oinsPath.moveTo(OFFSET_LEFT - TEXT_OFFSET - 30, CHARTH + OFFSET_TOP * 2 - 33);
            oinsPath.lineTo(660, CHARTH + OFFSET_TOP * 2 - 33);
            canvas.drawTextOnPath("(ms)  0", oinsPath, 0, 0, oinPaint);
            Path oPath = new Path();
            Paint oPaint = new Paint();
            oPaint.setTextSize(30);
            oPaint.setStrokeWidth(5);
            oPaint.setAntiAlias(true);
            oPaint.setColor(Color.LTGRAY);
            oPaint.setStyle(Paint.Style.FILL);
            oPath.moveTo(OFFSET_LEFT - 8, CHARTH + OFFSET_TOP * 2 - 60);
            oPath.lineTo(690, CHARTH + OFFSET_TOP * 2 - 60);
            canvas.drawTextOnPath(getString(R.string.arrows), oPath, 0, 0, oPaint);
            // 画表格中的虚线
            Path mPath = new Path();
            Paint aint = new Paint();
            PathEffect effect = new DashPathEffect(new float[]{2, 2, 2, 2}, 1);
            aint.setStyle(Paint.Style.FILL_AND_STROKE);
            aint.setAntiAlias(true);
            aint.setStrokeWidth(2);
            aint.setPathEffect(effect);
            aint.setColor(Color.GRAY);
            canvas.drawRect(chartRec, aint);
            for (int i = 0; i <= 9; i++) {
                mPath.moveTo(OFFSET_LEFT, OFFSET_TOP + CHARTH / 10 * i + 3);
                mPath.lineTo(OFFSET_LEFT + CHARTW, OFFSET_TOP + CHARTH / 10 * i + 3);
                canvas.drawPath(mPath, aint);
            }
        }

        // 图例
        private void legend(Canvas canvas) {

            mPaint.setColor(Color.GREEN);
            Path legPaint = new Path();
            mPaint.setStyle(Paint.Style.FILL);
            legPaint.moveTo(90, CHARTH + OFFSET_TOP * 2 + 7);
            legPaint.lineTo(200, CHARTH + OFFSET_TOP * 2 + 7);
            mPaint.setTextSize(25);
            canvas.drawTextOnPath(getString(R.string.legend), legPaint, 0, 0, mPaint);
            // 图线
            mPaint.setColor(Color.YELLOW);
            canvas.drawLine(80, CHARTH + OFFSET_TOP * 3 - 40, 200, CHARTH + OFFSET_TOP * 3 - 40, mPaint);
            // 文字
            mPaint.setColor(Color.CYAN);
            Path accPath = new Path();
            mPaint.setStyle(Paint.Style.FILL);
            accPath.moveTo(220, CHARTH + OFFSET_TOP * 3 - 33);
            accPath.lineTo(400, CHARTH + OFFSET_TOP * 3 - 33);
            mPaint.setTextSize(20);
            canvas.drawTextOnPath(getString(R.string.CH0), accPath, 0, 0, mPaint);
            /////
            mPaint.setColor(Color.MAGENTA);
            canvas.drawLine(80, CHARTH + OFFSET_TOP * 3, 200, CHARTH + OFFSET_TOP * 3, mPaint);
            mPaint.setColor(Color.CYAN);
            Path graPath = new Path();
            mPaint.setStyle(Paint.Style.FILL);
            graPath.moveTo(220, CHARTH + OFFSET_TOP * 3 + 5);
            graPath.lineTo(400, CHARTH + OFFSET_TOP * 3 + 5);
            mPaint.setTextSize(20);
            canvas.drawTextOnPath(getString(R.string.CH1), graPath, 0, 0, mPaint);
        }

        @Override
        public boolean dispatchTouchEvent(@NonNull MotionEvent ev) {
            try {

                return super.dispatchTouchEvent(ev);
            } catch (IllegalArgumentException exception) {
                Log.d(TAG, "dispatch key event exception");
            }
            return false;
        }

        @TargetApi(Build.VERSION_CODES.ECLAIR)
        private float distance(MotionEvent event) {
            float x = event.getX(0) - event.getX(1);
            float y = event.getY(0) - event.getY(1);
            return (float) Math.sqrt(x * x + y * y);
        }

        @TargetApi(Build.VERSION_CODES.ECLAIR)
        private void midPoint(PointF point, MotionEvent event) {
            try {
                float x = event.getX(0) + event.getX(1);
                float y = event.getY(0) + event.getY(1);
                point.set(x / 2, y / 2);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }

        // 拖动事件
        @TargetApi(Build.VERSION_CODES.ECLAIR)
        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {

            Paint pt = new Paint();
            pt.setTextSize(20);
            pt.setStrokeWidth(5);
            pt.setColor(Color.WHITE);
            pt.setStyle(Paint.Style.FILL);

            switch (event.getAction() & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_OUTSIDE:
                    break;
                case MotionEvent.ACTION_DOWN:
                    x2 = event.getX();
                    y2 = event.getY();
                    mode = 1;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    distance0 = distance(event);
                    mode += 1;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mode == 1) {
                        matrix.postTranslate(event.getX(), event.getY());
                        tX = event.getX();
                        tY = event.getY();
                        disx = x2 / tX;
                        disy = y2 / tY;
                    }
                    if (mode >= 2) {
                        midPoint(mid, event);
                        float distance2 = distance(event);
                        if (flag) n = distance2 / distance0;
                        else p = distance2 / distance0;
                        matrix.setScale(n, n, mid.x, mid.x);
                        matrix.postScale(n, n, mid.x, mid.y);
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    break;
            }
            // 通知该组件重绘
            this.invalidate();
            return true;
        }

        // 刻度
        private void drawDegree(Canvas canvas, float m, float w) {
            Paint mpt = new Paint();
            mpt.setColor(Color.LTGRAY);
            mpt.setStrokeWidth(5);
            mpt.setTextSize(21);
            // 左侧数字
            for (int c = 5; c >= 1; c--) {
                canvas.drawText("+" + 0.5 * m * c, OFFSET_LEFT - TEXT_OFFSET - 23, OFFSET_TOP + CHARTH / 10 * (5 - c), mpt);
            }
            for (int i = 1; i <= 5; i++) {
                canvas.drawText("-" + 0.5 * m * i, OFFSET_LEFT - TEXT_OFFSET - 20, OFFSET_TOP + CHARTH / 10 * (i + 5), mpt);
            }
            for (int j = 1; j <= 11; j++) {
                canvas.drawText("  " + (int) (50 * w * j), OFFSET_LEFT + 20 + 50 * (j - 1), CHARTH + OFFSET_TOP * 2 - 33, mpt);
            }
        }

        // 点的操作--
        private void drawCurve0(Canvas canvas) {
            mPaint.setColor(Color.MAGENTA);
            mPaint.setStrokeWidth(3);
            mPaint.setAntiAlias(true);

            if (mPlist0.size() >= 2) {
                for (int i = 0; i < mPlist0.size() - 1; i++) {
                    canvas.drawCircle(mPlist0.get(i).x, mPlist0.get(i).y, 4, mPaint);
                    canvas.drawLine(mPlist0.get(i).x, mPlist0.get(i).y, mPlist0.get(i + 1).x, mPlist0.get(i + 1).y, mPaint);
                }
            }
        }

        private void Point(Canvas canvas, float m) {

            mPaint.setColor(Color.YELLOW);
            mPaint.setStrokeWidth(5 / m);
            mPaint.setAntiAlias(true);
            if (mPlist.size() >= 2) {
                for (int i = 0; i < mPlist.size() - 1; i++) {
                    canvas.drawCircle(mPlist.get(i).x, mPlist.get(i).y, 3 / m, mPaint);
                    switch (i) {
                        case 0:
                            break;
                        default:
                            canvas.drawLine(mPlist.get(i).x, mPlist.get(i).y, mPlist.get(i - 1).x, mPlist.get(i - 1).y, mPaint);
                            break;
                    }
                }
            }
            float y = (float) (linear_acceleration[0] + 0.55f);

            float Py = OFFSET_TOP + y * (CHARTH - OFFSET_TOP);
            my = Py;
            PointF p = new PointF((float) OFFSET_LEFT / m/*+ CHARTW*/, Py);

            if (flag) {

                if (mPlist.size() > 300) {
                    mPlist.remove(0);
                    for (int i = 0; i < 300; i++) {
                        {
                            if (i == 0) mPlist.get(i).x += (X_INTERVAL - 1);
                            else mPlist.get(i).x += X_INTERVAL;
                        }
                        mx = mPlist0.get(i).x;
                    }
                    mPlist.add(p);
                } else {
                    for (int i = 0; i < mPlist.size(); i++) {
                        mPlist.get(i).x += X_INTERVAL;
                        mx = mPlist0.get(i).x;
                    }
                    mPlist.add(p);
                }
            }
        }

        private void Line0(float m) {

            float y0 = (float) (9.8f - gravity[2]) / 20;

            float Py0 = OFFSET_TOP + (y0 * (CHARTH - OFFSET_TOP));

            PointF p0 = new PointF((float) OFFSET_LEFT / m, Py0);

            if (flag) {

                if (mPlist0.size() > 300) {
                    mPlist0.remove(0);
                    for (int i = 0; i < 300; i++) {
                        {
                            if (i == 0) mPlist0.get(i).x += (X_INTERVAL - 1);
                            else mPlist0.get(i).x += X_INTERVAL;
                        }
                    }
                    mPlist0.add(p0);
                } else {
                    for (int i = 0; i < mPlist0.size(); i++) {
                        mPlist0.get(i).x += X_INTERVAL;
                    }
                    mPlist0.add(p0);
                }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.CUPCAKE)
    private class Chart implements SensorEventListener {

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

        }

        @Override
        public void onSensorChanged(SensorEvent arg) {
            // 检测时间
            long currentUpdateTime = System.currentTimeMillis();
            // 检测的时间间隔
            long timeInterval = currentUpdateTime - lastUpdateTime;
            // 判断是否达到了检测时间间隔
            if (timeInterval < UPTATE_INTERVAL_TIME)
                return;
            // 当前的时间变成last时间
            lastUpdateTime = currentUpdateTime;

            final float alpha = 0.8f;

            gravity[0] = alpha * gravity[SensorManager.DATA_X] + (1 - alpha) * arg.values[SensorManager.DATA_X];
            gravity[1] = alpha * gravity[SensorManager.DATA_Y] + (1 - alpha) * arg.values[SensorManager.DATA_Y];
            gravity[2] = alpha * gravity[SensorManager.DATA_Z] + (1 - alpha) * arg.values[SensorManager.DATA_Z];

            linear_acceleration[0] = arg.values[SensorManager.DATA_X] - gravity[SensorManager.DATA_X];
            linear_acceleration[1] = arg.values[SensorManager.DATA_Y] - gravity[SensorManager.DATA_Y];
            linear_acceleration[2] = arg.values[SensorManager.DATA_Z] - gravity[SensorManager.DATA_Z];
        }
    }
}
