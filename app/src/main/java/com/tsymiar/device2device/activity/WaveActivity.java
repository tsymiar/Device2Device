package com.tsymiar.device2device.activity;

import static com.tsymiar.device2device.activity.SelectActivity.RequestAudio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.button.MaterialButton;
import com.tsymiar.device2device.R;
import com.tsymiar.device2device.utils.AppDataPaths;
import com.tsymiar.device2device.utils.LocalFile;
import com.tsymiar.device2device.utils.SamplePlayer;
import com.tsymiar.device2device.utils.SoundRecord;
import com.tsymiar.device2device.utils.WaveCanvas;
import com.tsymiar.device2device.view.DecibelView;
import com.tsymiar.device2device.view.WaveSurface;
import com.tsymiar.device2device.view.WaveformsView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Locale;

public class WaveActivity extends AppCompatActivity {

    private static final String TAG = "WaveActivity";

    private static final int FREQUENCY = 16000;
    private static final int CHANNEL_CONFIGURATION = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    static final int AUDIO_SOURCE = MediaRecorder.AudioSource.MIC;
    private static final String FILE_NAME = "audio";

    private static final int REQUEST_CODE_SPEECH_INPUT = 100;
    private static final int REQUEST_CODE_PICK_AUDIO = 102;
    private static final int MSG_UPDATE_WAV = 100;
    private static final int MSG_UPDATE_STATUS = 101;

    private WaveSurface mWaveView;
    private WaveformsView mWaveform;
    private DecibelView mDecibelView;
    private View mResultCard;
    private TextView mStatView;
    private TextView mTvResult;
    private MaterialButton mRecordBtn;
    private LinearLayout mZoomBar;
    private TextView mZoomLabel;
    private MaterialButton mZoomInBtn;
    private MaterialButton mZoomOutBtn;
    private MaterialButton mTimeInBtn;
    private MaterialButton mTimeOutBtn;
    private TextView mTimeLabel;
    /** 文件模式信息区：格式、时长、采样率与手势提示 */
    private View mFileInfoCard;
    private TextView mFileInfoTitle;
    private TextView mFileInfoDetail;
    private TextView mFileInfoHint;
    private MaterialButton mPlayToggleBtn;
    private MaterialButton mSpeedBtn;
    private SeekBar mSeekProgress;
    private SeekBar mSeekGain;
    private TextView mTimeCurrent;
    private TextView mTimeTotal;
    private TextView mGainLabel;
    private boolean mPlaying;
    private boolean mSeeking;
    private float mPlaySpeed = 1f;

    private WaveCanvas mWaveCanvas;
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
                        activity.mStatView.setText(String.valueOf(msg.obj));
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
        mWaveView = findViewById(R.id.wave_surface);
        if (mWaveView != null) {
            mWaveView.setLineOff(42);
            mWaveView.setZOrderOnTop(true);
            mWaveView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        }

        mWaveform = findViewById(R.id.wave_form);
        mWaveform.setLineOffset(42);

        mStatView = findViewById(R.id.wave_status);
        mDecibelView = findViewById(R.id.db_meter);
        mResultCard = findViewById(R.id.result_card);
        mZoomBar = findViewById(R.id.wave_zoom_bar);
        mZoomLabel = findViewById(R.id.wave_zoom_label);
        mZoomInBtn = findViewById(R.id.wave_zoom_in);
        mZoomOutBtn = findViewById(R.id.wave_zoom_out);
        mTimeInBtn = findViewById(R.id.wave_time_in);
        mTimeOutBtn = findViewById(R.id.wave_time_out);
        mTimeLabel = findViewById(R.id.wave_time_label);
        mFileInfoCard = findViewById(R.id.file_info_card);
        mFileInfoTitle = findViewById(R.id.file_info_title);
        mFileInfoDetail = findViewById(R.id.file_info_detail);
        mFileInfoHint = findViewById(R.id.file_info_hint);
        mPlayToggleBtn = findViewById(R.id.btn_play_toggle);
        mSpeedBtn = findViewById(R.id.btn_speed);
        mSeekProgress = findViewById(R.id.seek_progress);
        mSeekGain = findViewById(R.id.seek_gain);
        mTimeCurrent = findViewById(R.id.tv_time_current);
        mTimeTotal = findViewById(R.id.tv_time_total);
        mGainLabel = findViewById(R.id.tv_gain);

        if (mPlayToggleBtn != null) {
            mPlayToggleBtn.setOnClickListener(v -> togglePlayback());
        }
        if (mSpeedBtn != null) {
            mSpeedBtn.setOnClickListener(v -> cycleSpeed());
        }
        if (mSeekProgress != null) {
            mSeekProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!mSeeking) return;
                    setTimeText(mTimeCurrent, progress);
                    // 拖动时波形上的播放指示线跟着走
                    mWaveform.setPlayback(mWaveform.millisecsToPixels(progress));
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    mSeeking = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    mSeeking = false;
                    if (mPlayer == null) return;
                    mPlayer.seekTo(seekBar.getProgress());
                    handler.removeMessages(MSG_UPDATE_WAV);
                    handler.sendEmptyMessage(MSG_UPDATE_WAV);
                }
            });
        }
        if (mSeekGain != null) {
            mSeekGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    applyGain(progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) { }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) { }
            });
        }

        // 幅度（Y 轴）缩放：最大 20×
        mZoomInBtn.setOnClickListener(v -> {
            if (mWaveCanvas != null) {
                mWaveCanvas.zoomIn(0.25f);
                updateZoomLabels();
            }
        });
        mZoomOutBtn.setOnClickListener(v -> {
            if (mWaveCanvas != null) {
                mWaveCanvas.zoomOut(0.25f);
                updateZoomLabels();
            }
        });
        // 时基（X 轴）缩放：一屏时间越短，波形细节越多
        mTimeInBtn.setOnClickListener(v -> {
            if (mWaveCanvas != null) {
                mWaveCanvas.zoomTimeIn(0.5f);
                updateZoomLabels();
            }
        });
        mTimeOutBtn.setOnClickListener(v -> {
            if (mWaveCanvas != null) {
                mWaveCanvas.zoomTimeOut(0.5f);
                updateZoomLabels();
            }
        });

        findViewById(R.id.wave_read).setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // 裸 PCM 常被标成 application/octet-stream，不能只收 audio/*
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"audio/*", "application/octet-stream"});
            pickAudioLauncher.launch(intent);
        });

        mRecordBtn = findViewById(R.id.wave_record);
        mRecordBtn.setOnClickListener(view -> {
            mWaveView.setVisibility(View.VISIBLE);
            mWaveform.setVisibility(View.VISIBLE);
            if (mWaveCanvas != null && mWaveCanvas.mIsRecording()) {
                mRecordBtn.setText(R.string.record);
                mWaveCanvas.setDecibelListener(null);
                mWaveCanvas.stopRecording();
                mWaveCanvas = null;
                if (mDecibelView != null) {
                    mDecibelView.reset();
                    // 分贝卡片只在录音中露脸，停下来就收回
                    mDecibelView.setVisibility(View.GONE);
                }
                mStatView.setVisibility(View.VISIBLE);
                mStatView.setText("");
                mZoomBar.setVisibility(View.GONE);
            } else {
                if (checkAudioPermissions()) {
                    mStatView.setVisibility(View.GONE);
                    mZoomBar.setVisibility(View.VISIBLE);
                    if (mFileInfoCard != null) mFileInfoCard.setVisibility(View.GONE);
                    if (mPlaying && mPlayer != null) {
                        mPlayer.pause();
                        mPlaying = false;
                        handler.removeMessages(MSG_UPDATE_WAV);
                        setPlayButtonText();
                    }
                    // 分贝卡片只在录音中露脸（startDrawWave 里会 reset 历史与峰值）
                    if (mDecibelView != null) mDecibelView.setVisibility(View.VISIBLE);
                    startDrawWave();
                }
            }
        });
    }

    private void initVoiceInput() {
        Button btnSpeak = findViewById(R.id.btn_speak);
        mTvResult = findViewById(R.id.tv_result);

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
                            settingsLauncher.launch(new Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())));
                        }
                    })
                    .setNegativeButton(android.R.string.no, (dialog, which) -> dialog.dismiss())
                    .show();
            return false;
        }
        return true;
    }

    // ---------- 音频文件加载 & 播放 ----------

    /** 解码器抽象：容器走 MediaExtractor，裸 PCM 走 createPcm */
    private interface SoundLoader {
        SoundRecord load() throws Exception;
    }

    private void loadWaveFile(String filePath) {
        Log.i(TAG, "loadWaveFile path=" + filePath);
        // 裸 PCM 没有文件头，必须问清采样率/声道；其余格式按魔数识别的扩展名交给 MediaExtractor
        if (filePath.toLowerCase(Locale.ROOT).endsWith(".pcm")) {
            showPcmFormatPicker(filePath);
            return;
        }
        startAudioLoad(() -> SoundRecord.create(filePath, fraction -> {
            handler.sendMessage(handler.obtainMessage(MSG_UPDATE_STATUS,
                    String.format(Locale.getDefault(), "加载中 %d%%", (int) (fraction * 100))));
            return true;
        }));
    }

    private void loadPcmFile(String path, int sampleRate, int channels) {
        Log.i(TAG, "loadPcmFile path=" + path + " rate=" + sampleRate + " ch=" + channels);
        startAudioLoad(() -> SoundRecord.createPcm(path, sampleRate, channels, fraction -> {
            handler.sendMessage(handler.obtainMessage(MSG_UPDATE_STATUS,
                    String.format(Locale.getDefault(), "加载中 %d%%", (int) (fraction * 100))));
            return true;
        }));
    }

    /** 选择裸 PCM 的采样率/声道（文件里没有这些信息，选错了只会音高/速度不对） */
    private void showPcmFormatPicker(String path) {
        final int[] rates = {16000, 44100, 48000, 8000};
        final int[] channels = {1, 1, 2, 1};
        String[] items = {
                "16 kHz · 单声道（录音默认）",
                "44.1 kHz · 单声道（CD）",
                "48 kHz · 立体声（视频音轨）",
                "8 kHz · 单声道（电话音质）"
        };
        final int[] picked = {0};
        new AlertDialog.Builder(this)
                .setTitle(R.string.pcm_format_title)
                .setMessage(R.string.pcm_format_message)
                .setSingleChoiceItems(items, 0, (dialog, which) -> picked[0] = which)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("加载", (dialog, which) ->
                        loadPcmFile(path, rates[picked[0]], channels[picked[0]]))
                .show();
    }

    /** 容器与 PCM 共用的后台加载 + UI 落地 */
    private void startAudioLoad(SoundLoader loader) {
        Log.i(TAG, "startAudioLoad begin");
        mLoadingKeepGoing = true;
        handler.sendMessage(handler.obtainMessage(MSG_UPDATE_STATUS, ""));
        mLoadSoundFileThread = new Thread(() -> {
            SoundRecord record;
            try {
                record = loader.load();
                if (record == null) {
                    Log.e(TAG, "startAudioLoad: decode returned null");
                    handler.sendMessage(handler.obtainMessage(MSG_UPDATE_STATUS,
                            "unsupported audio format"));
                    return;
                }
                Log.i(TAG, "decoded type=" + record.getFiletype() + " samples="
                        + record.getNumSamples() + " rate=" + record.getSampleRate()
                        + " ch=" + record.getChannels());
                mPlayer = new SamplePlayer(record);
                Log.i(TAG, "SamplePlayer created");
            } catch (final Exception e) {
                Log.e(TAG, "startAudioLoad failed", e);
                handler.sendMessage(handler.obtainMessage(MSG_UPDATE_STATUS, e.toString()));
                return;
            }

            if (mLoadingKeepGoing) {
                runOnUiThread(() -> {
                    mSoundRecord = record;
                    mWaveform.setSoundFile(record);
                    DisplayMetrics metrics = new DisplayMetrics();
                    getWindowManager().getDefaultDisplay().getMetrics(metrics);
                    mDensity = metrics.density;
                    mWaveform.recomputeHeights(mDensity);
                    mWaveView.setVisibility(View.INVISIBLE);
                    mWaveform.setVisibility(View.VISIBLE);
                    showFileInfo(record);
                    applyGain(mSeekGain != null ? mSeekGain.getProgress() : 100);
                    if (mPlayer != null) mPlayer.setSpeed(mPlaySpeed);
                    playWaveAudio(0);
                });
            }
        });
        mLoadSoundFileThread.start();
    }

    /** 显示文件信息与播放控制 */
    @SuppressLint("SetTextI18n")
    private void showFileInfo(SoundRecord record) {
        if (mFileInfoCard == null) return;
        mFileInfoCard.setVisibility(View.VISIBLE);
        if (mFileInfoTitle != null) {
            mFileInfoTitle.setText(getString(R.string.audio_file_title,
                    record.getFiletype().toUpperCase(Locale.ROOT)));
        }
        if (mFileInfoDetail != null) {
            float seconds = record.getSampleRate() > 0
                    ? (float) record.getNumSamples() / record.getSampleRate() : 0f;
            int total = Math.round(seconds);
            String duration = String.format(Locale.getDefault(), "%02d:%02d", total / 60, total % 60);
            mFileInfoDetail.setText(String.format(Locale.getDefault(),
                    "时长 %s · %d Hz · %d 声道 · %d 采样",
                    duration, record.getSampleRate(), record.getChannels(), record.getNumSamples()));
        }
        if (mFileInfoHint != null) {
            mFileInfoHint.setText(R.string.wave_hint);
        }
        int totalMs = mWaveform.pixelsToMillisecsTotal();
        if (mSeekProgress != null) {
            mSeekProgress.setMax(Math.max(1, totalMs));
            mSeekProgress.setProgress(0);
        }
        setTimeText(mTimeCurrent, 0);
        setTimeText(mTimeTotal, totalMs);
        mPlaying = false;
        mSeeking = false;
        setPlayButtonText();
    }

    private static void setTimeText(TextView view, int msecs) {
        if (view == null) return;
        int secs = Math.max(0, msecs) / 1000;
        view.setText(String.format(Locale.getDefault(), "%02d:%02d", secs / 60, secs % 60));
    }

    private void setPlayButtonText() {
        if (mPlayToggleBtn != null) {
            mPlayToggleBtn.setText(mPlaying ? "⏸ 暂停" : "▶ 播放");
        }
    }

    /** 播放 / 暂停切换；拖动进度条不影响这里的状态 */
    private void togglePlayback() {
        if (mPlayer == null || mFileInfoCard == null
                || mFileInfoCard.getVisibility() != View.VISIBLE) return;
        if (mPlaying) {
            mPlayer.pause();
            mPlaying = false;
            handler.removeMessages(MSG_UPDATE_WAV);
        } else {
            mPlayer.start();
            mPlaying = true;
            handler.removeMessages(MSG_UPDATE_WAV);
            handler.sendEmptyMessage(MSG_UPDATE_WAV);
        }
        setPlayButtonText();
    }

    /** 倍速循环 0.5× → 1× → 2×，播放指示线由 SamplePlayer 的进度自然反映 */
    private void cycleSpeed() {
        float[] speeds = {0.5f, 1f, 2f};
        int next = 0;
        for (float s : speeds) {
            if (Math.abs(s - mPlaySpeed) < 0.01f) break;
            next++;
        }
        mPlaySpeed = speeds[next % speeds.length];
        if (mPlayer != null) mPlayer.setSpeed(mPlaySpeed);
        if (mSpeedBtn != null) {
            mSpeedBtn.setText(String.format(Locale.getDefault(), "速度 %.1f×", mPlaySpeed));
        }
    }

    /**
     * 音量 0~100% 控制播放增益；波形显示增益允许放大到 200%，
     * 所以波形高度会直接跟着滑杆变化，方便看安静片段。
     */
    private void applyGain(int percent) {
        float gain = percent / 100f;
        if (mPlayer != null) mPlayer.setVolume(Math.min(1f, gain));
        mWaveform.setDisplayGain(gain);
        if (mGainLabel != null) {
            mGainLabel.setText(String.format(Locale.getDefault(), "%d%%", percent));
        }
    }

    /**
     * 取 SAF 文档的真实文件名。
     *
     * uri.getLastPathSegment() 在 DocumentsProvider 下往往是 "audio:1234" 这种文档 id，
     * 不含 ".pcm"，裸 PCM 就会被误判成 wav 走 MediaExtractor 而失败。
     */
    private String queryDisplayName(Uri uri) {
        String name = null;
        try {
            Cursor c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) name = c.getString(idx);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "queryDisplayName failed", e);
        }
        if (TextUtils.isEmpty(name)) {
            String last = uri.getLastPathSegment();
            if (last != null) {
                try {
                    name = java.net.URLDecoder.decode(last, "UTF-8");
                } catch (Exception ignored) {
                    name = last;
                }
            }
        }
        Log.i(TAG, "queryDisplayName=" + name + " uri=" + uri);
        return name;
    }

    /** 从 content URI 复制音频文件到缓存目录，返回绝对路径。优先用文件名后缀，其次靠魔数 */
    private String copyUriToFile(Uri uri, String displayName) {
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

            // 裸 PCM 没有魔数，只能看文件名；其余类型靠文件头识别
            String lowerName = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
            String ext = lowerName.contains(".pcm") ? ".pcm" : detectAudioExtension(data);
            Log.i(TAG, "copyUriToFile name=" + displayName + " ext=" + ext
                    + " bytes=" + data.length);
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
        // FLAC: "fLaC"
        if (data[0] == 'f' && data[1] == 'L' && data[2] == 'a' && data[3] == 'C') {
            return ".flac";
        }
        // WebM / Matroska: EBML 头 1A 45 DF A3
        if ((data[0] & 0xFF) == 0x1A && (data[1] & 0xFF) == 0x45
                && (data[2] & 0xFF) == 0xDF && (data[3] & 0xFF) == 0xA3) {
            return ".webm";
        }
        // AMR: "#!AMR"
        if (data.length >= 6 && data[0] == '#' && data[1] == '!' && data[2] == 'A'
                && data[3] == 'M' && data[4] == 'R') {
            return ".amr";
        }
        // MP3: ID3 tag
        if (data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
            return ".mp3";
        }
        if (data[0] == (byte) 0xFF) {
            // AAC ADTS: 0xFF 0xF1（MPEG-4）/ 0xFF 0xF9（MPEG-2）
            if (data[1] == (byte) 0xF1 || data[1] == (byte) 0xF9) {
                return ".aac";
            }
            // MPEG 音频帧（0xFB / 0xFA / 0xF3 / 0xF2 等，同步字高 3 位为 111）
            if ((data[1] & 0xE0) == 0xE0) {
                return ".mp3";
            }
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
        mPlayFullMillis = mWaveform.pixelsToMillisecsTotal();
        int playStartMillis = mWaveform.pixelsToMillisecs(startPosition);

        mPlayer.setOnCompletionListener(() -> {
            mWaveform.setPlayback(-1);
            updatePlaybackProgress();
            handler.removeMessages(MSG_UPDATE_WAV);
            mPlaying = false;
            runOnUiThread(this::setPlayButtonText);
        });
        mPlayer.seekTo(playStartMillis);
        mPlayer.start();
        mPlaying = true;
        setPlayButtonText();
        handler.sendEmptyMessage(MSG_UPDATE_WAV);
    }

    private void updatePlaybackProgress() {
        int current = mPlayer.getCurrentPosition();
        int frames = mWaveform.millisecsToPixels(current);
        mWaveform.setPlayback(frames);
        if (!mSeeking) {
            if (mSeekProgress != null) {
                mSeekProgress.setProgress(Math.min(mPlayFullMillis, current));
            }
            setTimeText(mTimeCurrent, current);
        }
        if (current >= mPlayFullMillis) {
            mWaveform.setPlayFinish(1);
            if (mPlayer.isPlaying()) {
                mPlayer.pause();
                handler.removeMessages(MSG_UPDATE_WAV);
            }
            mPlaying = false;
            setPlayButtonText();
        } else {
            mWaveform.setPlayFinish(0);
            // 文件模式显示的是 mWaveform，mWaveView 仅用于录音预览
            mWaveform.invalidate();
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
            speechLauncher.launch(intent);
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

        String dataDirectory = AppDataPaths.getRecordDir(this).getAbsolutePath() + File.separator;
        LocalFile.createDirectory(dataDirectory);
        mWaveCanvas = new WaveCanvas();
        mWaveCanvas.setBaseLine(mWaveView);
        // bufSize 为字节数，PCM_16BIT 下需转为 short 数量
        mWaveCanvas.startRecording(audioRecord, bufSize / 2, mWaveView, FILE_NAME, dataDirectory,
                msg -> true);
        // 实时分贝：WaveCanvas 已经换算成标准声压级（dB SPL），直接交给分贝卡片
        mWaveCanvas.setDecibelListener((db, peak) -> runOnUiThread(() -> {
            if (mDecibelView != null) mDecibelView.setLevel(db, peak);
        }));

        mRecordBtn.setText(getString(R.string.recording_state));
        mWaveCanvas.resetZoom();
        updateZoomLabels();
        // 新的一轮录音：分贝的历史和「全程峰值」都从零开始算
        if (mDecibelView != null) {
            mDecibelView.reset();
        }
    }

    /** 把幅度/时基倍率写到缩放条上 */
    @SuppressLint("SetTextI18n")
    private void updateZoomLabels() {
        if (mWaveCanvas == null) return;
        mZoomLabel.setText(String.format("%.1f×", mWaveCanvas.getZoomY()));
        mTimeLabel.setText(String.format("%.1f×", mWaveCanvas.getZoomX()));
    }

    // ---------- ActivityResult ----------

    private final ActivityResultLauncher<Intent> settingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) startDrawWave();
            });

    private final ActivityResultLauncher<Intent> pickAudioLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Intent data = result.getData();
                if (result.getResultCode() != RESULT_OK || data == null) return;
                Uri uri = data.getData();
                Log.i(TAG, "pick audio result uri=" + uri);
                if (uri == null) return;
                String path = copyUriToFile(uri, queryDisplayName(uri));
                if (path != null) {
                    mWaveView.setVisibility(View.VISIBLE);
                    mWaveform.setVisibility(View.INVISIBLE);
                    mStatView.setVisibility(View.VISIBLE);
                    mZoomBar.setVisibility(View.GONE);
                    handler.sendMessage(handler.obtainMessage(MSG_UPDATE_STATUS, ""));
                    loadWaveFile(path);
                } else {
                    handler.sendMessage(handler.obtainMessage(MSG_UPDATE_STATUS, "Failed to read audio file"));
                }
            });

    private final ActivityResultLauncher<Intent> speechLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Intent data = result.getData();
                if (result.getResultCode() != RESULT_OK || data == null) return;
                ArrayList<String> recognized = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (recognized != null && !recognized.isEmpty()) {
                    mTvResult.setText(recognized.get(0));
                    // 有结果才占位：空着的时候把下面那块让给分贝图
                    if (mResultCard != null) mResultCard.setVisibility(View.VISIBLE);
                }
            });

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
        handler.removeCallbacksAndMessages(null);

        if (mWaveCanvas != null) {
            mWaveCanvas.setDecibelListener(null);
            if (mWaveCanvas.mIsRecording()) {
                mWaveCanvas.stopRecording();
            }
            mWaveCanvas = null;
        }

        // 中断文件加载线程
        mLoadingKeepGoing = false;
        if (mLoadSoundFileThread != null && mLoadSoundFileThread.isAlive()) {
            mLoadSoundFileThread.interrupt();
        }

        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer = null;
        }
    }
}
