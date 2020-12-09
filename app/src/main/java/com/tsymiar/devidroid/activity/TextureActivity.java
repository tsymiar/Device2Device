package com.tsymiar.devidroid.activity;

import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Environment;
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

import com.tsymiar.devidroid.R;
import com.tsymiar.devidroid.wrapper.TimeWrapper;
import com.tsymiar.devidroid.wrapper.ViewWrapper;

import java.util.Locale;

public class TextureActivity extends AppCompatActivity implements AdapterView.OnItemSelectedListener,
        TextureView.SurfaceTextureListener {
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
    private final TextView[] mLog = new TextView[3];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_texture);
        InitViews();
    }

    protected void InitViews() {
        super.onResume();
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
    public void onItemSelected(@NonNull AdapterView<?> parent,
                               @NonNull View view,
                               int position,
                               long id) {
        updateSurfaceView(position);
    }

    @Override
    public void onNothingSelected(@NonNull AdapterView<?> parent) {
        updateSurfaceView(0);
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface,
                                          @IntRange(from = 1) int width,
                                          @IntRange(from = 1) int height) {
        log(String.format(Locale.ROOT, "Texture created (%d×%d)", width, height));
        final int position = mSpinner.getSelectedItemPosition();
        if (position >= 0)
            updateSurfaceView(position);
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface,
                                            @IntRange(from = 1) int width,
                                            @IntRange(from = 1) int height) {
        log(String.format(Locale.ROOT, "Texture resized (%d×%d)", width, height));
        /* The surface remains valid so no reinitialization is needed but its dimensions have
         * changed. You may want to recalculate OpenGL matrix or update buffer geometry here,
         * or rather call native method that does that. */
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        log("Texture destroyed");
        // De-initialize
        updateSurfaceView(0);
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        // Each draw updates the texture and logs on its own
    }

    public final String DATA_DIRECTORY = Environment.getExternalStorageDirectory()
            + "/Android/data/" + "com.tsymiar.devidroid" + "/cache/";

    public void updateSurfaceView(@IntRange(from = 0) int item) {
        final SurfaceTexture texture = mTextureView.getSurfaceTexture();
        String[] selValue = getResources().getStringArray(R.array.types);
        log(String.format(Locale.ROOT, "updateSurfaceView (%d, %s)", item, selValue[item]));
        if (texture != null && item != 2) {
            ViewWrapper.updateSurfaceView(texture, item);
        }
        if (item == 2) {
            ViewWrapper.updateEglSurface(texture, DATA_DIRECTORY + "test.h264");
        }
    }

    public void ReLoad(@NonNull View view) {
        updateSurfaceView(mSpinner.getSelectedItemPosition());
    }

    private void log(@NonNull String message) {
        for (TextView textView : mLog) {
            textView.setTextSize(14);
        }
        mLog[2].setText(mLog[1].getText());
        mLog[1].setText(mLog[0].getText());
        mLog[0].setText(String.format(Locale.ROOT, "%s [%d]", message, TimeWrapper.getAbsoluteTimestamp()));
    }
}
