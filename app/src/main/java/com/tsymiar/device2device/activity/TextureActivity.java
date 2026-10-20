package com.tsymiar.device2device.activity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
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
import java.util.ArrayList;
import java.util.List;
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

    /** spinner 位置常量：static 才能在 sampleName() 这样的静态方法里用 */
    private static final int EGL_TEXTURE_FILE = 1;
    private static final int EGL_SURFACE_FILE = 2;
    private static final int CPU_TEXTURE_FILE = 3;
    private static final int CPU_SURFACE_FILE = 4;
    private static final int DISCONNECT_WINDOW = 5;
    /** 视频走 MediaCodec 硬解直出，不经过上面那几条 YUV/RGBA 通道 */
    private static final int VIDEO_MEDIACODEC = 6;

    /** 用户选择的文件路径 */
    private String mPickedPath = null;
    /** CPU 渲染所需的 BMP 临时文件路径 */
    private String mBmpPath = null;
    /** mBmpPath 对应的源文件：换一张图之后不能拿上一张的 BMP 顶着 */
    private String mBmpSrc = null;
    /** Surface 还没建好时先记下"待渲染"，onSurfaceTextureAvailable 里补一次 */
    private boolean mPendingRender = false;
    /** 视频播放期间持有的 Surface：解码器要用，播完/换文件才放 */
    private Surface mVideoSurface = null;
    private TextView mPathView = null;
    private RadioGroup mModeGroup = null;
    private int mRenderMode = MODE_AUTO;
    /** Spinner position → item-constant 映射，根据当前模式变化 */
    private int[] mItemMapping = {0, 1, 2, 3, 4, 6, 5};
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
        // 先量一次屏幕：进页面就选文件时，等 onResume 再设渲染尺寸已经晚了
        measureDisplay();
        initViews();
        flushPendingLog();
        log("点 Pick 选一张图片或一段视频；先在 spinner 里指定渲染方式，"
                + "停在 None 则按文件类型自动挑通道");
    }

    /** 取屏幕像素尺寸（getMetrics 已废弃但各版本通用，这里只是给渲染一个上限） */
    private void measureDisplay() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mDisplayHeight = metrics.heightPixels;
        mDisplayWidth = metrics.widthPixels;
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
                    mItemMapping = new int[]{0, 1, 2, 3, 4, 6, 5};
                    setSpinnerAdapter(mAdapterAuto);
                } else if (checkedId == R.id.mode_gpu) {
                    mRenderMode = MODE_GPU;
                    mItemMapping = new int[]{0, 1, 2, 6, 5};
                    setSpinnerAdapter(mAdapterGpu);
                } else if (checkedId == R.id.mode_cpu) {
                    mRenderMode = MODE_CPU;
                    mItemMapping = new int[]{0, 3, 4, 6, 5};
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
        // 菜单选了什么就渲染什么：spinner 是唯一入口，模式只影响可选项与失败回退
        renderWithMode();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 转屏回来尺寸会变；真正的渲染尺寸在 onSurfaceTextureAvailable 里还会用
        // 纹理实际大小再校正一次
        measureDisplay();
        ViewWrapper.setRenderSize(mDisplayHeight, mDisplayWidth);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 页面收摊后日志区不能再被写：置空，log() 会转成攒进 sPendingLog
        mLog[0] = null;
        mLog[1] = null;
        mLog[2] = null;
        // 页面没了，解码线程不能继续往已经销毁的 surface 上出帧
        stopVideo();
    }

    @Override
    public void onNothingSelected(@NonNull AdapterView<?> parent) {
        renderWithMode();
    }

    // ---------- SurfaceTextureListener ----------

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, @IntRange(from = 1) int width,
                                          @IntRange(from = 1) int height) {
        // 用纹理的实际尺寸而不是屏幕尺寸：TextureView 上面还压着控件，两者并不一样
        mDisplayHeight = height;
        mDisplayWidth = width;
        ViewWrapper.setRenderSize(height, width);
        log(String.format(Locale.ROOT, "Texture created (%d×%d)", width, height));
        // 进页面后马上选文件时，那一笔渲染请求落在了 surface 建好之前，这里补上
        if (mPendingRender) {
            mPendingRender = false;
            renderWithMode();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, @IntRange(from = 1) int width,
                                            @IntRange(from = 1) int height) {
        mDisplayHeight = height;
        mDisplayWidth = width;
        ViewWrapper.setRenderSize(height, width);
        log(String.format(Locale.ROOT, "Texture resized (%d×%d)", width, height));
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        // surface 没了，解码器再往里出帧就是往一个死掉的 window 上写
        stopVideo();
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
        // 图片走纹理/原始通道，视频走 MediaCodec 硬解，两类都放出来
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
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
                    // 换了文件：上一个视频还在播时先收掉，不然两个解码器抢同一个 surface
                    stopVideo();
                    mPickedPath = path;
                    // 清除旧的 BMP 缓存：换图之后不能拿上一张的转换结果顶着
                    if (mBmpPath != null) {
                        //noinspection ResultOfMethodCallIgnored
                        new File(mBmpPath).delete();
                        mBmpPath = null;
                        mBmpSrc = null;
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
            case "video/mp4":
            case "video/mp4v-es": return ".mp4";
            case "video/3gpp":
            case "video/3gpp2":  return ".3gp";
            case "video/x-matroska": return ".mkv";
            case "video/webm":   return ".webm";
            case "video/avi":
            case "video/x-msvideo": return ".avi";
            case "video/quicktime": return ".mov";
            default:            return null;
        }
    }

    // ---------- 核心渲染调度 ----------

    /** 当前 spinner 选中的菜单项（已按模式映射回 item 常量）；没选 / 选了 None 时为 0 */
    private int selectedItem() {
        if (mSpinner == null) return 0;
        int pos = mSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= mItemMapping.length) return 0;
        return mItemMapping[pos];
    }

    /**
     * 菜单停在「None」时的自动路由。
     * 视频走 MediaCodec；图片走纹理通道，其余（原始 yuv / rgba）走 surface 通道；
     * Auto / GPU 模式下非视频的走 EGL，CPU 模式下走 CPU。
     */
    private int autoItem(boolean isImage, boolean isVideo) {
        if (isVideo) {
            return VIDEO_MEDIACODEC;
        }
        if (mRenderMode == MODE_CPU) {
            return isImage ? CPU_TEXTURE_FILE : CPU_SURFACE_FILE;
        }
        return isImage ? EGL_TEXTURE_FILE : EGL_SURFACE_FILE;
    }

    /** 同一渲染家族里的另一条通道：菜单选的与文件类型对不上时改走它 */
    private static int siblingItem(int item) {
        switch (item) {
            case EGL_TEXTURE_FILE:  return EGL_SURFACE_FILE;
            case EGL_SURFACE_FILE:  return EGL_TEXTURE_FILE;
            case CPU_TEXTURE_FILE:  return CPU_SURFACE_FILE;
            case CPU_SURFACE_FILE:  return CPU_TEXTURE_FILE;
            default:                return item;
        }
    }

    private static boolean isTextureItem(int item) {
        return item == EGL_TEXTURE_FILE || item == CPU_TEXTURE_FILE;
    }

    /** 菜单项 → 名字，日志里用（与 strings.xml 的 types 数组一一对应） */
    private static String itemName(int item) {
        switch (item) {
            case EGL_TEXTURE_FILE:  return "GPU-OpenGL";
            case EGL_SURFACE_FILE:  return "GPU-Surface";
            case CPU_TEXTURE_FILE:  return "CPU-image";
            case CPU_SURFACE_FILE:  return "CPU-video";
            case DISCONNECT_WINDOW: return "Disconnect";
            case VIDEO_MEDIACODEC:  return "Video";
            default:                return "None";
        }
    }

    /**
     * 渲染调度：菜单说什么就做什么。
     *
     * spinner 是唯一入口：选了具体项就走那一项，停在 None 才由 autoItem()
     * 按文件类型自动挑一条通道（Auto 模式下 GPU 失败还会退到 CPU）。
     * 菜单选了什么就走什么，行为不对着菜单走会让人看不懂。
     */
    private void renderWithMode() {
        if (mTextureView == null) return;
        String inputPath = getInputPath(null);
        if (inputPath == null) {
            log("没有选中文件：先点 Pick 选一张图片 / 一段视频");
            return;
        }
        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        if (texture == null) {
            // TextureView 的 SurfaceTexture 是异步建好的：刚进页面就选文件时这里还是 null。
            // 记下来等 surface 就绪补渲染，直接 return 时就是"选了文件什么都没发生"。
            mPendingRender = true;
            log("等待 Surface 就绪…");
            return;
        }

        boolean isImage = isImageFile(inputPath);
        boolean isVideo = !isImage && isVideoFile(inputPath);
        int item = selectedItem();
        if (item == DISCONNECT_WINDOW) {
            doDisconnect();
            return;
        }
        if (item == 0) {
            item = autoItem(isImage, isVideo);
        } else if (item != VIDEO_MEDIACODEC && isVideo) {
            log(itemName(item) + " 需要图片或原始数据，当前是视频 → 改走 Video");
            item = VIDEO_MEDIACODEC;
        } else if (item == VIDEO_MEDIACODEC && !isVideo) {
            log("Video 需要视频文件，当前是" + (isImage ? "图片" : "原始数据")
                    + " → 改走 " + itemName(autoItem(isImage, false)));
            item = autoItem(isImage, false);
        } else if (isTextureItem(item) != isImage) {
            int fixed = siblingItem(item);
            log(itemName(item) + " 需要" + (isTextureItem(item) ? "图片" : "原始数据")
                    + "，当前是" + (isImage ? "图片" : "原始数据") + " → 改走 " + itemName(fixed));
            item = fixed;
        }
        renderItem(item, inputPath, texture);
    }

    /** 按菜单项走对应的 native 通道 */
    private void renderItem(int item, String path, SurfaceTexture texture) {
        ViewWrapper.setRenderSize(mDisplayHeight, mDisplayWidth);
        int state;
        String label = itemName(item);
        if (item != VIDEO_MEDIACODEC) {
            // 视频还占着 surface 时，其它通道是画不上去的
            stopVideo();
        }

        switch (item) {
            case VIDEO_MEDIACODEC: {
                if (!checkFileValid(path, label)) return;
                state = startVideo(texture, path);
                break;
            }
            case EGL_TEXTURE_FILE: {
                if (!checkFileValid(path, label)) return;
                // cpp 那边的图片解码器只认 BMP（bitmap.c），jpg/png 先转一手再交给它
                String bmpFile = ensureBmpCopy(path);
                if (bmpFile == null) {
                    log("BMP conversion failed");
                    return;
                }
                ViewWrapper.setLocalFile(bmpFile);
                state = ViewWrapper.updateEglTexture(texture);
                if (state < 0 && mRenderMode == MODE_AUTO) {
                    // Auto 的意义就在这一下：GPU 通道起不来就退到 CPU，别让用户对着黑屏
                    state = ViewWrapper.updateCpuTexture(texture, CPU_TEXTURE_FILE);
                    label = "Auto→CPU-image";
                }
                break;
            }
            case EGL_SURFACE_FILE: {
                if (!checkFileValid(path, label)) return;
                ViewWrapper.setLocalFile(path);
                state = ViewWrapper.updateEglSurface(texture);
                if (state < 0 && mRenderMode == MODE_AUTO) {
                    state = ViewWrapper.updateCpuSurface(texture);
                    label = "Auto→CPU-video";
                }
                break;
            }
            case CPU_TEXTURE_FILE: {
                if (!checkFileValid(path, label)) return;
                String bmpFile = ensureBmpCopy(path);
                if (bmpFile == null) {
                    log("BMP conversion failed");
                    return;
                }
                ViewWrapper.setLocalFile(bmpFile);
                state = ViewWrapper.updateCpuTexture(texture, CPU_TEXTURE_FILE);
                break;
            }
            case CPU_SURFACE_FILE: {
                if (!checkFileValid(path, label)) return;
                ViewWrapper.setLocalFile(path);
                state = ViewWrapper.updateCpuSurface(texture);
                break;
            }
            default:
                log("已选 None：画面保持当前内容（渲染请先 Pick 一个文件）");
                return;
        }
        log(String.format(Locale.ROOT, "[%s] %s → stat=%d",
                label, new File(path).getName(), state));
    }

    /**
     * 起视频播放。
     *
     * MediaCodec 是往 surface 里直接出帧的，EGL / CPU 通道还挂在同一个 SurfaceTexture 上
     * 时两边会抢缓冲区，所以先把 CPU 那侧的 window 收掉，再新建一个 Surface 交出去。
     */
    private int startVideo(SurfaceTexture texture, String path) {
        stopVideo();
        ViewWrapper.unloadSurfaceView();
        mVideoSurface = new Surface(texture);
        int ret = ViewWrapper.startVideoRender(mVideoSurface, path);
        if (ret < 0) {
            mVideoSurface.release();
            mVideoSurface = null;
        }
        return ret;
    }

    /** 停视频播放并放掉 Surface（不管当前有没有在播） */
    private void stopVideo() {
        ViewWrapper.stopVideoRender();
        if (mVideoSurface != null) {
            mVideoSurface.release();
            mVideoSurface = null;
        }
    }

    /** 断开 / 重连窗口：先释放 native 侧的 window，再让 TextureView 重建 surface */
    private void doDisconnect() {
        stopVideo();
        ViewWrapper.unloadSurfaceView();
        if (mTextureView == null) {
            log("Disconnect: 没有 TextureView");
            return;
        }
        ViewParent parent = mTextureView.getParent();
        if (!(parent instanceof ViewGroup)) {
            log("Disconnect: TextureView 没有父容器，无法重建");
            return;
        }
        /**
         * 摘下来再挂回去，这才是真的"断开再连上"：TextureView 从窗口移除会走
         * onSurfaceTextureDestroyed（返回 true，旧的 SurfaceTexture 被释放），
         * 重新挂上再走 onSurfaceTextureAvailable 拿一张新的。
         *
         * 只切 GONE → VISIBLE 是不行的：同一帧里来回切，不会触发上面那两个回调，
         * 渲染窗口从头到尾没动过，等于什么都没做。
         */
        /**
         * Disconnect 是一次性动作，不是一种渲染方式：先把菜单静默拨回 None。
         * 不拨时菜单停在这一项，窗口重建后 onSurfaceTextureAvailable →
         * renderWithMode() 又会读到 Disconnect 再断一次 —— 无限递归。
         */
        if (mSpinner != null) {
            mSpinner.setOnItemSelectedListener(null);
            mSpinner.setSelection(0);
            mSpinner.setOnItemSelectedListener(this);
        }
        ViewGroup group = (ViewGroup) parent;
        int index = group.indexOfChild(mTextureView);
        // 重建后按当前文件再画一次，否则窗口是空的，看不出断没断
        mPendingRender = mPickedPath != null;
        group.removeView(mTextureView);
        group.addView(mTextureView, index);
        log("Disconnect: 渲染窗口已断开，等待重建");
    }

    /** 获取输入文件路径（只有用户 Pick 的这一个来源） */
    private String getInputPath(String defaultName) {
        if (mPickedPath != null && new File(mPickedPath).exists()) {
            return mPickedPath;
        }
        return null;
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

        // 已缓存：必须是同一张源图的缓存，换了图就得重转
        if (mBmpPath != null && srcPath.equals(mBmpSrc) && new File(mBmpPath).exists()) {
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
            mBmpSrc = srcPath;
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

    /** 判断文件是否为视频（先按扩展名，失败时检测文件头） */
    private static boolean isVideoFile(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".3gp")
                || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".avi")
                || lower.endsWith(".mov")) {
            return true;
        }
        return checkVideoMagic(path);
    }

    /**
     * 检测文件头：mp4 / mov / m4v / 3gp 这些 ISO-BMFF 容器都是
     * 4 字节长度 + "ftyp" + 品牌，靠偏移 4 的 ftyp 基本能认出来。
     */
    private static boolean checkVideoMagic(String path) {
        try {
            byte[] header = new byte[12];
            int n;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(path)) {
                n = fis.read(header);
            }
            if (n < 12) return false;
            return header[4] == 0x66 && header[5] == 0x74 && header[6] == 0x79
                    && header[7] == 0x70;   // "ftyp"
        } catch (Exception ignored) { }
        return false;
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

    // ---------- 日志 ----------

    /**
     * 本 Activity 建好之前攒下来的日志。
     *
     * 日志区是静态的（native 侧的日志由 SelectActivity 转过来），那些日志可能在
     * TextureActivity 还没 onCreate 时就到 —— 那时 mLog 里全是 null，直接写就是 NPE。
     */
    private static final List<String> sPendingLog = new ArrayList<>();

    public static void log(@NonNull String message) {
        if (mLog[0] == null || mLog[1] == null || mLog[2] == null) {
            sPendingLog.add(message);
            if (sPendingLog.size() > 3) {
                sPendingLog.remove(0);
            }
            return;
        }
        for (TextView textView : mLog) {
            textView.setTextSize(14);
        }
        mLog[2].setText(mLog[1].getText());
        mLog[1].setText(mLog[0].getText());
        mLog[0].setText(String.format(Locale.ROOT, "%s", message));
    }

    /** 把建好之前攒下的日志补进日志区：倒着放，最新的那条才在最上面 */
    private void flushPendingLog() {
        if (mLog[0] == null) return;
        for (int i = sPendingLog.size() - 1; i >= 0; i--) {
            log(sPendingLog.get(i));
        }
        sPendingLog.clear();
    }
}
