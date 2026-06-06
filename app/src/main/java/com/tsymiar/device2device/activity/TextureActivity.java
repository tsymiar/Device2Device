package com.tsymiar.device2device.activity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.wrapper.ViewWrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

public class TextureActivity extends AppCompatActivity
        implements AdapterView.OnItemSelectedListener, TextureView.SurfaceTextureListener {
    private static final int REQUEST_CODE_PICK_FILE = 200;

    /** 渲染模式 */
    private static final int MODE_AUTO = 0;
    private static final int MODE_GPU  = 1;
    private static final int MODE_CPU  = 2;

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

    /** 用户选择的文件路径 */
    private String mPickedPath = null;
    /** CPU 渲染所需的 BMP 临时文件路径 */
    private String mBmpPath = null;
    private TextView mPathView = null;
    private RadioGroup mModeGroup = null;
    private int mRenderMode = MODE_AUTO;
    /** Spinner position → item-constant 映射，根据当前模式变化 */
    private int[] mItemMapping = {0, 1, 2, 3, 4, 5};
    private ArrayAdapter<CharSequence> mAdapterAuto, mAdapterGpu, mAdapterCpu;

    private int mDisplayHeight = 0;
    private int mDisplayWidth = 0;

    public String mDataDirectory = null;

    /** 确保 mDataDirectory 存在 */
    private boolean ensureDataDir() {
        if (mDataDirectory == null) {
            File cacheDir = getExternalFilesDir("cache");
            if (cacheDir == null) return false;
            if (!cacheDir.exists() && !cacheDir.mkdirs()) return false;
            mDataDirectory = cacheDir.getAbsolutePath() + "/";
        }
        return new File(mDataDirectory).exists() || new File(mDataDirectory).mkdirs();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_texture);
        ensureDataDir();
        initViews();
    }

    protected void initViews() {
        mAdapterAuto = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        mAdapterAuto.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mAdapterGpu  = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        mAdapterGpu.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mAdapterCpu  = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        mAdapterCpu.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        for (String s : getResources().getStringArray(R.array.types))     mAdapterAuto.add(s);
        for (String s : getResources().getStringArray(R.array.types_gpu)) mAdapterGpu.add(s);
        for (String s : getResources().getStringArray(R.array.types_cpu)) mAdapterCpu.add(s);

        mSpinner = findViewById(R.id.spinner);
        if (mSpinner != null) {
            mSpinner.setAdapter(mAdapterAuto);
            mSpinner.setOnItemSelectedListener(this);
        }

        Button reload = findViewById(R.id.reload);
        if (reload != null) {
            reload.setOnClickListener(this::reload);
        }

        Button pick = findViewById(R.id.pick);
        if (pick != null) {
            pick.setOnClickListener(v -> openFilePicker());
        }

        mModeGroup = findViewById(R.id.mode_group);
        if (mModeGroup != null) {
            mModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.mode_auto) {
                    mRenderMode = MODE_AUTO;
                    mItemMapping = new int[]{0, 1, 2, 3, 4, 5};
                    setSpinnerAdapter(mAdapterAuto);
                } else if (checkedId == R.id.mode_gpu) {
                    mRenderMode = MODE_GPU;
                    mItemMapping = new int[]{0, 1, 2, 5};
                    setSpinnerAdapter(mAdapterGpu);
                } else if (checkedId == R.id.mode_cpu) {
                    mRenderMode = MODE_CPU;
                    mItemMapping = new int[]{0, 3, 4, 5};
                    setSpinnerAdapter(mAdapterCpu);
                }
                renderWithMode();
            });
        }

        mPathView = findViewById(R.id.file_path);

        mTextureView = findViewById(R.id.texture);
        if (mTextureView != null) {
            mTextureView.setSurfaceTextureListener(this);
            mTextureView.setOpaque(false);
        }

        mLog[0] = findViewById(R.id.log0);
        mLog[1] = findViewById(R.id.log1);
        mLog[2] = findViewById(R.id.log2);
    }

    /** 切换 Spinner 适配器，保持选择不触发回调 */
    private void setSpinnerAdapter(ArrayAdapter<CharSequence> adapter) {
        if (mSpinner == null) return;
        mSpinner.setOnItemSelectedListener(null);
        mSpinner.setAdapter(adapter);
        mSpinner.setSelection(0);
        mSpinner.setOnItemSelectedListener(this);
    }

    @Override
    public void onItemSelected(@NonNull AdapterView<?> parent, @NonNull View view, int position, long id) {
        int item = (position >= 0 && position < mItemMapping.length) ? mItemMapping[position] : 0;
        if (mRenderMode == MODE_AUTO) {
            if (mPickedPath != null) {
                renderWithMode();
            } else {
                updateSurfaceView(item);
            }
        } else {
            renderWithMode();
        }
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
        if (mRenderMode == MODE_AUTO) {
            updateSurfaceView(0);
        }
    }

    // ---------- SurfaceTextureListener ----------

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, @IntRange(from = 1) int width,
                                          @IntRange(from = 1) int height) {
        log(String.format(Locale.ROOT, "Texture created (%d×%d)", width, height));
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, @IntRange(from = 1) int width,
                                            @IntRange(from = 1) int height) {
        ViewWrapper.setRenderSize(height, width);
        log(String.format(Locale.ROOT, "Texture resized (%d×%d)", width, height));
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        ViewWrapper.unloadSurfaceView();
        log("Texture destroy");
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        log("Texture update");
    }

    // ---------- 文件选择器 ----------

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String path = copyUriToFile(uri);
                if (path != null) {
                    mPickedPath = path;
                    // 清除旧的 BMP 缓存
                    if (mBmpPath != null) {
                        //noinspection ResultOfMethodCallIgnored
                        new File(mBmpPath).delete();
                        mBmpPath = null;
                    }
                    if (mPathView != null) {
                        mPathView.setText(mPickedPath);
                        mPathView.setSelected(true); // 启用走马灯滚动
                    }
                    log("picked: " + new File(mPickedPath).getName());
                    // 选取后立即按当前模式渲染
                    renderWithMode();
                } else {
                    log("Failed to read file");
                }
            }
        }
    }

    private String copyUriToFile(Uri uri) {
        try {
            String name = uri.getLastPathSegment();
            if (name == null || name.isEmpty()) name = "picked_file";
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);

            // 从 MIME 类型获取正确扩展名，避免文件名无扩展名导致 isImageFile 误判
            String mime = getContentResolver().getType(uri);
            String ext = mimeToExtension(mime);
            if (ext != null && !name.toLowerCase(Locale.ROOT).endsWith(ext)) {
                int dot = name.lastIndexOf('.');
                if (dot < 0) {
                    name = name + ext;  // 无扩展名 → 追加
                }
            }

            if (!ensureDataDir()) {
                log("copyUri: cannot create data dir");
                return null;
            }
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) {
                log("copyUri: openInputStream returned null");
                return null;
            }
            File outFile = new File(mDataDirectory, name);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
            }
            is.close();
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            log("copyUri error: " + e.getMessage());
            return null;
        }
    }

    /** MIME 类型 → 文件扩展名 */
    private static String mimeToExtension(String mime) {
        if (mime == null) return null;
        switch (mime) {
            case "image/jpeg":  return ".jpg";
            case "image/png":   return ".png";
            case "image/bmp":
            case "image/x-bmp": return ".bmp";
            case "image/gif":   return ".gif";
            case "image/webp":  return ".webp";
            default:            return null;
        }
    }

    // ---------- 核心渲染调度 ----------

    /**
     * 根据当前模式（CPU / GPU / Auto）调度渲染。
     * CPU 模式：图片 → 转 BMP → updateCpuTexture；视频 → updateCpuSurface
     * GPU 模式：图片 → updateEglTexture；视频 → updateEglSurface
     * Auto 模式：走 spinner 选择的路径
     */
    private void renderWithMode() {
        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        if (texture == null) return;

        String inputPath = getInputPath(null);
        if (inputPath == null) {
            log("No file selected, just return.");
            return;
        }

        boolean isImage = isImageFile(inputPath);
        ViewWrapper.setRenderSize(mDisplayHeight, mDisplayWidth);
        int state = 0;
        String label;

        switch (mRenderMode) {
            case MODE_GPU:
                if (!checkFileValid(inputPath, "GPU")) break;
                ViewWrapper.setLocalFile(inputPath);
                if (isImage) {
                    state = ViewWrapper.updateEglTexture(texture);
                    label = "GPU-OpenGL (image)";
                } else {
                    state = ViewWrapper.updateEglSurface(texture);
                    label = "GPU-surface (video)";
                }
                break;

            case MODE_CPU:
                if (isImage) {
                    // CPU 渲染图片需要 BMP 格式，自动转换
                    String bmpFile = ensureBmpCopy(inputPath);
                    if (bmpFile == null) {
                        log("BMP conversion failed");
                        return;
                    }
                    ViewWrapper.setLocalFile(bmpFile);
                    state = ViewWrapper.updateCpuTexture(texture, CPU_TEXTURE_FILE);
                    label = "CPU-image (BMP)";
                } else {
                    if (!checkFileValid(inputPath, "CPU")) break;
                    ViewWrapper.setLocalFile(inputPath);
                    state = ViewWrapper.updateCpuSurface(texture);
                    label = "CPU-video";
                }
                break;

            case MODE_AUTO:
            default:
                // Auto 模式：有用户文件时按类型路由；无则回退到 spinner 预设
                if (mPickedPath != null) {
                    if (!checkFileValid(inputPath, "Auto")) return;
                    ViewWrapper.setLocalFile(inputPath);
                    if (isImage) {
                        state = ViewWrapper.updateEglTexture(texture);
                        label = "Auto-EGL (image)";
                    } else {
                        state = ViewWrapper.updateEglSurface(texture);
                        label = "Auto-EGL (video)";
                    }
                    break;
                }
                if (mSpinner != null) {
                    int pos = mSpinner.getSelectedItemPosition();
                    updateSurfaceView((pos >= 0 && pos < mItemMapping.length) ? mItemMapping[pos] : 0);
                }
                return;
        }
        log(String.format(Locale.ROOT, "[%s] %s → stat=%d",
                modeName(), new File(inputPath).getName(), state));
    }

    /** 获取输入文件路径（优先用户选择），defaultName 为 null 时只返回用户选择 */
    private String getInputPath(String defaultName) {
        if (mPickedPath != null && new File(mPickedPath).exists()) {
            return mPickedPath;
        }
        if (defaultName != null) {
            String def = mDataDirectory + defaultName;
            if (new File(def).exists()) return def;
        }
        return null;
    }

    // ---------- Auto 模式的 spinner 路由 ----------

    public void updateSurfaceView(@IntRange(from = 0) int item) {
        String[] selValue = getResources().getStringArray(R.array.types);
        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        if (texture == null) return;

        ViewWrapper.setRenderSize(mDisplayHeight, mDisplayWidth);
        int state = 0;
        String msg = null;

        switch (item) {
            case EGL_TEXTURE_FILE: {
                String filePath = resolvePath("test.jpg");
                if (!checkFileValid(filePath, "EGL_TEXTURE")) break;
                ViewWrapper.setLocalFile(filePath);
                state = ViewWrapper.updateEglTexture(texture);
                msg = selValue[item];
                break;
            }
            case EGL_SURFACE_FILE: {
                String filePath = resolvePath("test.yuv");
                if (!checkFileValid(filePath, "EGL_SURFACE")) break;
                ViewWrapper.setLocalFile(filePath);
                state = ViewWrapper.updateEglSurface(texture);
                msg = selValue[item];
                break;
            }
            case CPU_TEXTURE_FILE: {
                String filePath = resolvePath("test.bmp");
                if (!checkFileValid(filePath, "CPU_TEXTURE")) break;
                ViewWrapper.setLocalFile(filePath);
                state = ViewWrapper.updateCpuTexture(texture, item);
                msg = selValue[item];
                break;
            }
            case CPU_SURFACE_FILE: {
                String filePath = resolvePath("test.h264");
                if (!checkFileValid(filePath, "CPU_SURFACE")) break;
                ViewWrapper.setLocalFile(filePath);
                state = ViewWrapper.updateCpuSurface(texture);
                msg = selValue[item];
                break;
            }
            case DISCONNECT_WINDOW:
                mTextureView.setVisibility(View.GONE);
                mTextureView.setVisibility(View.VISIBLE);
                msg = selValue[item];
                break;
            default:
                msg = (item == 0) ? selValue[item] : "Not implement: " + item;
                break;
        }
        log(String.format(Locale.ROOT, "(%s: item=%d stat=%d)", msg, item, state));
    }

    /** 校验文件存在且非空，避免传无效路径导致 native 崩溃 */
    private boolean checkFileValid(String path, String label) {
        if (path == null) {
            log(label + ": path is null");
            return false;
        }
        File f = new File(path);
        if (!f.exists()) {
            log(label + ": file not found");
            return false;
        }
        if (f.length() == 0) {
            log(label + ": file is empty");
            return false;
        }
        return true;
    }

    /** 优先返回用户选择的路径，否则用默认文件 */
    private String resolvePath(String defaultName) {
        if (mPickedPath != null && new File(mPickedPath).exists()) {
            return mPickedPath;
        }
        return mDataDirectory + defaultName;
    }

    public void reload(@NonNull View view) {
        renderWithMode();
    }

    // ---------- 图片 → BMP 转换 ----------

    /**
     * 将用户选取的图片转为 BMP 格式（用于 CPU 渲染）。
     * 转换结果缓存在 mBmpPath 中，重复调用不会重复转换。
     */
    private String ensureBmpCopy(String srcPath) {
        if (srcPath == null) return null;

        // 如果已是 BMP，直接使用
        if (srcPath.toLowerCase(Locale.ROOT).endsWith(".bmp")) {
            return srcPath;
        }

        // 已缓存
        if (mBmpPath != null && new File(mBmpPath).exists()) {
            return mBmpPath;
        }

        try {
            Bitmap bitmap = BitmapFactory.decodeFile(srcPath);
            if (bitmap == null) return null;

            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

            mBmpPath = mDataDirectory + "cpu_render.bmp";
            try (FileOutputStream fos = new FileOutputStream(mBmpPath)) {
                // BMP 文件头 (14 bytes) + 信息头 (40 bytes) + 像素数据
                int rowBytes = (w * 3 + 3) & ~3;  // 每行对齐到 4 字节
                int pixelDataSize = rowBytes * h;
                int fileSize = 14 + 40 + pixelDataSize;

                // BITMAPFILEHEADER (14 bytes)
                fos.write(new byte[]{'B', 'M'});               // bfType
                fos.write(intToLE(fileSize));                   // bfSize
                fos.write(new byte[]{0, 0, 0, 0});             // bfReserved
                fos.write(intToLE(14 + 40));                    // bfOffBits

                // BITMAPINFOHEADER (40 bytes)
                fos.write(intToLE(40));                         // biSize
                fos.write(intToLE(w));                          // biWidth
                fos.write(intToLE(h));                          // biHeight
                fos.write(shortToLE(1));                        // biPlanes
                fos.write(shortToLE(24));                       // biBitCount
                fos.write(intToLE(0));                          // biCompression (BI_RGB)
                fos.write(intToLE(pixelDataSize));              // biSizeImage
                fos.write(intToLE(0));                          // biXPelsPerMeter
                fos.write(intToLE(0));                          // biYPelsPerMeter
                fos.write(intToLE(0));                          // biClrUsed
                fos.write(intToLE(0));                          // biClrImportant

                // 像素数据（BMP 从下到上，BGR 顺序）
                byte[] row = new byte[rowBytes];
                for (int y = h - 1; y >= 0; y--) {
                    for (int x = 0; x < w; x++) {
                        int c = pixels[y * w + x];
                        row[x * 3]     = (byte) (c & 0xFF);        // B
                        row[x * 3 + 1] = (byte) ((c >> 8) & 0xFF); // G
                        row[x * 3 + 2] = (byte) ((c >> 16) & 0xFF);// R
                    }
                    fos.write(row, 0, rowBytes);
                }
            }
            bitmap.recycle();
            return mBmpPath;
        } catch (Exception e) {
            log("BMP convert error: " + e.getMessage());
            return null;
        }
    }

    private static byte[] intToLE(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] shortToLE(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }

    /** 判断文件是否为图片（先按扩展名，失败时检测文件头 magic bytes） */
    private static boolean isImageFile(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png")  || lower.endsWith(".bmp")
                || lower.endsWith(".gif")  || lower.endsWith(".webp")) {
            return true;
        }
        // 扩展名不可靠时检测文件头
        return checkImageMagic(path);
    }

    /** 检测文件头 magic bytes 是否为常见图片格式 */
    private static boolean checkImageMagic(String path) {
        try {
            byte[] header = new byte[12];
            int n;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(path)) {
                n = fis.read(header);
            }
            if (n < 4) return false;
            // JPEG: FF D8 FF
            if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8
                    && (header[2] & 0xFF) == 0xFF) return true;
            // PNG:  89 50 4E 47
            if (header[0] == (byte)0x89 && header[1] == 0x50
                    && header[2] == 0x4E && header[3] == 0x47) return true;
            // BMP:  42 4D (BM)
            if (header[0] == 0x42 && header[1] == 0x4D) return true;
            // GIF:  47 49 46 38 (GIF8)
            if (header[0] == 0x47 && header[1] == 0x49
                    && header[2] == 0x46 && header[3] == 0x38) return true;
            // WebP: 52 49 46 46 ... 57 45 42 50 (RIFF....WEBP)
            if (header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x46
                    && n >= 12 && header[8] == 0x57 && header[9] == 0x45
                    && header[10] == 0x42 && header[11] == 0x50) return true;
        } catch (Exception ignored) { }
        return false;
    }

    /** 当前渲染模式的名称 */
    private String modeName() {
        switch (mRenderMode) {
            case MODE_GPU:  return "GPU";
            case MODE_CPU:  return "CPU";
            default:        return "Auto";
        }
    }

    // ---------- 日志 ----------

    public static void log(@NonNull String message) {
        for (TextView textView : mLog) {
            textView.setTextSize(14);
        }
        mLog[2].setText(mLog[1].getText());
        mLog[1].setText(mLog[0].getText());
        mLog[0].setText(String.format(Locale.ROOT, "%s", message));
    }
}
