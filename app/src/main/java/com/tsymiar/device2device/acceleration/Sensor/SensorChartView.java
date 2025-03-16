package com.tsymiar.device2device.acceleration.Sensor;

//import dalvik.system.VMRuntime;

import android.content.Context;
import android.hardware.SensorManager;
import android.hardware.TriggerEventListener;
import android.view.View;

import org.achartengine.chart.PointStyle;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;

import java.util.ArrayList;
import java.util.List;

//----//------------------/-----------------------/*------*****************??*******************************************++++++++++++++++++++////////
public class SensorChartView extends View {
    public SensorChartView(Context context) {
        super(context);
    }

    private XYMultipleSeriesDataset mDataset;
    private XYMultipleSeriesDataset dataset;
    private SensorManager mSensorManager;
    //  private Sensor[] mSensor=new Sensor[5];
    private float EPSILON = 0.0001f;
    private double[] gravity = new double[3];
    //  private TreeSet<Byte> setNode = new TreeSet<Byte>();// 保存节点地址
    private final static float TARGET_HEAP_UTILIZATION = 0.75f;
    private final static int CWJ_HEAP_SIZE = 8 * 1024 * 1024;
    private double[] linear_acceleration = new double[3];
    private static final float NS2S = 1.0f / 1000000000.0f;
    private final double[] deltaRotationVector = new double[4];
    private int UPTATE_INTERVAL_TIME = 100;
    private float timestamp;
    private long curTime;
    private long duration;
    //  private GraphicalView chart;
//  private LinearLayout layoutGraph;
    private TriggerEventListener mTriggerEventListener;
    //  private int addX = -1;
    XYSeries series[] = new XYSeries[5];
    double addY;
    //  private float result1, result2, result3; // 获取的Y值
    ArrayList<Integer> xlist = new ArrayList<Integer>();// 记录每个x的值
    List<double[]> values = new ArrayList<double[]>();
/*
    @Override
    class SensorDemo implements SensorEventListener {

        TextView t;
        Sensor sensor;

        private long lastTime;

        public SensorDemo(Sensor sensor, TextView t) {

            this.t = t;
            this.sensor = sensor;
        }

        public void register() {
            mSensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        public void unregister() {
            mSensorManager.unregisterListener(this);
        }

        // from ChartDemo
        public String getName() {
            return "";
        }

        public String getDesc() {
            return "";
        }

        TextView t1, t2, t3;
        // 传感器管理器
        private SensorManager sensorManager;
        // 传感器
        private Sensor accelerometerSensor, gravitySensor, linearAccelerationSensor;
        SensorChart Chart1, Chart2, Chart3;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            // super.onCreate(savedInstanceState);
            // setContentView(R.layout.activity_main);
            t1 = (TextView) findViewById(R.id.t1);
            t2 = (TextView) findViewById(R.id.t2);
            t3 = (TextView) findViewById(R.id.t3);

            // sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

            // Chart1 = new SensorChart(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), t1);
            // Chart2 = new SensorChart(sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY), t2);
            // Chart3 = new SensorChart(sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), t3);
        }

        @Override
        protected void onResume() {
            // TODO Auto-generated method stub
            super.onResume();
            Chart1.register();
            Chart2.register();
            Chart3.register();
        }

        @Override
        protected void onPause() {
            // TODO Auto-generated method stub
            super.onPause();
            Chart1.unregister();
            Chart2.unregister();
            Chart3.unregister();
        }

        private void updateChart(double point, XYSeries series) {

            addX = 0;
            addY = point;
            int[] xv = new int[100]; // 横坐标值
            float[] yv = new float[100]; // 纵坐标值

            // 移除所有点,始终只显示50个点
            mDataset.removeSeries(series);
            int num = series.getItemCount();
            if (num > 50) {
                num = 50;
            }
            for (int i = 0; i < num; i++) {
                xv[i] = (int) (series.getX(i) + 1);
                yv[i] = (float) series.getY(i);
            }

            System.out.println(Arrays.toString(yv));
            // 点集先清空，为了做成新的点集而准备
            series.clear();
       /*
        * 将新产生的点首先加入到点集中，然后在循环体中将坐标变换后的一系列点都重新加入到点集中
        / 这里可以试验一下把顺序颠倒过来是什么效果，即先运行循环体，再添加新产生的点
       *-*/ /*
            for (int k = 0; k < num; k++) {
                series.add(xv[k], yv[k]);
            }
            series.add(addX, addY);

            // 在数据集中添加新的点集
            dataset.addSeries(series);

            // 视图更新，没有这一步，曲线不会呈现动态
            // 如果在非UI主线程中，需要调用postInvalidate()，具体参考api
            chart.invalidate();
        }

        // 打开监控的方法
        protected XYMultipleSeriesDataset buildDataset(String[] titles,
                                                       List xValues, List yValues) {
            dataset = new XYMultipleSeriesDataset();

            int length = titles.length; // 有几条线
            for (int i = 0; i < length; i++) {
                series[i] = new XYSeries(titles[i]); // 根据每条线的名称创建点集合
                double[] xV = (double[]) xValues.get(i); // 获取第i条线的数据
                double[] yV = (double[]) yValues.get(i);
                int seriesLength = xV.length; // 有几个点

                for (int k = 0; k < seriesLength; k++) // 每条线里有几个点
                {
                    series[i].add(xV[k], yV[k]);
                }

                dataset.addSeries(series[i]); // 点集合加入到数据集中
            }

            return dataset;
        }

        public Handler handler = new Handler() {
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case 1:
                        // 节点1
                        result1 = msg.getData().getFloat("tem1");
                        if ((result1 - 0) != 0.0) {
                            System.out.println("tem1=========>" + result1);
                        }
                        break;
                    case 2:
                        // 节点2
                        result2 = msg.getData().getFloat("tem2");
                        if ((result2 - 0) != 0.0) {
                            System.out.println("tem2*********>" + result2);
                        }
                        break;
                    case 3:
                        // 节点3
                        result3 = msg.getData().getFloat("tem3");
                        if ((result3 - 0) != 0.0) {
                            System.out.println("tem3+++++++++>" + result3);
                        }
                        break;
                }
                // 必须同时更新,否则会曲线会交替出现
                updateChart(result1, series[0]); // 更新曲线a
                updateChart(result2, series[1]); // 更新曲线b
                updateChart(result3, series[2]);
            }

            ;
        };

        /**
         * 坐标轴属性
         */
        protected XYMultipleSeriesRenderer buildRenderer(int colors[],
                                                         PointStyle style[], boolean fill) {
            XYMultipleSeriesRenderer renderer = new XYMultipleSeriesRenderer();
            renderer = new XYMultipleSeriesRenderer();
            int length = colors.length;
            for (int i = 0; i < length; i++) {
                XYSeriesRenderer r = new XYSeriesRenderer();
                r.setColor(colors[i]);
                r.setPointStyle(style[i]);
                r.setFillPoints(fill);
                r.setLineWidth(3);
                renderer.addSeriesRenderer(r);
            }

            return renderer;
        }
/*
        @Override
        public Intent execute(Context context) {
            String[] titles = new String[]{"加速度", "重力", "陀螺仪", "其他"};

            for (int i = 0; i < titles.length; i++) {
                x.add(new double[]{i});//此处最有可能越界，一个x对应一个values，共用i脚标
            }
            values.add(new double[]{gravity[0]});
            values.add(new double[]{gravity[1]});
            values.add(new double[]{gravity[2]});
            values.add(new double[]{deltaRotationVector[0]});

            // 图表渲染--曲线
            int[] colors = new int[]{Color.RED, Color.GREEN, Color.CYAN, Color.YELLOW};
            PointStyle[] styles = new PointStyle[]{PointStyle.CIRCLE, PointStyle.DIAMOND,
                    PointStyle.TRIANGLE, PointStyle.SQUARE};
            XYMultipleSeriesRenderer renderer = buildRenderer(colors, styles);
            int length = renderer.getSeriesRendererCount();
            for (int i = 0; i < length; i++) {
                XYSeriesRenderer seriesRenderer = (XYSeriesRenderer) renderer.getSeriesRendererAt(i);
                seriesRenderer.setFillPoints(true);
                // 生成图表,加入布局框显示
                // layoutGraph.addView(chart);

                // 绘制曲线下方积分面积
                if (i == length - 1) {
                    XYSeriesRenderer.FillOutsideLine fill = new XYSeriesRenderer.FillOutsideLine(XYSeriesRenderer.FillOutsideLine.Type.BOUNDS_ALL);
                    fill.setColor(Color.GREEN);
                    seriesRenderer.addFillOutsideLine(fill);
                }
            }
            setChartSettings(renderer, "传感器实时曲线", "时间(ms)", "幅度", 0, 9.5, -5, 5, Color.LTGRAY, Color.WHITE);
            renderer.setXLabels(20);// 设置合适的刻度,在轴上显示的数量是 MAX / labels
            renderer.setYLabels(20);
            renderer.setShowGrid(true);// 网格
            renderer.setXLabelsAlign(Paint.Align.RIGHT);
            renderer.setYLabelsAlign(Paint.Align.RIGHT);
            renderer.setZoomButtonsVisible(true);
            // renderer.setBackgroundColor(Color.rgb(0xD9, 0xff, 0xff)); // 设置图表背景色
            renderer.setLabelsColor(Color.WHITE); // xy轴标签的颜色
            renderer.setPointSize((float) 5);
            renderer.setShowLegend(true); // 图例

            XYMultipleSeriesDataset dataset = buildDataset(titles, x, values);
            XYSeries series = dataset.getSeriesAt(0);
            series.addAnnotation("Vacation", 6, 3);
            Intent intent = ChartFactory.getLineChartIntent(context, dataset, renderer,
                    "AChartEngine-图表");
            return intent;
        }

        @Override
        protected void onResume() {
            x.add(new double[]{0});
            x.add(new double[]{0});
            x.add(new double[]{0});
            values.add(new double[]{0});
            values.add(new double[]{0});
            values.add(new double[]{0});

        }
    }
    */
}
