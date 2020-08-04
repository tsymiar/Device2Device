package com.tsymiar.devidroid.utils;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.media.AudioRecord;
import android.os.AsyncTask;
import android.os.Handler.Callback;
import android.os.Message;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;

import com.tsymiar.devidroid.view.WaveSurfaceView;
import com.tsymiar.devidroid.wrapper.FileWrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import static android.media.AudioRecord.STATE_INITIALIZED;

/**
 * 波形和写入文件使用了两个不同的线程，以免造成卡机现象
 * 实时波形绘制
 *
 * @author cokus
 */
public class WaveCanvas {

    private static final String TAG = WaveCanvas.class.getSimpleName();
    private final ArrayList<Short> inBuf = new ArrayList<Short>();//缓冲区数据
    private final ArrayList<byte[]> write_data = new ArrayList<byte[]>();//写入文件数据
    private boolean isWriting = false;// 录音线程控制标记
    private boolean isRecording = false;// 录音线程控制标记
    private ArrayList<String> filePathList = new ArrayList<>();
    private int line_off;//上下边距的距离
    private int rateX = 100;//控制多少帧取一帧
    private int baseLine = 0;// Y轴基线
    private PaintingTask audioTask = null;
    private int marginRight = 30;//波形图绘制距离右边的距离
    private float divider = 0.2f;//为了节约绘画时间，每0.2个像素画一个数据
    private long c_time;//当前时间戳
    private String savePcmPath;//保存pcm文件路径
    private String saveWavPath;//保存wav文件路径
    private Paint circlePaint;
    private Paint center;
    private Paint paintLine;
    private Paint mPaint;

    public WaveCanvas() {
        init();
    }

    /**
     * 开始绘制
     *
     * @param audioRecord AudioRecord object
     * @param recBufSize  buffer size
     * @param sfv         surface
     * @param audioName   name
     */
    public void Start(AudioRecord audioRecord, int recBufSize, SurfaceView sfv, String audioName,
                      String path, Callback callback) {
        savePcmPath = path + audioName + ".pcm";
        saveWavPath = path + audioName + ".wav";
        new Thread(new WriteRunnable()).start();//开线程写文件
        audioTask = new PaintingTask(audioRecord, recBufSize, sfv, mPaint, callback);
        audioTask.execute();
    }

    private void init() {
        circlePaint = new Paint();//画圆
        circlePaint.setColor(Color.rgb(246, 131, 126));//设置上圆的颜色

        center = new Paint();//中心线
        center.setColor(Color.rgb(39, 199, 175));// 画笔为color
        center.setStrokeWidth(1);// 设置画笔粗细
        center.setAntiAlias(true);
        center.setFilterBitmap(true);
        center.setStyle(Style.FILL);

        paintLine = new Paint();
        paintLine.setColor(Color.rgb(221, 221, 221));
        Paint paintText = new Paint();
        paintText.setColor(Color.rgb(255, 255, 255));
        paintText.setStrokeWidth(3);
        paintText.setTextSize(22);

        Paint paintRect = new Paint();
        paintRect.setColor(Color.rgb(39, 199, 175));

        mPaint = new Paint();
        mPaint.setColor(Color.rgb(39, 199, 175));// 画笔为color
        mPaint.setStrokeWidth(1);// 设置画笔粗细
        mPaint.setAntiAlias(true);
        mPaint.setFilterBitmap(true);
        mPaint.setStyle(Paint.Style.FILL);
    }


    /**
     * 停止绘制
     */
    public void Stop() {
        isRecording = false;
        if (audioTask != null) {
            audioTask.StopRecord();
        }
        //inBuf.clear();// 清除
    }

    /**
     * 清除数据
     */
    public void Clear() {
        inBuf.clear();// 清除
    }

    /**
     * 异步绘制程序
     *
     * @author cokus
     */
    class PaintingTask extends AsyncTask<Object, Object, Object> {
        private int recBufSize;
        private AudioRecord audioRecord;
        private SurfaceView sfv;// 画板
        private Paint mPaint;// 画笔
        private Callback callback;

        PaintingTask(AudioRecord audioRecord, int recBufSize,
                     SurfaceView sfv, Paint mPaint, Callback callback) {
            this.audioRecord = audioRecord;
            this.recBufSize = recBufSize;
            this.sfv = sfv;
            line_off = ((WaveSurfaceView) sfv).getLine_off();
            this.mPaint = mPaint;
            this.callback = callback;
            inBuf.clear();// 清除 换缓冲区的数据
            isRecording = true;
        }

        @Override
        protected Object doInBackground(Object... params) {
            try {
                short[] buffer = new short[recBufSize];
                audioRecord.startRecording();// 开始录制
                while (isRecording) {
                    // 从MIC保存数据到缓冲区
                    int readsize = audioRecord.read(buffer, 0,
                            recBufSize);
                    synchronized (inBuf) {
                        for (int i = 0; i < readsize; i += rateX) {
                            inBuf.add(buffer[i]);
                        }
                    }
                    publishProgress();
                    if (AudioRecord.ERROR_INVALID_OPERATION != readsize) {
                        synchronized (write_data) {
                            byte[] bys = new byte[readsize * 2];
                            //因为arm字节序问题，所以需要高低位交换
                            for (int i = 0; i < readsize; i++) {
                                byte[] ss = getBytes(buffer[i]);
                                bys[i * 2] = ss[0];
                                bys[i * 2 + 1] = ss[1];
                            }
                            write_data.add(bys);
                        }
                    }
                }
                isWriting = false;
            } catch (Throwable t) {
                Message msg = new Message();
                msg.arg1 = -2;
                msg.obj = t.getMessage();
                callback.handleMessage(msg);
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(Object... values) {
            long time = new Date().getTime();
            //两次绘图间隔的时间
            int draw_time = 1000 / 200;
            if (time - c_time >= draw_time) {
                ArrayList<Short> buf = new ArrayList<Short>();
                synchronized (inBuf) {
                    if (inBuf.size() == 0)
                        return;
                    while (inBuf.size() > (sfv.getWidth() - marginRight) / divider) {
                        inBuf.remove(0);
                    }
                    buf = (ArrayList<Short>) inBuf.clone();// 缓存
                }
                SimpleDraw(buf, sfv.getHeight() / 2);// 把缓冲区数据画出来
                c_time = new Date().getTime();
            }
            super.onProgressUpdate(values);
        }


        byte[] getBytes(short s) {
            byte[] buf = new byte[2];
            for (int i = 0; i < buf.length; i++) {
                buf[i] = (byte) (s & 0x00ff);
                s >>= 8;
            }
            return buf;
        }

        /**
         * 绘制指定区域
         *
         * @param buf      缓冲区
         * @param baseLine Y轴基线
         */
        void SimpleDraw(ArrayList<Short> buf, int baseLine) {
            //波形图绘制距离左边的距离
            int marginLeft = 20;
            divider = (float) ((sfv.getWidth() - marginRight - marginLeft) / (16000 / rateX * 20.00));
            if (!isRecording)
                return;
            //  Y轴缩小的比例 默认为1
            int rateY = (65535 / 2 / (sfv.getHeight() - line_off));

            for (int i = 0; i < buf.size(); i++) {
                byte[] bus = getBytes(buf.get(i));
                buf.set(i, (short) ((bus[1]) << 8 | bus[0]));//高低位交换
            }
            Canvas canvas = sfv.getHolder().lockCanvas(
                    new Rect(0, 0, sfv.getWidth(), sfv.getHeight()));// 关键:获取画布
            if (canvas == null)
                return;
            canvas.drawARGB(255, 239, 239, 239);
            int start = (int) ((buf.size()) * divider);
            float py = baseLine;
            float y;

            if (sfv.getWidth() - start <= marginRight) {//如果超过预留的右边距距离
                start = sfv.getWidth() - marginRight;//画的位置x坐标
            }
            canvas.drawLine(0, line_off >> 1, sfv.getWidth(), line_off >> 1, paintLine);//最上面的那根线
            canvas.drawLine(0, sfv.getHeight() - (line_off >> 1) - 1, sfv.getWidth(), sfv.getHeight() - (line_off >> 1) - 1, paintLine);//最下面的那根线
            canvas.drawCircle(start, line_off >> 1, line_off / 10.f, circlePaint);// 上圆
            canvas.drawCircle(start, sfv.getHeight() - (line_off >> 1) - 1, line_off / 10.f, circlePaint);// 下圆
            canvas.drawLine(start, line_off >> 1, start, sfv.getHeight() - (line_off >> 1), circlePaint);//垂直的线
            int height = sfv.getHeight() - line_off;
            canvas.drawLine(0, height * 0.5f + (line_off >> 1), sfv.getWidth(), height * 0.5f + (line_off >> 1), center);//中心线

//	         canvas.drawLine(0, height*0.25f+20, sfv.getWidth(),height*0.25f+20, paintLine);//第二根线
//	         canvas.drawLine(0, height*0.75f+20, sfv.getWidth(),height*0.75f+20, paintLine);//第3根线
            for (int i = 0; i < buf.size(); i++) {
                y = 1.f * buf.get(i) / rateY + baseLine;// 调节缩小比例，调节基准线
                float x = (i) * divider;
                if (sfv.getWidth() - (i - 1) * divider <= marginRight) {
                    x = sfv.getWidth() - marginRight;
                }
                float y1 = sfv.getHeight() - y;
                if (y < line_off >> 1) {
                    y = line_off >> 1;
                }
                if (y > sfv.getHeight() - (line_off >> 1) - 1) {
                    y = sfv.getHeight() - (line_off >> 1) - 1;

                }
                if (y1 < line_off >> 1) {
                    y1 = line_off >> 1;
                }
                if (y1 > (sfv.getHeight() - (line_off >> 1) - 1)) {
                    y1 = (sfv.getHeight() - (line_off >> 1) - 1);
                }
                canvas.drawLine(x, y, x, y1, mPaint);//中间出波形
            }
            sfv.getHolder().unlockCanvasAndPost(canvas);// 解锁画布，提交画好的图像
        }

        void StopRecord() {
            if (audioRecord.getState() == STATE_INITIALIZED) {
                audioRecord.stop();
            }
        }
    }

    /**
     * 异步写文件
     *
     * @author cokus
     */
    class WriteRunnable implements Runnable {
        @Override
        public void run() {
            try {
                FileOutputStream fos2wav = null;
                File file2wav;
                try {
                    file2wav = new File(savePcmPath);
                    if (file2wav.exists()) {
                        file2wav.delete();
                    }
                    fos2wav = new FileOutputStream(file2wav);// 建立一个可存取字节的文件
                } catch (Exception e) {
                    e.printStackTrace();
                }
                while (isWriting || write_data.size() > 0) {
                    byte[] buffer = null;
                    synchronized (write_data) {
                        if (write_data.size() > 0) {
                            buffer = write_data.get(0);
                            write_data.remove(0);
                        }
                    }
                    try {
                        if (buffer != null) {
                            fos2wav.write(buffer);
                            fos2wav.flush();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                assert fos2wav != null;
                fos2wav.close();
                //将pcm格式转换成wav一个44字节的头信息
                FileWrapper.convertAudioFiles(savePcmPath, saveWavPath);
            } catch (Throwable t) {
                Log.e(TAG, t.toString());
            }
        }
    }

    public boolean isRecording() {
        return this.isRecording;
    }

    public int getBaseLine() {
        return this.baseLine;
    }

    public void setBaseLine(View view) {
        this.baseLine = view.getHeight() / 2;
    }
}
