package com.tsymiar.devidroid.activity;

import android.annotation.SuppressLint;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.tsymiar.devidroid.R;
import com.tsymiar.devidroid.utils.LocalFile;
import com.tsymiar.devidroid.utils.SamplePlayer;
import com.tsymiar.devidroid.utils.SoundFile;
import com.tsymiar.devidroid.utils.WaveCanvas;
import com.tsymiar.devidroid.view.WaveSurfaceView;
import com.tsymiar.devidroid.view.WaveformView;

import java.io.File;

public class WaveActivity extends AppCompatActivity {

    public final String DATA_DIRECTORY = Environment.getExternalStorageDirectory()
            + "/Android/data/" + "com.tsymiar.devidroid" + "/record/";
    private static final int FREQUENCY = 16000;// 设置音频采样率,44100是目前的标准,某些设备仍然支持22050，16000，11025
    private static final int CHANNELCONGIFIGURATION = AudioFormat.CHANNEL_IN_MONO;// 设置单声道声道
    private static final int AUDIOENCODING = AudioFormat.ENCODING_PCM_16BIT;// 音频数据格式：每个样本16位
    public final static int AUDIO_SOURCE = MediaRecorder.AudioSource.MIC;// 音频获取源
    private static final String mFileName = "test";
    WaveSurfaceView waveView;
    WaveformView waveform;
    WaveCanvas waveCanvas = null;
    TextView status;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wave);
        waveView = findViewById(R.id.wave_surface);
        if (waveView != null) {
            waveView.setLine_off(42);
            //解决surfaceView黑色闪动效果
            waveView.setZOrderOnTop(true);
            waveView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        }
        waveform = findViewById(R.id.wave_form);
        waveform.setLine_offset(42);
        status = findViewById(R.id.wave_status);
        findViewById(R.id.wave_read).setOnClickListener(view -> {
            waveView.setVisibility(View.VISIBLE);
            waveform.setVisibility(View.INVISIBLE);
            statusHandle.sendMessage(statusHandle.obtainMessage(0, ""));
            loadWaveFile();
            playWaveAudio(0);
        });
        Button recdBtn = findViewById(R.id.wave_record);
        recdBtn.setOnClickListener(view -> {
            waveView.setVisibility(View.VISIBLE);
            waveform.setVisibility(View.VISIBLE);
            if (waveCanvas != null && waveCanvas.isRecording()) {
                recdBtn.setText("RECORD");
                waveCanvas.Stop();
                waveCanvas = null;
            } else {
                startDrawWave();
                recdBtn.setText("Recording");
            }
        });
    }

    File mFile;
    Thread mLoadSoundFileThread;
    SoundFile mSoundFile;
    boolean mLoadingKeepGoing;
    static SamplePlayer mPlayer;
    float mDensity;

    private void loadWaveFile() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mFile = new File(DATA_DIRECTORY + mFileName + ".wav");
        mLoadingKeepGoing = true;
        // Load the sound file in a background thread
        mLoadSoundFileThread = new Thread() {
            public void run() {
                try {
                    mSoundFile = SoundFile.create(mFile.getAbsolutePath(), null);
                    if (mSoundFile == null) {
                        return;
                    }
                    mPlayer = new SamplePlayer(mSoundFile);
                } catch (final Exception e) {
                    e.printStackTrace();
                    statusHandle.sendMessage(statusHandle.obtainMessage(-1, e.toString()));
                    return;
                }
                if (mLoadingKeepGoing) {
                    Runnable runnable = () -> {
                        waveform.setSoundFile(mSoundFile);
                        DisplayMetrics metrics = new DisplayMetrics();
                        getWindowManager().getDefaultDisplay().getMetrics(metrics);
                        mDensity = metrics.density;
                        waveform.recomputeHeights(mDensity);
                        waveView.setVisibility(View.INVISIBLE);
                        waveform.setVisibility(View.VISIBLE);
                    };
                    WaveActivity.this.runOnUiThread(runnable);
                }
                statusHandle.sendMessage(statusHandle.obtainMessage(0, "loaded wave file."));
            }
        };
        mLoadSoundFileThread.start();
    }

    private int mPlayEndMsec;
    private final int UPDATE_WAV = 100;

    /**
     * 播放音频
     *
     * @param startPosition 开始播放的时间
     */
    private synchronized void playWaveAudio(int startPosition) {
        if (mPlayer == null)
            return;
        if (mPlayer.isPlaying()) {
            mPlayer.pause();
            updateWaveTime.removeMessages(UPDATE_WAV);
        }
        int mPlayStartMsec = waveform.pixelsToMillisecs(startPosition);
        mPlayEndMsec = waveform.pixelsToMillisecsTotal();
        mPlayer.setOnCompletionListener(() -> {
            waveform.setPlayback(-1);
            setDisplaySpeed();
            updateWaveTime.removeMessages(UPDATE_WAV);
        });
        mPlayer.seekTo(mPlayStartMsec);
        mPlayer.start();
        Message msg = new Message();
        msg.what = UPDATE_WAV;
        updateWaveTime.sendMessage(msg);
    }

    @SuppressLint("HandlerLeak")
    Handler updateWaveTime = new Handler() {
        public void handleMessage(Message msg) {
            setDisplaySpeed();
            updateWaveTime.sendMessageDelayed(new Message(), 10);
        }
    };

    @SuppressLint("HandlerLeak")
    Handler statusHandle = new Handler() {
        @SuppressLint("HandlerLeak")
        public void handleMessage(Message msg) {
            status.setText(String.valueOf(msg.obj));
        }
    };

    // 更新updateView播放进度
    private void setDisplaySpeed() {
        int current = mPlayer.getCurrentPosition();
        int frames = waveform.millisecsToPixels(current);
        waveform.setPlayback(frames);
        if (current >= mPlayEndMsec) {
            waveform.setPlayFinish(1);
            if (mPlayer != null && mPlayer.isPlaying()) {
                mPlayer.pause();
                updateWaveTime.removeMessages(UPDATE_WAV);
            }
        } else {
            waveform.setPlayFinish(0);
        }
        waveView.invalidate();
    }

    public void startDrawWave() {
        int recvBufSize = AudioRecord.getMinBufferSize(
                FREQUENCY,
                CHANNELCONGIFIGURATION,
                AUDIOENCODING);// 录音组件
        AudioRecord audioRecord = new AudioRecord(AUDIO_SOURCE,// 指定音频来源，麦克风
                FREQUENCY, // 16000HZ采样频率
                CHANNELCONGIFIGURATION,// 录制通道
                AUDIO_SOURCE,// 录制编码格式
                recvBufSize);// 录制缓冲区大小
        LocalFile.createDirectory(DATA_DIRECTORY);
        waveCanvas = new WaveCanvas();
        waveCanvas.setBaseLine(waveView);
        Handler.Callback msgCallback = new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                return true;
            }
        };
        waveCanvas.Start(audioRecord, recvBufSize, waveView, mFileName, DATA_DIRECTORY, msgCallback);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mPlayer == null) {
            return;
        }
        mPlayer.stop();
    }
}
