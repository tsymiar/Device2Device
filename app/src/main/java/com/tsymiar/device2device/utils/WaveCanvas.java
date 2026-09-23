package com.tsymiar.device2device.utils;

import static android.media.AudioRecord.STATE_INITIALIZED;

import android.annotation.SuppressLint;
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

import com.tsymiar.device2device.view.WaveSurface;
import com.tsymiar.device2device.wrapper.MediaWrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

/**
 * 波形和写入文件使用了两个不同的线程，以免造成卡机现象
 * 实时波形绘制
 *
 * @author cokus
 */
public class WaveCanvas {

    private static final String TAG = WaveCanvas.class.getSimpleName();
    private final ArrayList<Short> inBuf = new ArrayList<>();//缓冲区数据
    private final ArrayList<byte[]> mWriteData = new ArrayList<>();//写入文件数据
    private boolean mIsWriting = false;// 录音线程控制标记
    private boolean mIsRecording = false;// 录音线程控制标记
    private int mLineOff;//上下边距的距离
    private static final int RATE_X = 100;//控制多少帧取一帧
    /** 麦克风采样率：抽稀后每秒 RATE_X 分之一的点进波形 */
    private static final int SAMPLE_RATE = 16000;
    /** 1× 时一屏铺满的音频时长（秒），X 轴放大后这个窗口变短 = 细节更多 */
    private static final double WINDOW_SECONDS = 20.0;
    /** Y 轴（幅度）缩放范围：以前最大只有 3×，看小信号不够用 */
    private static final float ZOOM_Y_MIN = 0.1f;
    private static final float ZOOM_Y_MAX = 20.0f;
    /** X 轴（时基）缩放范围：1× = 20 秒一屏，8× = 2.5 秒一屏 */
    private static final float ZOOM_X_MIN = 1.0f;
    private static final float ZOOM_X_MAX = 8.0f;
    /** 满刻度（0 dBFS）对应的声压级：1 Pa = 94 dB SPL */
    private static final double DB_SPL_AT_FULL_SCALE = 94.0;
    /** dBFS 下限：PCM 16bit 的动态范围到 -90 dBFS */
    private static final double DBFS_FLOOR = -90.0;
    /** 显示下限：手机底噪换算出来不到 30 dB，按「安静的室内」兜底，免得数字低得不真实 */
    private static final double DB_SPL_FLOOR = 30.0;
    private int mBaseLine = 0;// Y轴基线
    private PaintingTask mAudioTask = null;
    private final int mMarginRight = 30;//波形图绘制距离右边的距离
    private float mDivider = 0.2f;//为了节约绘画时间，每0.2个像素画一个数据
    private long mCurrentTime;//当前时间戳
    private DecibelListener mDecibelListener;
    /**
     * 实时分贝，单位 dB SPL（日常看到的分贝数：安静 ≈ 30 dB，正常说话 60 dB 上下）。
     * 换算：先算 dBFS = 20·log10(rms / 满刻度)，再 +94 dB（0 dBFS 对应 1 Pa = 94 dB SPL）。
     * 手机麦克风没有校准，这里是工程上通用的参考换算，够用来看相对大小。
     */
    private volatile double mCurrentDb = DB_SPL_FLOOR;
    private float mZoomY = 1.0f;// Y轴缩放倍率（幅度）
    private float mZoomX = 1.0f;// X轴缩放倍率（时基，越大时间窗口越短）
    private Paint mPaintDbBg;// 分贝文字背景
    private Paint mPaintDbText;// 分贝文字
    private String mSavePcmPath;//保存pcm文件路径
    private String mSaveWavPath;//保存wav文件路径
    private Paint mCirclePaint;
    private Paint mCenter;
    private Paint mPaintLine;
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
    public void startRecording(AudioRecord audioRecord, int recBufSize, SurfaceView sfv, String audioName,
                               String path, Callback callback) {
        mSavePcmPath = path + audioName + ".pcm";
        mSaveWavPath = path + audioName + ".wav";
        new Thread(new WriteRunnable()).start();//开线程写文件
        mAudioTask = new PaintingTask(audioRecord, recBufSize, sfv, mPaint, callback);
        mAudioTask.execute();
    }

    private void init() {
        mCirclePaint = new Paint();//画圆
        mCirclePaint.setColor(Color.rgb(246, 131, 126));//设置上圆的颜色

        mCenter = new Paint();//中心线
        mCenter.setColor(Color.rgb(39, 199, 175));// 画笔为color
        mCenter.setStrokeWidth(1);// 设置画笔粗细
        mCenter.setAntiAlias(true);
        mCenter.setFilterBitmap(true);
        mCenter.setStyle(Style.FILL);

        mPaintLine = new Paint();
        mPaintLine.setColor(Color.rgb(221, 221, 221));
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

        mPaintDbBg = new Paint();
        mPaintDbBg.setColor(0xcc_000000);
        mPaintDbBg.setAntiAlias(true);

        mPaintDbText = new Paint();
        mPaintDbText.setColor(Color.rgb(0, 255, 0));
        mPaintDbText.setTextSize(36);
        mPaintDbText.setAntiAlias(true);
        mPaintDbText.setFakeBoldText(true);
    }

    /**
     * 停止绘制
     */
    public void stopRecording() {
        mIsRecording = false;
        if (mAudioTask != null) {
            mAudioTask.stopRecord();
        }
        clear(); // 清除
    }

    /**
     * 清除数据
     */
    public void clear() {
        inBuf.clear();
    }

    /**
     * 异步绘制程序
     *
     * @author cokus
     */
    @SuppressLint("StaticFieldLeak")
    class PaintingTask extends AsyncTask<Object, Object, Object> {
        private final int recBufSize;
        private final AudioRecord audioRecord;
        private final SurfaceView sfv;// 画板
        private final Paint mPaint;// 画笔
        private final Callback callback;

        PaintingTask(AudioRecord audioRecord, int recBufSize,
                     SurfaceView sfv, Paint mPaint, Callback callback) {
            this.audioRecord = audioRecord;
            this.recBufSize = recBufSize;
            this.sfv = sfv;
            mLineOff = ((WaveSurface) sfv).getLineOff();
            this.mPaint = mPaint;
            this.callback = callback;
            inBuf.clear();// 清除 换缓冲区的数据
            mIsRecording = true;
        }

        @Override
        protected Object doInBackground(Object... params) {
            try {
                short[] buffer = new short[recBufSize];
                audioRecord.startRecording();// 开始录制
                while (mIsRecording) {
                    // 从MIC保存数据到缓冲区
                    int readSize = audioRecord.read(buffer, 0,
                            recBufSize);
                    synchronized (inBuf) {
                        for (int i = 0; i < readSize; i += RATE_X) {
                            inBuf.add(buffer[i]);
                        }
                    }
                    // 在后台线程中基于原始 buffer 计算实时分贝
                    if (readSize > 0) {
                        double sum = 0;
                        for (int i = 0; i < readSize; i++) {
                            sum += (double) buffer[i] * buffer[i];
                        }
                        double rms = Math.sqrt(sum / readSize);
                        if (rms <= 0) {
                            mCurrentDb = DB_SPL_FLOOR;
                        } else {
                            // 标准算法：dBFS = 20·log10(rms / 满刻度)，再加 94 dB 换成声压级
                            double dbfs = Math.max(DBFS_FLOOR, 20.0 * Math.log10(rms / 32768.0));
                            mCurrentDb = Math.max(DB_SPL_FLOOR, dbfs + DB_SPL_AT_FULL_SCALE);
                        }
                    }
                    publishProgress();
                    if (AudioRecord.ERROR_INVALID_OPERATION != readSize) {
                        synchronized (mWriteData) {
                            byte[] bys = new byte[readSize * 2];
                            //因为arm字节序问题，所以需要高低位交换
                            for (int i = 0; i < readSize; i++) {
                                byte[] ss = getBytes(buffer[i]);
                                bys[i * 2] = ss[0];
                                bys[i * 2 + 1] = ss[1];
                            }
                            mWriteData.add(bys);
                        }
                    }
                }
                mIsWriting = false;
            } catch (Throwable t) {
                Message msg = new Message();
                msg.arg1 = -2;
                msg.obj = t.getMessage();
                callback.handleMessage(msg);
            }
            return null;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void onProgressUpdate(Object... values) {
            long time = new Date().getTime();
            //两次绘图间隔的时间
            int draw_time = 1000 / 200;
            if (time - mCurrentTime >= draw_time) {
                ArrayList<Short> arrBuf = null;
                synchronized (inBuf) {
                    if (inBuf.size() == 0)
                        return;
                    while (inBuf.size() > (sfv.getWidth() - mMarginRight) / mDivider) {
                        inBuf.remove(0);
                    }
                    Object object = inBuf.clone();
                    if (object instanceof ArrayList<?>) {
                        arrBuf = (ArrayList<Short>) object;// 缓存
                    }
                }
                if (arrBuf != null) {
                    if (mDecibelListener != null) {
                        mDecibelListener.onDecibelChanged(mCurrentDb);
                    }
                    simpleDraw(arrBuf, sfv.getHeight() / 2);// 把缓冲区数据画出来
                }
                mCurrentTime = new Date().getTime();
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
         * @param mBaseLine Y轴基线
         */
        void simpleDraw(ArrayList<Short> buf, int mBaseLine) {
            //波形图绘制距离左边的距离
            int marginLeft = 20;
            // 每个采样点占多少像素：X 轴放大后一屏只画更短的一段音频，波形细节更多
            double samplesPerSecond = SAMPLE_RATE / (double) RATE_X;
            double windowSeconds = WINDOW_SECONDS / mZoomX;
            mDivider = (float) ((sfv.getWidth() - mMarginRight - marginLeft)
                    / (samplesPerSecond * windowSeconds));
            if (!mIsRecording)
                return;
            //  Y轴缩小的比例 默认为1；放大到很高倍率时别让它变成 0（除 0 会崩）
            int rateY = Math.max(1, (int) ((65535 / 2 / (sfv.getHeight() - mLineOff)) / mZoomY));

            for (int i = 0; i < buf.size(); i++) {
                byte[] bus = getBytes(buf.get(i));
                buf.set(i, (short) ((bus[1]) << 8 | bus[0]));//高低位交换
            }
            Canvas canvas = sfv.getHolder().lockCanvas(
                    new Rect(0, 0, sfv.getWidth(), sfv.getHeight()));// 关键:获取画布
            if (canvas == null)
                return;
            canvas.drawARGB(255, 239, 239, 239);
            int start = (int) ((buf.size()) * mDivider);

            if (sfv.getWidth() - start <= mMarginRight) {//如果超过预留的右边距距离
                start = sfv.getWidth() - mMarginRight;//画的位置x坐标
            }
            canvas.drawLine(0, mLineOff >> 1, sfv.getWidth(), mLineOff >> 1, mPaintLine);//最上面的那根线
            canvas.drawLine(0, sfv.getHeight() - (mLineOff >> 1) - 1, sfv.getWidth(), sfv.getHeight() - (mLineOff >> 1) - 1, mPaintLine);//最下面的那根线
            canvas.drawCircle(start, mLineOff >> 1, mLineOff / 10.f, mCirclePaint);// 上圆
            canvas.drawCircle(start, sfv.getHeight() - (mLineOff >> 1) - 1, mLineOff / 10.f, mCirclePaint);// 下圆
            canvas.drawLine(start, mLineOff >> 1, start, sfv.getHeight() - (mLineOff >> 1), mCirclePaint);//垂直的线
            int height = sfv.getHeight() - mLineOff;
            canvas.drawLine(0, height * 0.5f + (mLineOff >> 1), sfv.getWidth(), height * 0.5f + (mLineOff >> 1), mCenter);//中心线

            canvas.drawLine(0, height*0.25f+20, sfv.getWidth(),height*0.25f+20, mPaintLine);//第二根线
            canvas.drawLine(0, height*0.75f+20, sfv.getWidth(),height*0.75f+20, mPaintLine);//第3根线
            float y;
            float prevX = -1f;
            float prevTop = 0f;
            float prevBottom = 0f;
            for (int i = 0; i < buf.size(); i++) {
                y = 1.f * buf.get(i) / rateY + mBaseLine;// 调节缩小比例，调节基准线
                float x = (i) * mDivider;
                if (sfv.getWidth() - (i - 1) * mDivider <= mMarginRight) {
                    x = sfv.getWidth() - mMarginRight;
                }
                float y1 = sfv.getHeight() - y;
                if (y < mLineOff >> 1) {
                    y = mLineOff >> 1;
                }
                if (y > sfv.getHeight() - (mLineOff >> 1) - 1) {
                    y = sfv.getHeight() - (mLineOff >> 1) - 1;

                }
                if (y1 < mLineOff >> 1) {
                    y1 = mLineOff >> 1;
                }
                if (y1 > (sfv.getHeight() - (mLineOff >> 1) - 1)) {
                    y1 = (sfv.getHeight() - (mLineOff >> 1) - 1);
                }
                canvas.drawLine(x, y, x, y1, mPaint);//中间出波形
                if (prevX >= 0 && mDivider > 1.2f) {
                    // 时基放大后点与点之间拉开距离，补上连线波形才连得起来
                    canvas.drawLine(prevX, prevTop, x, y, mPaint);
                    canvas.drawLine(prevX, prevBottom, x, y1, mPaint);
                }
                prevX = x;
                prevTop = y;
                prevBottom = y1;
            }
            // 在画布右上角绘制实时分贝
            String dbText = String.format("%.0f dB", mCurrentDb);
            float textW = mPaintDbText.measureText(dbText);
            float textH = mPaintDbText.getTextSize();
            float padX = 14, padY = 8;
            // 直角标签：页面里的卡片都不带圆角，这里也别例外
            canvas.drawRect(
                    sfv.getWidth() - textW - padX * 2 - 6, 6,
                    sfv.getWidth() - 6, padY + textH + 8,
                    mPaintDbBg);
            canvas.drawText(dbText, sfv.getWidth() - textW - padX - 6, padY + textH, mPaintDbText);
            sfv.getHolder().unlockCanvasAndPost(canvas);// 解锁画布，提交画好的图像
        }

        void stopRecord() {
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
            FileOutputStream fos2wav = null;
            try {
                File file2wav;
                try {
                    file2wav = new File(mSavePcmPath);
                    fos2wav = new FileOutputStream(file2wav);// 建立一个可存取字节的文件
                } catch (Exception e) {
                    e.printStackTrace();
                }
                while (mIsWriting || !mWriteData.isEmpty()) {
                    byte[] buffer = null;
                    synchronized (mWriteData) {
                        if (!mWriteData.isEmpty()) {
                            buffer = mWriteData.get(0);
                            mWriteData.remove(0);
                        }
                    }
                    try {
                        if (buffer != null && fos2wav != null) {
                            fos2wav.write(buffer);
                            fos2wav.flush();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                //将pcm格式转换成wav一个44字节的头信息
                MediaWrapper.convertAudioFiles(mSavePcmPath, mSaveWavPath);
            } catch (Throwable t) {
                Log.e(TAG, t.toString());
            } finally {
                try {
                    if (fos2wav != null)
                        fos2wav.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public boolean mIsRecording() {
        return this.mIsRecording;
    }

    public int getBaseLine() {
        return this.mBaseLine;
    }

    public void setBaseLine(View view) {
        this.mBaseLine = view.getHeight() / 2;
    }

    /**
     * 实时分贝回调接口
     */
    public interface DecibelListener {
        /** 回传标准声压级 dB SPL（安静 ≈ 30 dB，正常说话 60 dB 上下） */
        void onDecibelChanged(double dbSpl);
    }

    public void setDecibelListener(DecibelListener listener) {
        this.mDecibelListener = listener;
    }

    /** 波形 Y 轴（幅度）缩放：step 是每次点击的比例步长，按倍数缩放才够快爬到 20× */
    public float getZoomY() { return mZoomY; }
    public void zoomIn(float step) { mZoomY = Math.min(mZoomY * (1f + step), ZOOM_Y_MAX); }
    public void zoomOut(float step) { mZoomY = Math.max(mZoomY / (1f + step), ZOOM_Y_MIN); }

    /** 波形 X 轴（时基）缩放：倍率越大，一屏容纳的时间越短，细节越多 */
    public float getZoomX() { return mZoomX; }
    public void zoomTimeIn(float step) { mZoomX = Math.min(mZoomX * (1f + step), ZOOM_X_MAX); }
    public void zoomTimeOut(float step) { mZoomX = Math.max(mZoomX / (1f + step), ZOOM_X_MIN); }

    public void resetZoom() { mZoomY = 1.0f; mZoomX = 1.0f; }

}
