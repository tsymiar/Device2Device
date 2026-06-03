package com.tsymiar.device2device.activity;

import static com.tsymiar.device2device.activity.SelectActivity.RequestAudio;

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
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.button.MaterialButton;
import com.tsymiar.device2device.R;
import com.tsymiar.device2device.utils.LocalFile;
import com.tsymiar.device2device.utils.SamplePlayer;
import com.tsymiar.device2device.utils.SoundRecord;
import com.tsymiar.device2device.utils.WaveCanvas;
import com.tsymiar.device2device.view.WaveSurface;
import com.tsymiar.device2device.view.WaveformsView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class WaveActivity extends AppCompatActivity {

    private static final String TAG = "WaveActivity";
    static final String DATA_DIRECTORY = Environment.getExternalStorageDirectory()
            + "/files/record/";

    private static final int FREQUENCY = 16000;
    private static final int CHANNEL_CONFIGURATION = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    static final int AUDIO_SOURCE = MediaRecorder.AudioSource.MIC;
    private static final String FILE_NAME = "audio";

    private static final int REQUEST_CODE_SPEECH_INPUT = 100;
    private static final int REQUEST_CODE_PICK_AUDIO = 102;
    private static final int MSG_UPDATE_WAV = 100;
    private static final int MSG_UPDATE_STATUS = 101;

    private WaveSurface waveView;
    private WaveformsView waveform;
    private TextView statView;
    private TextView tvResult;
    private MaterialButton recordBtn;
    private LinearLayout zoomBar;
    private TextView zoomLabel;
    private MaterialButton zoomInBtn;
    private MaterialButton zoomOutBtn;

    private WaveCanvas waveCanvas;
    private File mFile;
    private Thread mLoadSoundFileThread;
    private SoundRecord mSoundRecord;
    private boolean mLoadingKeepGoing;
    private static SamplePlayer mPlayer;
    private float mDensity;
    private int mPlayFullMillis;

    private final WaveHandler handler = new WaveHandler(this);

    /**
     * 静态内部类 Handler 避免内存泄漏
     */
    private static class WaveHandler extends Handler {
        private final WeakReference<WaveActivity> ref;

        WaveHandler(WaveActivity activity) {
            super(Looper.getMainLooper());
            this.ref = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            WaveActivity activity = ref.get();
            if (activity == null || activity.isFinishing()) return;

            switch (msg.what) {
                case MSG_UPDATE_STATUS:
                    if (msg.obj != null) {
                        activity.statView.setText(String.valueOf(msg.obj));
                    }
                    break;
                case MSG_UPDATE_WAV:
                    activity.updatePlaybackProgress();
                    sendEmptyMessageDelayed(MSG_UPDATE_WAV, 10);
                    break;
            }
        }
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wave);

        initViews();
        initVoiceInput();
    }

    private void initViews() {
        waveView = findViewById(R.id.wave_surface);
        if (waveView != null) {
            waveView.setLine_off(42);
            waveView.setZOrderOnTop(true);
            waveView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        }

        waveform = findViewById(R.id.wave_form);
        waveform.setLine_offset(42);

        statView = findViewById(R.id.wave_status);
        zoomBar = findViewById(R.id.wave_zoom_bar);
        zoomLabel = findViewById(R.id.wave_zoom_label);
        zoomInBtn = findViewById(R.id.wave_zoom_in);
        zoomOutBtn = findViewById(R.id.wave_zoom_out);

        // 缩放按钮
        zoomInBtn.setOnClickListener(v -> {
            if (waveCanvas != null) {
                waveCanvas.zoomIn(0.25f);
                zoomLabel.setText(String.format("%.1f×", waveCanvas.getZoomY()));
            }
        });
        zoomOutBtn.setOnClickListener(v -> {
            if (waveCanvas != null) {
                waveCanvas.zoomOut(0.25f);
                zoomLabel.setText(String.format("%.1f×", waveCanvas.getZoomY()));
            }
        });

        // 读取/播放按钮
        findViewById(R.id.wave_read).setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            startActivityForResult(intent, REQUEST_CODE_PICK_AUDIO);
        });

        // 录制按钮
        recordBtn = findViewById(R.id.wave_record);
        recordBtn.setOnClickListener(view -> {
            waveView.setVisibility(View.VISIBLE);
            waveform.setVisibility(View.VISIBLE);
            if (waveCanvas != null && waveCanvas.isRecording()) {
                recordBtn.setText(R.string.record);
                waveCanvas.Stop();
                waveCanvas = null;
                statView.setVisibility(View.VISIBLE);
                statView.setText("");
                zoomBar.setVisibility(View.GONE);
            } else {
                if (checkAudioPermissions()) {
                    statView.setVisibility(View.GONE);
                    zoomBar.setVisibility(View.VISIBLE);
                    startDrawWave();
                }
            }
        });
    }

    private void initVoiceInput() {
        Button btnSpeak = findViewById(R.id.btnSpeak);
        tvResult = findViewById(R.id.tvResult);

        if (!isSpeechRecognizerAvailable()) {
            if (btnSpeak != null) {
                btnSpeak.setEnabled(false);
            }
            Toast.makeText(this, R.string.speech_not_supported, Toast.LENGTH_SHORT).show();
            return;
        }

        btnSpeak.setOnClickListener(v -> startVoiceInput());
    }

    private boolean checkAudioPermissions() {
        boolean hasRecord = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean hasStorage = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

        if (!hasRecord || !hasStorage) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.permission_audio_storage)
                    .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                        dialog.dismiss();
                        Toast.makeText(this, R.string.enable_storage_record_permission, Toast.LENGTH_SHORT).show();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            startActivityForResult(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())), RequestAudio);
                        }
                    })
                    .setNegativeButton(android.R.string.no, (dialog, which) -> dialog.dismiss())
                    .show();
            return false;
        }
        return true;
    }

    // ---------- 音频文件加载 & 播放 ----------

    private void loadWaveFile(String filePath) {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            handler.sendMessage(handler.obtainMessage(MSG_UPDATE_STATUS, e.toString()));
        }

        mFile = new File(filePath);
        mSoundRecord = new SoundRecord(this);
        mLoadingKeepGoing = true;

        mLoadSoundFileThread = new Thread(() -> {
            try {
                mSoundRecord = SoundRecord.create(mFile.getAbsolutePath(), fractionComplete -> {
                    handler.sendMessage(handler.obtainMessage(MSG_UPDATE_STATUS, fractionComplete + "%"));
                    return true;
                });
                if (mSoundRecord == null) {
                    handler.sendMessage(handler.obtainMessage(MSG_UPDATE_STATUS,
                            "unsupported audio format"));
                    return;
                }

                mPlayer = new SamplePlayer(mSoundRecord);
            } catch (final Exception e) {
                handler.sendMessage(handler.obtainMessage(MSG_UPDATE_STATUS, e.toString()));
                return;
            }

            if (mLoadingKeepGoing) {
                runOnUiThread(() -> {
                    waveform.setSoundFile(mSoundRecord);
                    DisplayMetrics metrics = new DisplayMetrics();
                    getWindowManager().getDefaultDisplay().getMetrics(metrics);
                    mDensity = metrics.density;
                    waveform.recomputeHeights(mDensity);
                    waveView.setVisibility(View.INVISIBLE);
                    waveform.setVisibility(View.VISIBLE);
                    // 加载完成后自动播放
                    playWaveAudio(0);
                });
            }
            handler.sendMessage(handler.obtainMessage(MSG_UPDATE_STATUS, "loaded wave file."));
        });
        mLoadSoundFileThread.start();
    }

    /** 从 content URI 复制音频文件到缓存目录，返回绝对路径。通过文件头魔数检测类型，避免 ContentResolver.query */
    private String copyUriToFile(Uri uri) {
        try {
            // 读取所有数据到内存
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                baos.write(buf, 0, len);
            }
            is.close();
            byte[] data = baos.toByteArray();
            if (data.length == 0) return null;

            // 通过文件头魔数检测音频类型
            String ext = detectAudioExtension(data);
            File tempFile = new File(getCacheDir(), "picked_audio_"
                    + System.currentTimeMillis() + ext);
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(data);
            }
            return tempFile.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /** 根据文件头魔数检测音频类型，返回带 . 的扩展名 */
    private static String detectAudioExtension(byte[] data) {
        if (data.length < 4) return ".wav";
        // WAV: "RIFF"
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') {
            return ".wav";
        }
        // OGG: "OggS"
        if (data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S') {
            return ".ogg";
        }
        // AMR: "#!AMR"
        if (data.length >= 6 && data[0] == '#' && data[1] == '!' && data[2] == 'A'
                && data[3] == 'M' && data[4] == 'R') {
            return ".amr";
        }
        // MP3: ID3 tag or sync word 0xFF 0xFB / 0xFF 0xF3
        if ((data[0] == 'I' && data[1] == 'D' && data[2] == '3')
                || (data[0] == (byte) 0xFF && (data[1] & 0xFE) == 0xFA)) {
            return ".mp3";
        }
        // AAC ADTS: 0xFF 0xF1
        if (data[0] == (byte) 0xFF && (data[1] & 0xF6) == 0xF0) {
            return ".aac";
        }
        // MP4/M4A/3GP: "ftyp" at offset 4
        if (data.length >= 8 && data[4] == 'f' && data[5] == 't'
                && data[6] == 'y' && data[7] == 'p') {
            return ".m4a";
        }
        // 无法识别时返回 wav 让 MediaExtractor 尝试解码
        return ".wav";
    }

    private synchronized void playWaveAudio(int startPosition) {
        if (mPlayer == null) return;
        if (mPlayer.isPlaying()) {
            mPlayer.pause();
            handler.removeMessages(MSG_UPDATE_WAV);
        }
        mPlayFullMillis = waveform.pixelsToMillisecsTotal();
        int playStartMillis = waveform.pixelsToMillisecs(startPosition);

        mPlayer.setOnCompletionListener(() -> {
            waveform.setPlayback(-1);
            updatePlaybackProgress();
            handler.removeMessages(MSG_UPDATE_WAV);
        });
        mPlayer.seekTo(playStartMillis);
        mPlayer.start();
        handler.sendEmptyMessage(MSG_UPDATE_WAV);
    }

    private void updatePlaybackProgress() {
        int current = mPlayer.getCurrentPosition();
        int frames = waveform.millisecsToPixels(current);
        waveform.setPlayback(frames);
        if (current >= mPlayFullMillis) {
            waveform.setPlayFinish(1);
            if (mPlayer.isPlaying()) {
                mPlayer.pause();
                handler.removeMessages(MSG_UPDATE_WAV);
            }
        } else {
            waveform.setPlayFinish(0);
            waveView.invalidate();
        }
    }

    // ---------- 语音识别 ----------

    private boolean isSpeechRecognizerAvailable() {
        return RecognizerIntent.getVoiceDetailsIntent(this) != null;
    }

    private void startVoiceInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speak_prompt));

        try {
            startActivityForResult(intent, REQUEST_CODE_SPEECH_INPUT);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.speech_error, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- 录音 & 波形绘制 ----------

    @SuppressLint({"SetTextI18n", "MissingPermission"})
    public void startDrawWave() {
        int bufSize = AudioRecord.getMinBufferSize(FREQUENCY, CHANNEL_CONFIGURATION, AUDIO_ENCODING);
        AudioRecord audioRecord = new AudioRecord(AUDIO_SOURCE, FREQUENCY,
                CHANNEL_CONFIGURATION, AUDIO_ENCODING, bufSize);

        LocalFile.createDirectory(DATA_DIRECTORY);
        waveCanvas = new WaveCanvas();
        waveCanvas.setBaseLine(waveView);
        // bufSize 为字节数，PCM_16BIT 下需转为 short 数量
        waveCanvas.Start(audioRecord, bufSize / 2, waveView, FILE_NAME, DATA_DIRECTORY,
                msg -> true);

        recordBtn.setText(getString(R.string.recording_state));
        waveCanvas.resetZoom();
        zoomLabel.setText("1.0×");
    }

    // ---------- ActivityResult ----------

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RequestAudio && resultCode == RESULT_OK) {
            startDrawWave();
        }
        if (requestCode == REQUEST_CODE_PICK_AUDIO && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String path = copyUriToFile(uri);
                if (path != null) {
                    waveView.setVisibility(View.VISIBLE);
                    waveform.setVisibility(View.INVISIBLE);
                    statView.setVisibility(View.VISIBLE);
                    zoomBar.setVisibility(View.GONE);
                    handler.sendMessage(handler.obtainMessage(MSG_UPDATE_STATUS, ""));
                    loadWaveFile(path);
                } else {
                    handler.sendMessage(handler.obtainMessage(MSG_UPDATE_STATUS, "Failed to read audio file"));
                }
            }
        }
        if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultCode == RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty()) {
                tvResult.setText(result.get(0));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RequestAudio) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startDrawWave();
            } else {
                Toast.makeText(this, R.string.permission_denied_record, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onStop() {
        super.onStop();
        if (mPlayer != null) {
            mPlayer.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 停止波形更新
        handler.removeCallbacksAndMessages(null);

        // 停止录音
        if (waveCanvas != null && waveCanvas.isRecording()) {
            waveCanvas.Stop();
            waveCanvas = null;
        }

        // 中断文件加载线程
        mLoadingKeepGoing = false;
        if (mLoadSoundFileThread != null && mLoadSoundFileThread.isAlive()) {
            mLoadSoundFileThread.interrupt();
        }

        // 释放播放器
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer = null;
        }
    }
}
