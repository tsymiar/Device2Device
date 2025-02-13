package com.tsymiar.device2device.activity;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.tsymiar.device2device.R;

import java.util.ArrayList;

public class GraphActivity extends AppCompatActivity {

    public final String TAG = GraphActivity.class.getSimpleName();
    private final LineChart[] charts = new LineChart[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_colored_lines);

        setTitle("LineChartActivityColored");

        charts[2] = findViewById(R.id.colored_chart);

        // Typeface mTf = Typeface.createFromAsset(getAssets(),
        // "OpenSans-Bold.ttf");

        for (int i = 0; i < charts.length; i++) {
            if (charts[i] == null)
                continue; // 确保 charts[i] 已初始化
            LineData data = getData();
            // data.setValueTypeface(mTf);
            // add some transparency to the color with "& 0x90FFFFFF"
            setupChart(charts[i], data, colors[i % colors.length]);
        }
    }

    private final int[] colors = new int[] { Color.rgb(137, 230, 81), Color.rgb(240, 240, 30), Color.rgb(89, 199, 250),
            Color.rgb(250, 104, 104) };

    private void setupChart(LineChart chart, LineData data, int color) {

        ((LineDataSet)data.getDataSetByIndex(0)).setCircleHoleColor(color);

        // no description text
        chart.getDescription().setEnabled(false);

        // chart.setDrawHorizontalGrid(false);
        //
        // enable / disable grid background
        chart.setDrawGridBackground(false);
        // chart.getRenderer().getGridPaint().setGridColor(Color.WHITE &
        // 0x70FFFFFF);

        // enable touch gestures
        chart.setTouchEnabled(true);

        // enable scaling and dragging
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);

        // if disabled, scaling can be done on x- and y-axis separately
        chart.setPinchZoom(false);

        chart.setBackgroundColor(color);

        // set custom chart offsets (automatic offset calculation is hereby
        // disabled)
        chart.setViewPortOffsets(10, 0, 10, 0);

        // add data
        chart.setData(data);

        // get the legend (only possible after setting data)
        Legend l = chart.getLegend();
        l.setEnabled(false);

        chart.getAxisLeft().setEnabled(false);
        chart.getAxisLeft().setSpaceTop(40);
        chart.getAxisLeft().setSpaceBottom(40);
        chart.getAxisRight().setEnabled(false);

        chart.getXAxis().setEnabled(false);

        // animate calls invalidate()...
        chart.animateX(2500);
    }

    private LineData getData() {

        int count = 36;
        float range = 100;
        Log.i(TAG, "getData: count = " + count + ", range=" + range);

        ArrayList<Entry> values1 = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float val = (float)(Math.random() * range) + 3;
            values1.add(new Entry(i, val));
        }

        // create a dataset and give it a type
        LineDataSet set1 = new LineDataSet(values1, "DataSet 1");
        // set1.setFillAlpha(110);
        // set1.setFillColor(Color.RED);

        set1.setLineWidth(1.75f);
        set1.setCircleRadius(5f);
        set1.setCircleHoleRadius(2.5f);
        set1.setColor(Color.WHITE);
        set1.setCircleColor(Color.WHITE);
        set1.setHighLightColor(Color.WHITE);
        set1.setDrawValues(false);

        ArrayList<Entry> values2 = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float val = (float)(Math.random() * (range - 1)) + 3;
            values2.add(new Entry(i, val));
        }

        LineDataSet set2 = new LineDataSet(values2, "拟合数据");
        // set2.isDrawValuesEnabled();
        set2.setDrawValues(false);
        set2.setAxisDependency(YAxis.AxisDependency.RIGHT);
        set2.setColor(Color.RED);
        set2.setCircleColor(Color.BLACK);
        set2.setLineWidth(2f);
        set2.setCircleRadius(1f);
        // set2.setFillAlpha(65);
        // set2.setFillColor(Color.RED);
        set2.setDrawCircleHole(true);
        set2.setHighLightColor(Color.rgb(244, 117, 117));

        // create a data object with the data sets
        return new LineData(set1, set2);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return true;
    }
}
