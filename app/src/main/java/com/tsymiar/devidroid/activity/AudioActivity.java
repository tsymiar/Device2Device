package com.tsymiar.devidroid.activity;

import static com.tsymiar.devidroid.activity.MainActivity.RequestAudio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.tsymiar.devidroid.R;
import com.tsymiar.devidroid.utils.LocalFile;
import com.tsymiar.devidroid.utils.SamplePlayer;
import com.tsymiar.devidroid.utils.SoundFile;
import com.tsymiar.devidroid.utils.WaveCanvas;
import com.tsymiar.devidroid.view.WaveSurface;
import com.tsymiar.devidroid.view.WaveformsView;

import java.io.File;

public class AudioActivity extends AppCompatActivity {

    public final String TAG = AudioActivity.class.getSimpleName();
    public final String DATA_DIRECTORY = Environment.getExternalStorageDirectory()
            + "/Android/data/" + "com.tsymiar.devidroid" + "/record/";
    private static final int FREQUENCY = 16000;// 设置音频采样率,44100是目前的标准,某些设备仍然支持22050，16000，11025
    private static final int CHANNEL_CONFIGURATION = AudioFormat.CHANNEL_IN_MONO;// 设置单声道声道
    private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;// 音频数据格式：每个样本16位
    public final static int AUDIO_SOURCE = MediaRecorder.AudioSource.MIC;// 音频获取源
    private static final String mFileName = "audio";
    WaveSurface waveView;
    WaveformsView waveform;
    WaveCanvas waveCanvas = null;
    TextView status;
    Button recdBtn;

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
            int start = 0;
            playWaveAudio(start);
        });
        recdBtn = findViewById(R.id.wave_record);
        recdBtn.setOnClickListener(view -> {
            waveView.setVisibility(View.VISIBLE);
            waveform.setVisibility(View.VISIBLE);
            if (waveCanvas != null && waveCanvas.isRecording()) {
                recdBtn.setText("RECORD");
                waveCanvas.Stop();
                waveCanvas = null;
            } else {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                        || ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    new AlertDialog.Builder(this)
                            .setMessage("Storage and Record Permission needed, jump to setting?")
                            .setPositiveButton("yes", (dialog12, which) -> {
                                dialog12.dismiss();
                                Toast.makeText(this, "Please enable Storage and Record PERMISSION", Toast.LENGTH_SHORT).show();
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    startActivityForResult(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())), RequestAudio);
                                }
                            }).setNegativeButton("no", (dialog1, which) -> dialog1.dismiss()).setCancelable(false).show();
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                } else {
                    startDrawWave();
                }
            }
        });
    }

    File mFile;
    Thread mLoadSoundFileThread;
    SoundFile mSoundFile = new SoundFile(this);
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
                    AudioActivity.this.runOnUiThread(runnable);
                }
                statusHandle.sendMessage(statusHandle.obtainMessage(0, "loaded wave file."));
            }
        };
        mLoadSoundFileThread.start();
    }

    private int mPlayFullMisc;
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
        int mPlayStartMisc = waveform.pixelsToMillisecs(startPosition);
        mPlayFullMisc = waveform.pixelsToMillisecsTotal();
        mPlayer.setOnCompletionListener(() -> {
            waveform.setPlayback(-1);
            setDisplaySpeed();
            updateWaveTime.removeMessages(UPDATE_WAV);
        });
        mPlayer.seekTo(mPlayStartMisc);
        mPlayer.start();
        Message msg = new Message();
        msg.what = UPDATE_WAV;
        updateWaveTime.sendMessage(msg);
    }

    @SuppressLint("HandlerLeak")
    final Handler updateWaveTime = new Handler() {
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
        if (current >= mPlayFullMisc) {
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

    @SuppressLint("SetTextI18n")
    public void startDrawWave() {
        int bufSize = AudioRecord.getMinBufferSize(
                FREQUENCY,
                CHANNEL_CONFIGURATION,
                AUDIO_ENCODING);// 录音组件
        @SuppressLint("MissingPermission")
        AudioRecord audioRecord = new AudioRecord(AUDIO_SOURCE,// 指定音频来源，麦克风
                FREQUENCY, // 16000HZ采样频率
                CHANNEL_CONFIGURATION,// 录制通道
                AUDIO_SOURCE,// 录制编码格式
                bufSize);// 录制缓冲区大小
        LocalFile.createDirectory(DATA_DIRECTORY);
        waveCanvas = new WaveCanvas();
        waveCanvas.setBaseLine(waveView);
        Handler.Callback msgCallback = msg -> true;
        waveCanvas.Start(audioRecord, bufSize, waveView, mFileName, DATA_DIRECTORY, msgCallback);
        int baseLine = waveCanvas.getBaseLine();
        Log.i(TAG, "waveCanvas baseline = " + baseLine);
        recdBtn.setText("Recording");
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RequestAudio && resultCode == RESULT_OK) {
            startDrawWave();
        }
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
