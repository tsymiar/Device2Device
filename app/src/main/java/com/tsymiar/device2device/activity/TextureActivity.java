package com.tsymiar.device2device.activity;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.wrapper.ViewWrapper;

import java.io.File;
import java.util.Locale;

public class TextureActivity extends AppCompatActivity
        implements AdapterView.OnItemSelectedListener, TextureView.SurfaceTextureListener {
    private static final String TAG = TextureActivity.class.getCanonicalName();
    /**
     * Spinner for implementation selection.
     */
    private Spinner mSpinner = null;

    /**
     * Texture for drawing to.
     */
    private TextureView mTextureView;

    /**
     * Log text views.
     */
    private final static TextView[] mLog = new TextView[3];

    private final int EGL_TEXTURE_FILE = 1;
    private final int EGL_SURFACE_FILE = 2;
    private final int CPU_TEXTURE_FILE = 3;
    private final int CPU_SURFACE_FILE = 4;
    private final int DISCONNECT_WINDOW = 5;

    private int mDisplayHeight = 0;
    private int mDisplayWidth = 0;

    @SuppressLint("SdCardPath")
    public String DATA_DIRECTORY = Environment.getExternalStorageDirectory()
            // + "/Android/data/com.tsymiar.device2device"
            + "/files/cache/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_texture);
        InitViews();
        File file = getExternalFilesDir("cache");
        assert file != null;
        // DATA_DIRECTORY = file.getAbsolutePath() + "/";
        if (!file.exists()) {
            boolean status = file.mkdirs();
            if (!status) {
                log("make dirs fail");
            }
        }
    }

    protected void InitViews() {
        final ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.types,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpinner = findViewById(R.id.spinner);
        if (mSpinner != null) {
            mSpinner.setAdapter(adapter);
            mSpinner.setOnItemSelectedListener(this);
        }

        Button reload = findViewById(R.id.reload);
        if (reload != null) {
            reload.setOnClickListener(this::ReLoad);
        }

        mTextureView = findViewById(R.id.texture);
        if (mTextureView != null) {
            mTextureView.setSurfaceTextureListener(this);
            mTextureView.setOpaque(false);
        }

        mLog[0] = findViewById(R.id.log0);
        mLog[1] = findViewById(R.id.log1);
        mLog[2] = findViewById(R.id.log2);
    }

    @Override
    public void onItemSelected(@NonNull AdapterView<?> parent, @NonNull View view, int position, long id) {
        updateSurfaceView(position);
    }

    @Override
    public void onResume() {
        super.onResume();
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mDisplayHeight = metrics.heightPixels;
        mDisplayWidth = metrics.widthPixels;
        ViewWrapper.setRenderSize(mDisplayHeight, mDisplayWidth);
    }

    @Override
    public void onNothingSelected(@NonNull AdapterView<?> parent) {
        updateSurfaceView(0);
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, @IntRange(from = 1) int width,
            @IntRange(from = 1) int height) {
        log(String.format(Locale.ROOT, "Texture created (%d×%d)", width, height));
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, @IntRange(from = 1) int width,
            @IntRange(from = 1) int height) {
        /*
         * The surface remains valid so no reinitialization is needed but its
         * dimensions have changed. You may want to recalculate OpenGL matrix or
         * update buffer geometry here, or rather call native method that does
         * that.
         */
        ViewWrapper.setRenderSize(height, width);
        log(String.format(Locale.ROOT, "Texture resized (%d×%d)", width, height));
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        // De-initialize
        ViewWrapper.unloadSurfaceView();
        log("Texture destroy");
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        // Each draw updates the texture and logs on its own
        log("Texture update");
    }

    public void updateSurfaceView(@IntRange(from = 0) int item) {
        String[] selValue = getResources().getStringArray(R.array.types);
        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        if (texture == null) {
            return;
        }
        ViewWrapper.setRenderSize(mDisplayHeight, mDisplayWidth);
        int state = 0;
        String msg = "";
        switch (item) {
        case EGL_TEXTURE_FILE:
            ViewWrapper.setLocalFile(DATA_DIRECTORY + "test.jpg");
            state = ViewWrapper.updateEglTexture(texture);
            msg = selValue[item];
            break;
        case EGL_SURFACE_FILE:
            ViewWrapper.setLocalFile(DATA_DIRECTORY + "test.yuv");
            state = ViewWrapper.updateEglSurface(texture);
            msg = selValue[item];
            break;
        case CPU_TEXTURE_FILE:
            ViewWrapper.setLocalFile(DATA_DIRECTORY + "test.bmp");
            state = ViewWrapper.updateCpuTexture(texture, item);
            msg = selValue[item];
            break;
        case CPU_SURFACE_FILE:
            ViewWrapper.setLocalFile(DATA_DIRECTORY + "test.h264");
            state = ViewWrapper.updateCpuSurface(texture);
            msg = selValue[item];
            break;
        case DISCONNECT_WINDOW:
            mTextureView.setVisibility(View.GONE);
            mTextureView.setVisibility(View.VISIBLE);
            msg = selValue[item];
            break;
        default:
            msg = "Not implement: " + item;
            break;
        }
        log(String.format(Locale.ROOT, "(%s: item=%d stat=%d)", msg, item, state));
    }

    public void ReLoad(@NonNull View view) {
        updateSurfaceView(mSpinner.getSelectedItemPosition());
    }

    public static void log(@NonNull String message) {
        for (TextView textView : mLog) {
            textView.setTextSize(14);
        }
        mLog[2].setText(mLog[1].getText());
        mLog[1].setText(mLog[0].getText());
        mLog[0].setText(String.format(Locale.ROOT, "%s", message));
    }
}
