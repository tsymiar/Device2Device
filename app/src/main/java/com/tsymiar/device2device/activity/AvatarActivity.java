package com.tsymiar.device2device.activity;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.avatar.AnnyModel;
import com.tsymiar.device2device.avatar.BodyProfile;
import com.tsymiar.device2device.avatar.GlbLoader;
import com.tsymiar.device2device.avatar.HumanMesh;
import com.tsymiar.device2device.avatar.ObjLoader;
import com.tsymiar.device2device.avatar.TripoClient;
import com.tsymiar.device2device.avatar.PhotoAnalyzer;
import com.tsymiar.device2device.avatar.AvatarSurfaceView;

import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 人物 3D 模型页面：照片 / 拍照 → 形象推断 → 参数微调 → 等身比例 3D 模型。
 *
 * 生成链路：
 *   照片(或拍照) ──抠图取轮廓(肩/腰/臀比例)＋分区取色(发/肤/上装/下装)──┐
 *   身高 / 体重 / 头身比 / 脸型 / 发型 ─────────────────────────────────┴→ 参数化建模 → OpenGL 预览
 *
 * 建模引擎两套（进入页面前选择，页面内也能切换）：
 *   · 原生：SDF 隐式曲面 + SurfaceNets 等值面提取，身体与五官全部程序化生成；
 *   · 高分：Anny（NAVER LABS，基于 MakeHuman 社区人体测量数据的参数化人体模型）
 *           的原型形变基替换掉程序化生成的 3D 模型，其余流程不变。
 *
 * 模型在米制坐标里 1:1 生成（脚底 y=0，头顶 y=身高），预览区带地面网格与身高标尺，
 * 可直接导出 OBJ(+mtl) 或保存预览图。
 */
public class AvatarActivity extends AppCompatActivity {

    private static final String TAG = "AvatarActivity";
    private static final String PREF = "avatar_prefs";

    /** 进入页面时指定的建模引擎 */
    public static final String EXTRA_ENGINE = "avatar_engine";
    /** 原生实现：隐式曲面 + 等值面提取 */
    public static final int ENGINE_NATIVE = 0;
    /** 高分实现：Anny（MakeHuman 人体测量形变基） */
    public static final int ENGINE_ANNY = 1;
    private static final int REQ_PICK = 9101;
    private static final int REQ_SHOT = 9102;
    private static final int REQ_CAMERA_PERM = 9103;
    private static final int REQ_OBJ = 9104;
    private static final int REQ_ANNY = 9105;

    private static final int C_BG = 0xFF0E1116;
    private static final int C_FIELD = 0xFF151A21;
    private static final int C_STROKE = 0xFF3C4A66;
    private static final int C_PRESSED = 0xFF223040;
    private static final int C_PRIMARY = 0xFF7E57C2;
    private static final int C_PRIMARY_PRESSED = 0xFF5E35B1;
    private static final int C_TEXT = 0xFFE8EAED;
    private static final int C_SUB = 0xFF9AA4B2;

    private static final int[] PALETTE = {
            0xFFF6DCC4, 0xFFE7BE9C, 0xFFD9A176, 0xFFC1885C, 0xFFA06A44, 0xFF7A4A2A,
            0xFF1B1B1B, 0xFF33281E, 0xFF5A3B21, 0xFF8C6239, 0xFFC9A227, 0xFFE0E0E0,
            0xFF2F4F8F, 0xFF3E6DB4, 0xFF4CAF50, 0xFF00897B, 0xFFFF9800, 0xFFE53935,
            0xFF9C27B0, 0xFF37474F, 0xFF795548, 0xFF212121, 0xFFF5F5F5, 0xFF8E3B3B,
    };

    private final BodyProfile mProfile = new BodyProfile();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** 建模线程池：单线程，保证拖动滑块时只保留最后一次结果 */
    private final ExecutorService mBuildExecutor = Executors.newSingleThreadExecutor();
    private volatile int mBuildSeq;

    private AvatarSurfaceView mGl;
    private LinearLayout mControls;
    private TextView mStat;
    private TextView mNote;
    private ImageView mThumb;

    private Slider mHeight, mWeight, mHead, mShoulder, mChest, mWaist, mHip;
    /** 身材细分 */
    private Slider mLeg, mTorso, mArm, mNeckLen, mMuscle, mBust;
    /** 腿脚细分：围度与脚的尺码单独可调 */
    private Slider mThigh, mCalf, mFootL, mFootW;
    /** 女性罩杯：选中即从实测反解丰满度；每次建模结束回显实测到的那一杯 */
    private LinearLayout mCupRow;
    private AppCompatSpinner mCup;
    private int mCupSelection = 0;
    /** 回显下拉时不再触发联动，避免「建模 → 回显 → 又建模」转圈 */
    private boolean mSyncUi = true;
    /** 腰臀联动时不再互相回写，避免两个滑块来回推 */
    private boolean mLinkWaistHip;
    /** 五官细分 */
    private Slider mFaceW, mFaceL, mJawW, mCheek, mChin, mForehead,
            mEyeSize, mEyeGap, mNoseW, mNoseH, mLipW, mLipT, mBrow;
    private View mSwatchSkin, mSwatchHair, mSwatchTop, mSwatchBottom;
    /** 刘海那一行：只有原生引擎会按它搭刘海网格，高分引擎的发型是模型自带的 */
    private View mBangsRow;
    private TextView mBtnMale, mBtnFemale;
    /** 当前建模引擎（原生 / 高分 Anny） */
    private int mEngine = ENGINE_NATIVE;
    private TextView mBtnNative, mBtnAnny, mEngineNote;
    private HumanMesh.Result mMesh;
    private Uri mCameraUri;
    /** 当前素材（相册 / 拍照），点击缩略图可看大图 */
    private Uri mPhotoUri;
    /** Tripo3D：API Key 与面数上限（只存在本机 SharedPreferences） */
    private String mTripoKey = "";
    private int mTripoFaces = 20000;

    private interface FloatConsumer {
        void accept(float v);
    }

    private interface IntConsumer {
        void accept(int v);
    }

    private interface FloatGetter {
        float get();
    }

    private static final class Slider {
        SeekBar bar;
        /** 滑块所在的整块（标题 + 读数 + 拖动条）：某个引擎下这一项用不上时整块隐藏 */
        View box;
        float min;
        float step;

        void set(float v) {
            bar.setProgress(Math.round((v - min) / step));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.avatar_3d);
        setContentView(R.layout.activity_avatar);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(C_BG));

        mGl = findViewById(R.id.gl_view);
        mControls = findViewById(R.id.controls);

        // 入口选择框传进来的引擎优先，其次用上次记住的
        int engine = getIntent().getIntExtra(EXTRA_ENGINE, -1);
        restorePrefs();
        if (engine == ENGINE_NATIVE || engine == ENGINE_ANNY) mEngine = engine;
        buildUi();
        mSyncUi = false;              // 初始 selection 的回调不算「用户选了杯」
        // 入口传进来的引擎可能与存档里的不同，腰臀比的允许区间两套引擎不一样，按当前引擎
        // 再协调一次（滑块此时已建好，会一起回写显示）
        linkWaistHip(0);
        rebuild();
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePrefs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        savePrefs();
        mHandler.removeCallbacksAndMessages(null);
        mBuildExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private void buildUi() {
        // ---- 建模引擎 ----
        mControls.addView(caption("⓪ 建模引擎"));
        LinearLayout engineRow = hRow();
        mBtnNative = toggle("原生（程序化）", mEngine == ENGINE_NATIVE,
                v -> setEngine(ENGINE_NATIVE));
        mBtnAnny = toggle("高分（Anny）", mEngine == ENGINE_ANNY,
                v -> setEngine(ENGINE_ANNY));
        engineRow.addView(mBtnNative, weightParams(1f));
        engineRow.addView(mBtnAnny, weightParams(1f));
        mControls.addView(engineRow);
        mEngineNote = new TextView(this);
        mEngineNote.setTextSize(11);
        mEngineNote.setTextColor(0xFF6B7280);
        mEngineNote.setPadding(0, dp(4), 0, dp(2));
        mControls.addView(mEngineNote);
        LinearLayout annyRow = hRow();
        annyRow.setPadding(0, dp(4), 0, 0);
        annyRow.addView(actionButton("📥 替换 Anny 模型数据（anny.mhb）", v -> importAnny()),
                weightParams(1f));
        mControls.addView(annyRow);
        updateEngineStyle();

        // ---- 照片来源 ----
        mControls.addView(caption("① 素材：相册照片 / 拍照"));
        LinearLayout srcRow = hRow();
        srcRow.addView(actionButton("🖼 相册", v -> pickPhoto()), weightParams(1f));
        srcRow.addView(actionButton("📷 拍照", v -> takePhoto()), weightParams(1f));
        mThumb = new ImageView(this);
        mThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mThumb.setBackground(rounded(C_FIELD, C_STROKE));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(64), dp(44));
        tp.setMargins(dp(8), 0, 0, 0);
        srcRow.addView(mThumb, tp);
        mThumb.setOnClickListener(v -> showPhotoPreview());
        mControls.addView(srcRow);

        mNote = new TextView(this);
        mNote.setTextSize(11);
        mNote.setTextColor(0xFF6B7280);
        mNote.setPadding(0, dp(4), 0, dp(8));
        mNote.setText("未选择照片：使用默认形象，可在下方手动调参。");
        mControls.addView(mNote);

        // ---- 性别 ----
        mControls.addView(caption("② 性别"));
        LinearLayout genderRow = hRow();
        mBtnMale = toggle("男", mProfile.gender == 0, v -> setGender(0));
        mBtnFemale = toggle("女", mProfile.gender == 1, v -> setGender(1));
        genderRow.addView(mBtnMale, weightParams(1f));
        genderRow.addView(mBtnFemale, weightParams(1f));
        mControls.addView(genderRow);

        // ---- 体型参数 ----
        mControls.addView(caption("③ 体型参数"));
        mHeight = addSlider("身高", 120f, 210f, 1f, mProfile.heightCm, " cm", "%.0f",
                v -> {
                    mProfile.heightCm = v;
                    scheduleRebuild();
                });
        mWeight = addSlider("体重", 30f, 150f, 0.5f, mProfile.weightKg, " kg", "%.1f",
                v -> {
                    mProfile.weightKg = v;
                    scheduleRebuild();
                });
        mHead = addSlider("头身比", 6f, 8.5f, 0.1f, mProfile.headRatio, " 头身", "%.1f",
                v -> {
                    mProfile.headRatio = v;
                    scheduleRebuild();
                });

        // ---- 身材细分 ----
        mControls.addView(caption("④ 身材细分（腿长 / 躯干 / 臂长 / 肌肉…）"));
        mLeg = addParamSlider("腿长", 0.90f, 1.10f,
                () -> mProfile.legR, v -> {
                    mProfile.legR = v;
                    scheduleRebuild();
                });
        mTorso = addParamSlider("躯干长", 0.92f, 1.08f,
                () -> mProfile.torsoR, v -> {
                    mProfile.torsoR = v;
                    scheduleRebuild();
                });
        mArm = addParamSlider("臂长", 0.88f, 1.12f,
                () -> mProfile.armR, v -> {
                    mProfile.armR = v;
                    scheduleRebuild();
                });
        mNeckLen = addParamSlider("颈长", 0.70f, 1.40f,
                () -> mProfile.neckLenR, v -> {
                    mProfile.neckLenR = v;
                    scheduleRebuild();
                });
        mMuscle = addParamSlider("肌肉量", 0.75f, 1.35f,
                () -> mProfile.muscleR, v -> {
                    mProfile.muscleR = v;
                    scheduleRebuild();
                });
        mControls.addView(labelledRow("胸型", spinner(BodyProfile.BUST_SHAPES, mProfile.bustShape,
                pos -> {
                    mProfile.bustShape = pos;
                    scheduleRebuild();
                })));
        mBust = addParamSlider("丰满度", 0.20f, 2.10f,
                () -> mProfile.bustR, v -> {
                    mProfile.bustR = v;
                    scheduleRebuild();
                });
        mCup = spinner(BodyProfile.BUST_CUP_LABELS, mCupSelection, this::onCupPicked);
        mCupRow = labelledRow("罩杯", mCup);
        mCupRow.setVisibility(cupVisible() ? View.VISIBLE : View.GONE);
        mControls.addView(mCupRow);

        // ---- 腿脚细分 ----（大腿围 / 小腿围都挪到下面臀围旁边了：调下半身时臀和大腿、
        // 小腿是一起看的，分开在上下两段里很难调）
        mControls.addView(caption("⑤ 腿脚（脚长 / 脚宽）"));
        mFootL = addParamSlider("脚长", 0.85f, 1.20f,
                () -> mProfile.footR, v -> {
                    mProfile.footR = v;
                    scheduleRebuild();
                });
        mFootW = addParamSlider("脚宽", 0.80f, 1.30f,
                () -> mProfile.footWR, v -> {
                    mProfile.footWR = v;
                    scheduleRebuild();
                });

        // ---- 五官细分 ----
        mControls.addView(caption("⑥ 五官细分（照片推断后仍可手调）"));
        mFaceW = addParamSlider("脸宽", 0.80f, 1.25f,
                () -> mProfile.faceWidthR, v -> {
                    mProfile.faceWidthR = v;
                    scheduleRebuild();
                });
        mFaceL = addParamSlider("脸长", 0.88f, 1.12f,
                () -> mProfile.faceLenR, v -> {
                    mProfile.faceLenR = v;
                    scheduleRebuild();
                });
        mJawW = addParamSlider("下颌宽", 0.60f, 1.50f,
                () -> mProfile.jawR, v -> {
                    mProfile.jawR = v;
                    scheduleRebuild();
                });
        mCheek = addParamSlider("颧骨", 0.60f, 1.50f,
                () -> mProfile.cheekR, v -> {
                    mProfile.cheekR = v;
                    scheduleRebuild();
                });
        mChin = addParamSlider("下巴", 0.50f, 1.60f,
                () -> mProfile.chinR, v -> {
                    mProfile.chinR = v;
                    scheduleRebuild();
                });
        mForehead = addParamSlider("额头", 0.70f, 1.30f,
                () -> mProfile.foreheadR, v -> {
                    mProfile.foreheadR = v;
                    scheduleRebuild();
                });
        mEyeSize = addParamSlider("眼大小", 0.70f, 1.40f,
                () -> mProfile.eyeSizeR, v -> {
                    mProfile.eyeSizeR = v;
                    scheduleRebuild();
                });
        mEyeGap = addParamSlider("眼距", 0.72f, 1.35f,
                () -> mProfile.eyeGapR, v -> {
                    mProfile.eyeGapR = v;
                    scheduleRebuild();
                });
        mNoseW = addParamSlider("鼻宽", 0.70f, 1.40f,
                () -> mProfile.noseWR, v -> {
                    mProfile.noseWR = v;
                    scheduleRebuild();
                });
        mNoseH = addParamSlider("鼻长", 0.70f, 1.45f,
                () -> mProfile.noseHR, v -> {
                    mProfile.noseHR = v;
                    scheduleRebuild();
                });
        mLipW = addParamSlider("唇宽", 0.70f, 1.45f,
                () -> mProfile.lipWR, v -> {
                    mProfile.lipWR = v;
                    scheduleRebuild();
                });
        mLipT = addParamSlider("唇厚", 0.60f, 1.70f,
                () -> mProfile.lipTR, v -> {
                    mProfile.lipTR = v;
                    scheduleRebuild();
                });
        mBrow = addParamSlider("眉粗细", 0.60f, 1.80f,
                () -> mProfile.browR, v -> {
                    mProfile.browR = v;
                    scheduleRebuild();
                });
        addParamSlider("耳大小", 0.70f, 1.45f,
                () -> mProfile.earSizeR, v -> {
                    mProfile.earSizeR = v;
                    scheduleRebuild();
                });
        LinearLayout faceOp = hRow();
        faceOp.setPadding(0, dp(4), 0, 0);
        faceOp.addView(actionButton("🧹 清除脸部贴图", v -> {
            mProfile.faceBmp = null;
            scheduleRebuild();
        }), weightParams(1f));
        mControls.addView(faceOp);

        // ---- 轮廓微调 ----
        mControls.addView(caption("⑦ 轮廓微调（肩 / 胸 / 腰 / 臀 / 大腿 / 小腿，照片推断后可再手调）"));
        mShoulder = addSlider("肩宽", 0.70f, 1.60f, 0.01f, mProfile.shoulderR, "×", "%.2f",
                v -> {
                    mProfile.shoulderR = v;
                    scheduleRebuild();
                });
        mChest = addSlider("胸围", 0.70f, 1.60f, 0.01f, mProfile.chestR, "×", "%.2f",
                v -> {
                    mProfile.chestR = v;
                    scheduleRebuild();
                });
        mWaist = addSlider("腰围", 0.70f, 1.60f, 0.01f, mProfile.waistR, "×", "%.2f",
                v -> {
                    mProfile.waistR = v;
                    linkWaistHip(1);          // 腰照用户给的算，臀跟着配
                    scheduleRebuild();
                });
        mHip = addSlider("臀围", 0.70f, 1.60f, 0.01f, mProfile.hipR, "×", "%.2f",
                v -> {
                    mProfile.hipR = v;
                    linkWaistHip(-1);         // 臀照用户给的算，腰跟着配
                    scheduleRebuild();
                });
        // 大腿围 / 小腿围紧跟臀围：这三个是连着的，臀一大腿就得跟着，分开在上下两段里很难调
        mThigh = addParamSlider("大腿围", 0.70f, 1.45f,
                () -> mProfile.thighR, v -> {
                    mProfile.thighR = v;
                    scheduleRebuild();
                });
        mCalf = addParamSlider("小腿围", 0.70f, 1.45f,
                () -> mProfile.calfR, v -> {
                    mProfile.calfR = v;
                    scheduleRebuild();
                });

        // ---- 脸型 / 发型 ----
        mControls.addView(caption("⑧ 脸型与发型"));
        mControls.addView(labelledRow("脸型", spinner(BodyProfile.FACE_SHAPES, mProfile.faceShape,
                pos -> {
                    mProfile.faceShape = pos;
                    scheduleRebuild();
                })));
        mControls.addView(labelledRow("发型", spinner(BodyProfile.HAIR_STYLES, mProfile.hairStyle,
                pos -> {
                    mProfile.hairStyle = pos;
                    scheduleRebuild();
                })));
        mBangsRow = labelledRow("刘海", spinner(BodyProfile.BANGS, mProfile.bangs,
                pos -> {
                    mProfile.bangs = pos;
                    scheduleRebuild();
                }));
        mControls.addView(mBangsRow);
        // ---- 配色 ----
        mControls.addView(caption("⑨ 配色（点击色块更换）"));
        mSwatchSkin = addColorRow("肤色", mProfile.skin, c -> {
            mProfile.skin = c;
            refreshSwatches();
            scheduleRebuild();
        });
        mSwatchHair = addColorRow("发色", mProfile.hair, c -> {
            mProfile.hair = c;
            refreshSwatches();
            scheduleRebuild();
        });
        mSwatchTop = addColorRow("上装", mProfile.top, c -> {
            mProfile.top = c;
            refreshSwatches();
            scheduleRebuild();
        });
        mSwatchBottom = addColorRow("下装", mProfile.bottom, c -> {
            mProfile.bottom = c;
            refreshSwatches();
            scheduleRebuild();
        });
        refreshSwatches();

        // ---- 操作 ----
        mControls.addView(caption("⑩ 生成与导出"));
        LinearLayout op1 = hRow();
        op1.addView(primaryButton("🔄 重新生成", v -> rebuild()), weightParams(1f));
        op1.addView(actionButton("🧭 复位视角", v -> mGl.resetView()), weightParams(1f));
        mControls.addView(op1);
        LinearLayout op2 = hRow();
        op2.setPadding(0, dp(6), 0, 0);
        op2.addView(actionButton("💾 导出 OBJ", v -> exportObj()), weightParams(1f));
        op2.addView(actionButton("🖼 保存预览图", v -> saveShot()), weightParams(1f));
        mControls.addView(op2);
        LinearLayout op3 = hRow();
        op3.setPadding(0, dp(6), 0, 0);
        op3.addView(actionButton("📦 导入开源 OBJ 模型（MakeHuman 等）", v -> importObj()),
                weightParams(1f));
        mControls.addView(op3);
        LinearLayout op4 = hRow();
        op4.setPadding(0, dp(6), 0, 0);
        op4.addView(actionButton("☁ Tripo3D 照片生成 3D", v -> showTripoDialog()),
                weightParams(1f));
        mControls.addView(op4);

        mStat = new TextView(this);
        mStat.setTextSize(11);
        mStat.setTextColor(C_SUB);
        mStat.setPadding(0, dp(8), 0, dp(4));
        mStat.setLineSpacing(0, 1.25f);
        mControls.addView(mStat);

        // 控件都建完了再按引擎收一次：updateEngineStyle 第一次被调用时（引擎那一行刚建好）
        // 后面这些滑块还不存在，那会儿只能刷按钮样式
        updateEngineStyle();
    }

    private TextView caption(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xFFB39DDB);
        tv.setPadding(0, dp(8), 0, dp(4));
        return tv;
    }

    private LinearLayout hRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams weightParams(float w) {
        return new LinearLayout.LayoutParams(0, dp(40), w);
    }

    private LinearLayout labelledRow(String label, View field) {
        LinearLayout row = hRow();
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(C_SUB);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f));
        row.addView(field, new LinearLayout.LayoutParams(0, dp(38), 1f));
        row.setPadding(0, dp(2), 0, dp(4));
        return row;
    }

    private static int clampIndex(int i, int n) {
        return i < 0 ? 0 : (i > n - 1 ? n - 1 : i);
    }

    private AppCompatSpinner spinner(String[] items, int selection, IntConsumer onPick) {
        AppCompatSpinner sp = new AppCompatSpinner(this);
        sp.setBackground(rounded(C_FIELD, C_STROKE));
        sp.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(C_FIELD));
        // 这两个 layout 跟行情页共用，那里是浅底；这里按 Avatar 的暗底覆写文字色，
        // 免得深灰字落在 #FF151A21 的弹窗上（原色 #FF1F2937）几乎看不见
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                R.layout.item_market_spinner_selected, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) ((TextView) v).setTextColor(C_TEXT);
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                if (v instanceof TextView) ((TextView) v).setTextColor(C_TEXT);
                return v;
            }
        };
        adapter.setDropDownViewResource(R.layout.item_market_spinner);
        sp.setAdapter(adapter);
        // 存档里的下标可能比现在的选项表长（选项改过），夹一下，免得下拉框显示空白
        sp.setSelection(clampIndex(selection, items.length), false);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onPick.accept(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        return sp;
    }

    /**
     * 腰臀联动：拖其中一个滑块时把另一个也带上，别拉出「腰比臀还粗」或者「蚂蚁腰」这种
     * 真人长不出来的体型（真人的腰臀比：女 0.67~0.85、男 0.80~0.95）。
     *
     * @param keep &gt;0 保住腰（拖腰围滑块）、&lt;0 保住臀（拖臀围滑块）、0 两边各让一半
     */
    private void linkWaistHip(int keep) {
        if (mLinkWaistHip || mWaist == null || mHip == null) return;
        float[] wh = {mProfile.waistR, mProfile.hipR};
        if (mEngine == ENGINE_ANNY) BodyProfile.waistHipAnny(wh, mProfile.gender, keep);
        else BodyProfile.waistHip(wh, mProfile.gender, keep);
        if (Math.abs(wh[0] - mProfile.waistR) < 0.005f
                && Math.abs(wh[1] - mProfile.hipR) < 0.005f) return;
        mLinkWaistHip = true;
        mProfile.waistR = wh[0];
        mProfile.hipR = wh[1];
        mWaist.set(wh[0]);
        mHip.set(wh[1]);
        mLinkWaistHip = false;
    }

    /** 直接绑定 BodyProfile 上某个「×倍率」参数的滑块 */
    private Slider addParamSlider(String title, float min, float max, FloatGetter get, FloatConsumer cb) {
        return addSlider(title, min, max, 0.01f, get.get(), "×", "%.2f", cb);
    }

    private Slider addSlider(String title, float min, float max, float step, float value,
                             String unit, String format, FloatConsumer cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(2), 0, dp(2));

        LinearLayout head = hRow();
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(12);
        name.setTextColor(C_SUB);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(this);
        val.setTextSize(12);
        val.setTextColor(0xFF00DCA0);
        val.setText(String.format(Locale.US, format, value) + unit);
        head.addView(val);
        box.addView(head);

        Slider holder = new Slider();
        holder.min = min;
        holder.step = step;
        AppCompatSeekBar bar = new AppCompatSeekBar(this);
        bar.setMax(Math.round((max - min) / step));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float v = min + progress * step;
                val.setText(String.format(Locale.US, format, v) + unit);
                if (fromUser) cb.accept(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        holder.bar = bar;
        holder.set(value);
        box.addView(bar);
        holder.box = box;
        mControls.addView(box);
        return holder;
    }

    /** 某项参数在当前引擎下用不上时整块隐藏，别留一个拖了没反应的滑块在那儿 */
    private static void setSliderVisible(Slider s, boolean visible) {
        if (s == null || s.box == null) return;
        s.box.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private View addColorRow(String title, int color, IntConsumer cb) {
        LinearLayout row = hRow();
        row.setPadding(0, dp(3), 0, dp(3));
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(12);
        name.setTextColor(C_SUB);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View swatch = new View(this);
        swatch.setLayoutParams(new LinearLayout.LayoutParams(dp(84), dp(28)));
        swatch.setOnClickListener(v -> showPalette(title, cb));
        row.addView(swatch);
        mControls.addView(row);
        return swatch;
    }

    private void showPalette(String title, IntConsumer cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(4));
        final AlertDialog[] holder = new AlertDialog[1];
        int perRow = 6;
        for (int i = 0; i < PALETTE.length; i += perRow) {
            LinearLayout row = hRow();
            row.setPadding(0, 0, 0, dp(8));
            for (int j = i; j < Math.min(PALETTE.length, i + perRow); j++) {
                final int color = PALETTE[j];
                View sw = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
                lp.setMargins(dp(3), 0, dp(3), 0);
                sw.setLayoutParams(lp);
                sw.setBackground(rounded(color, 0xFF6B7280));
                sw.setOnClickListener(v -> {
                    cb.accept(color);
                    if (holder[0] != null) holder[0].dismiss();
                });
                row.addView(sw);
            }
            box.addView(row);
        }
        holder[0] = new AlertDialog.Builder(this)
                .setTitle("选择" + title)
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
    }

    private TextView toggle(String text, boolean selected, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(13);
        tv.setTextColor(selected ? 0xFF0A0A12 : C_TEXT);
        tv.setBackground(buttonBg(selected ? C_PRIMARY : C_FIELD, C_STROKE,
                C_PRIMARY_PRESSED, C_STROKE));
        tv.setOnClickListener(v -> {
            l.onClick(v);
            updateGenderStyle();
        });
        return tv;
    }

    private TextView actionButton(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(12);
        tv.setTextColor(C_TEXT);
        tv.setBackground(buttonBg(C_FIELD, C_STROKE, C_PRESSED, C_STROKE));
        tv.setOnClickListener(l);
        return tv;
    }

    private TextView primaryButton(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(12);
        tv.setTextColor(0xFFFFFFFF);
        tv.setBackground(buttonBg(C_PRIMARY, 0, C_PRIMARY_PRESSED, 0));
        tv.setOnClickListener(l);
        return tv;
    }

    private Drawable rounded(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(6));
        if (stroke != 0) drawable.setStroke(Math.max(1, dp(1)), stroke);
        return drawable;
    }

    private Drawable buttonBg(int fill, int stroke, int pressedFill, int pressedStroke) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(pressedFill, pressedStroke));
        states.addState(new int[0], rounded(fill, stroke));
        return states;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    private void setGender(int gender) {
        mProfile.gender = gender;
        linkWaistHip(0);        // 男女的腰臀比区间不同，换性别时重新协调一次
        updateGenderStyle();
        scheduleRebuild();
    }

    /** 切换建模引擎：原生（程序化）/ 高分（Anny 人体测量形变基） */
    private void setEngine(int engine) {
        if (mEngine == engine) return;
        mEngine = engine;
        updateEngineStyle();
        linkWaistHip(0);        // 两套引擎的腰臀比区间不同，换引擎时把两个滑块重新协调回显
        savePrefs();
        rebuild();
    }

    private void updateGenderStyle() {
        if (mBtnMale == null || mBtnFemale == null) return;
        styleToggle(mBtnMale, mProfile.gender == 0);
        styleToggle(mBtnFemale, mProfile.gender == 1);
        if (mCupRow != null) {
            mCupRow.setVisibility(cupVisible() ? View.VISIBLE : View.GONE);
        }
    }

    /** 罩杯读数是由高分引擎那一圈的实测差值算出来的，原生引擎 / 男性都没有这一项 */
    private boolean cupVisible() {
        return mProfile.gender == 1 && mEngine == ENGINE_ANNY;
    }

    private void updateEngineStyle() {
        if (mBtnNative == null || mBtnAnny == null) return;
        boolean nativeOn = mEngine == ENGINE_NATIVE;
        styleToggle(mBtnNative, nativeOn);
        styleToggle(mBtnAnny, !nativeOn);
        if (mEngineNote != null) {
            mEngineNote.setText(nativeOn
                    ? "原生：隐式曲面（胶囊 / 椭球平滑并集）+ 等值面提取，身体与五官全程序化生成。"
                    : "高分：Anny（MakeHuman 人体测量形变基）替换身体网格；"
                    + "脸型 / 五官以模型自带形变为主，发型与刘海按实测头壳另搭，"
                    + "体型与围度滑块仍按人体测量生效；"
                    + "臂长 / 鼻宽 / 鼻长只有原生引擎才有，这里已收起。");
        }
        if (mCupRow != null) {
            mCupRow.setVisibility(cupVisible() ? View.VISIBLE : View.GONE);
        }
        // 这几项只有原生引擎真的接了：高分的身体是 Anny 网格，臂长没有对应的形变
        // （AnnyModel 里只有腿长 / 躯干长 / 颈长），鼻子是那颗真脸上自带的（没有鼻宽 / 鼻长
        // 这两个参数）。与其留着拖不动的滑块，不如整块收起
        // 刘海不在此列：高分这边的头发也是按实测头壳表搭出来的（AnnyModel.addHair →
        // HumanMesh.buildHair），刘海那一圈同样贴得上，所以两套引擎都给
        boolean anny = mEngine == ENGINE_ANNY;
        setSliderVisible(mArm, !anny);
        setSliderVisible(mNoseW, !anny);
        setSliderVisible(mNoseH, !anny);
    }

    private void styleToggle(TextView tv, boolean selected) {
        tv.setTextColor(selected ? 0xFF0A0A12 : C_TEXT);
        tv.setBackground(buttonBg(selected ? C_PRIMARY : C_FIELD, C_STROKE,
                C_PRIMARY_PRESSED, C_STROKE));
    }

    private void refreshSwatches() {
        setSwatch(mSwatchSkin, mProfile.skin);
        setSwatch(mSwatchHair, mProfile.hair);
        setSwatch(mSwatchTop, mProfile.top);
        setSwatch(mSwatchBottom, mProfile.bottom);
    }

    private void setSwatch(View v, int color) {
        if (v == null) return;
        v.setBackground(rounded(color, C_STROKE));
    }

    private void pickPhoto() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择照片"), REQ_PICK);
        } catch (Exception e) {
            Log.w(TAG, "pick photo failed", e);
            toast("无法打开相册");
        }
    }

    private void takePhoto() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
            return;
        }
        dispatchCamera();
    }

    private void dispatchCamera() {
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "mkdirs failed " + dir);
            }
            File file = new File(dir, "avatar_" + System.currentTimeMillis() + ".jpg");
            mCameraUri = FileProvider.getUriForFile(this, "com.tsymiar.device2device.fileprovider", file);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (getPackageManager() != null) {
                for (android.content.pm.ResolveInfo info :
                        getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
                    grantUriPermission(info.activityInfo.packageName, mCameraUri,
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            }
            startActivityForResult(intent, REQ_SHOT);
        } catch (Exception e) {
            Log.e(TAG, "camera failed", e);
            toast("无法启动相机：" + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERM && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            dispatchCamera();
        } else if (requestCode == REQ_CAMERA_PERM) {
            toast("未授予相机权限，无法拍照");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_PICK && data != null && data.getData() != null) {
            handlePhoto(data.getData());
        } else if (requestCode == REQ_SHOT && mCameraUri != null) {
            handlePhoto(mCameraUri);
        } else if (requestCode == REQ_OBJ && data != null && data.getData() != null) {
            handleObj(data.getData());
        } else if (requestCode == REQ_ANNY && data != null && data.getData() != null) {
            handleAnny(data.getData());
        }
    }

    /** 选择第三方 OBJ（MakeHuman / Blender / Ready Player Me 等导出的人体） */
    private void importObj() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择 OBJ 模型"), REQ_OBJ);
        } catch (Exception e) {
            Log.w(TAG, "pick obj failed", e);
            toast("无法打开文件选择器");
        }
    }

    /** 导入并按当前身高归一化；顶点/面数过多的模型直接提示，避免卡死 */
    private void handleObj(final Uri uri) {
        toast("正在导入模型…");
        final float target = Math.max(0.8f, mProfile.heightCm / 100f);
        new Thread(() -> {
            ObjLoader.LoadResult res;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                res = in == null ? null : ObjLoader.load(in, target, mProfile.top);
            } catch (Exception e) {
                Log.e(TAG, "import obj failed", e);
                res = null;
            }
            final ObjLoader.LoadResult result = res;
            runOnUiThread(() -> {
                if (result == null || result.mesh == null) {
                    toast("导入失败：" + (result == null ? "无法读取文件" : result.error));
                    return;
                }
                mBuildSeq++;                  // 作废排队中的参数化建模结果
                mMesh = result.mesh;
                mGl.setMesh(result.mesh);
                mStat.setText(ObjLoader.stat(result.mesh));
                toast("已导入，已归一化为 " + Math.round(mProfile.heightCm) + " cm");
            });
        }).start();
    }

    /**
     * 高分引擎的模型数据（anny.mhb）由 tools/export_anny*.py 离线导出，默认已经打进
     * assets/anny/ 里随 APK 一起发布，装上就能直接用，不需要任何额外操作。
     *
     * 这里提供的是旁路：换一份自己导出的数据装进来，不用重新打包 APK。
     */
    private void importAnny() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择 anny.mhb"), REQ_ANNY);
        } catch (Exception e) {
            Log.w(TAG, "pick anny failed", e);
            toast("无法打开文件选择器");
        }
    }

    private void handleAnny(final Uri uri) {
        toast("正在载入 Anny 模型数据…");
        new Thread(() -> {
            boolean ok = false;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ok = in != null && AnnyModel.install(in);
            } catch (Exception e) {
                Log.e(TAG, "load anny failed", e);
            }
            final boolean done = ok;
            runOnUiThread(() -> {
                if (!done) {
                    toast("载入失败：不是有效的 anny.mhb");
                    return;
                }
                toast("Anny 模型数据已载入");
                mEngine = ENGINE_ANNY;           // 载入成功就切到高分
                updateEngineStyle();
                linkWaistHip(0);
                savePrefs();
                rebuild();
            });
        }).start();
    }

    // ------------------------------------------------------------------
    // Tripo3D：照片 → 云端生成 3D 模型（需要自己的 API Key）
    // ------------------------------------------------------------------

    /** 填 API Key / 面数上限的对话框 */
    private void showTripoDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(14), dp(4));

        TextView keyLabel = new TextView(this);
        keyLabel.setText("API Key（tripo3d.com 后台获取，仅保存在本机）");
        keyLabel.setTextSize(12);
        keyLabel.setTextColor(C_SUB);
        box.addView(keyLabel);

        final EditText keyEdit = new EditText(this);
        keyEdit.setBackground(rounded(C_FIELD, C_STROKE));
        keyEdit.setHint("tsk_…");
        keyEdit.setHintTextColor(0xFF6B7280);
        keyEdit.setText(mTripoKey);
        keyEdit.setSingleLine();
        keyEdit.setTextColor(C_TEXT);
        keyEdit.setTextSize(13);
        keyEdit.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.addView(keyEdit);

        TextView faceLabel = new TextView(this);
        faceLabel.setText("面数上限（手机建议 8000~30000，越大越精细也越慢）");
        faceLabel.setTextSize(12);
        faceLabel.setTextColor(C_SUB);
        faceLabel.setPadding(0, dp(8), 0, 0);
        box.addView(faceLabel);

        final EditText faceEdit = new EditText(this);
        faceEdit.setBackground(rounded(C_FIELD, C_STROKE));
        faceEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        faceEdit.setText(String.valueOf(mTripoFaces));
        faceEdit.setSingleLine();
        faceEdit.setTextColor(C_TEXT);
        faceEdit.setTextSize(13);
        faceEdit.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.addView(faceEdit);

        final android.widget.CheckBox textureBox = new android.widget.CheckBox(this);
        textureBox.setText("生成贴图（关闭则只出几何，速度更快）");
        textureBox.setChecked(true);
        textureBox.setTextColor(C_TEXT);
        textureBox.setTextSize(12);
        textureBox.setPadding(0, dp(8), 0, 0);
        box.addView(textureBox);

        new AlertDialog.Builder(this)
                .setTitle("Tripo3D 照片生成 3D")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始生成", (dialog, which) -> {
                    String key = keyEdit.getText().toString().trim();
                    if (key.isEmpty()) {
                        toast("请先填写 Tripo API Key");
                        return;
                    }
                    int faces = mTripoFaces;
                    try {
                        faces = Integer.parseInt(faceEdit.getText().toString().trim());
                    } catch (NumberFormatException ignore) {
                        // 填了非法值就用上一次的
                    }
                    mTripoKey = key;
                    mTripoFaces = faces;
                    savePrefs();
                    startTripo(key, faces, textureBox.isChecked());
                })
                .show();
    }

    /** 上传照片 → 轮询任务 → 下载 GLB → 按身高归一化后载入预览 */
    private void startTripo(String key, int faces, boolean texture) {
        if (mPhotoUri == null) {
            toast("先在「① 素材」里选一张照片，Tripo 用它生成 3D");
            return;
        }
        final float target = Math.max(0.8f, mProfile.heightCm / 100f);
        final File out = new File(getFilesDir(), "tripo_model.glb");
        final TripoClient.Options opt = new TripoClient.Options();
        opt.faceLimit = Math.max(500, faces);
        opt.texture = texture;
        opt.pbr = false;

        toast("已提交 Tripo3D，通常需要 1~3 分钟…");
        mStat.setText("Tripo3D：正在上传照片…");
        new Thread(() -> {
            Bitmap bmp = decode(mPhotoUri, 1024);
            if (bmp == null) {
                runOnUiThread(() -> toast("读取照片失败"));
                return;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, bos);
            if (bos.size() > 18 * 1024 * 1024) {          // 接口限制 20MB
                runOnUiThread(() -> toast("照片太大，请换一张更小的"));
                return;
            }
            TripoClient.Outcome res = TripoClient.generate(key, bos.toByteArray(), opt, out,
                    (stage, percent) -> runOnUiThread(() -> mStat.setText("Tripo3D：" + stage)));
            if (res.file == null) {
                final String err = res.error == null ? "未知错误" : res.error;
                runOnUiThread(() -> {
                    mStat.setText("Tripo3D 失败：" + err);
                    toast("Tripo3D 失败：" + err);
                });
                return;
            }
            GlbLoader.LoadResult loaded = GlbLoader.load(res.file, target, mProfile.top);
            runOnUiThread(() -> {
                if (loaded.mesh == null) {
                    mStat.setText("Tripo3D 模型载入失败：" + loaded.error);
                    toast("模型载入失败：" + loaded.error);
                    return;
                }
                mBuildSeq++;                       // 作废排队中的参数化建模结果
                mMesh = loaded.mesh;
                mGl.setMesh(loaded.mesh);
                mStat.setText("Tripo3D 模型：" + loaded.mesh.vertices + " 顶点 / "
                        + loaded.mesh.triangles + " 三角面 · 已归一化为 "
                        + Math.round(mProfile.heightCm) + " cm");
                toast("Tripo3D 生成完成");
            });
        }).start();
    }

    /** 解码 + 推断（放在后台线程，避免大图解码卡住 UI） */
    private void handlePhoto(final Uri uri) {
        toast("正在分析照片…");
        new Thread(() -> {
            Bitmap bmp = decode(uri, 1080);
            if (bmp == null) {
                runOnUiThread(() -> toast("读取图片失败"));
                return;
            }
            final PhotoAnalyzer.Result r = PhotoAnalyzer.analyze(bmp);
            final Bitmap face = PhotoAnalyzer.cropFace(bmp, r);
            runOnUiThread(() -> {
                mPhotoUri = uri;
                mThumb.setImageBitmap(bmp);
                applyAnalysis(r, face);
            });
        }).start();
    }

    private void applyAnalysis(PhotoAnalyzer.Result r, Bitmap face) {
        if (r.skin != -1) mProfile.skin = r.skin;
        if (r.hair != -1) mProfile.hair = r.hair;
        if (r.top != -1) mProfile.top = r.top;
        if (r.bottom != -1) mProfile.bottom = r.bottom;
        mProfile.chestR = r.chestR;
        mProfile.waistR = r.waistR;
        mProfile.hipR = r.hipR;
        linkWaistHip(0);            // 照片量出来的腰臀比一般是真人的，越界的顺手夹回来
        mChest.set(mProfile.chestR);
        mWaist.set(mProfile.waistR);
        mHip.set(mProfile.hipR);
        // 脸部：贴图 + 五官比例（都可在下方继续手调）
        mProfile.faceBmp = face;
        // 以脸部贴图的肤色为准：脸是照片烘焙的，身体是纯色，两处基色必须一致
        if (face != null) mProfile.skin = PhotoAnalyzer.faceSkin(face, mProfile.skin);
        if (r.faceOk) {
            mProfile.faceWidthR = r.faceWidthR;
            mProfile.faceLenR = r.faceLenR;
            mProfile.jawR = r.jawR;
            mProfile.eyeGapR = r.eyeGapR;
            mProfile.eyeSizeR = r.eyeSizeR;
            mProfile.noseWR = r.noseWR;
            mProfile.lipTR = r.lipTR;
            mProfile.browR = r.browR;
            mFaceW.set(r.faceWidthR);
            mFaceL.set(r.faceLenR);
            mJawW.set(r.jawR);
            mEyeSize.set(r.eyeSizeR);
            mEyeGap.set(r.eyeGapR);
            mNoseW.set(r.noseWR);
            mLipT.set(r.lipTR);
            mBrow.set(r.browR);
        }
        mNote.setText(r.note + "\n（点击右侧缩略图可查看原图）");
        refreshSwatches();
        rebuild();
    }

    /** 点击缩略图弹出大图预览 */
    private void showPhotoPreview() {
        if (mPhotoUri == null) {
            toast("还没有选择照片");
            return;
        }
        final ImageView big = new ImageView(this);
        big.setAdjustViewBounds(true);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        big.setBackgroundColor(C_BG);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.addView(big, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("素材预览")
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
        int side = (int) (Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 0.86f);
        android.view.Window w = dialog.getWindow();
        if (w != null) w.setLayout(side, side);
        // 大图解码放到后台，避免大文件卡住 UI
        new Thread(() -> {
            Bitmap bmp = decode(mPhotoUri, 1600);
            runOnUiThread(() -> {
                if (bmp == null) {
                    toast("读取图片失败");
                    return;
                }
                big.setImageBitmap(bmp);
            });
        }).start();
    }

    private Bitmap decode(Uri uri, int maxDim) {
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(getContentResolver().openInputStream(uri), null, opt);
            int w = opt.outWidth;
            int h = opt.outHeight;
            int sample = 1;
            while (Math.max(w, h) / sample > maxDim) sample *= 2;
            opt.inJustDecodeBounds = false;
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeStream(getContentResolver().openInputStream(uri), null, opt);
        } catch (Exception e) {
            Log.e(TAG, "decode failed", e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 建模 / 导出
    // ------------------------------------------------------------------

    private void scheduleRebuild() {
        mHandler.removeCallbacks(mRebuildTask);
        mHandler.postDelayed(mRebuildTask, 150);
    }

    private final Runnable mRebuildTask = new Runnable() {
        @Override
        public void run() {
            rebuild();
        }
    };

    /**
     * 建模在手机上需要几百毫秒，放到单线程池里跑，只保留最后一次结果，
     * 避免连续拖动滑块时堆积任务。两套引擎输出同一种 {@link HumanMesh.Result}，
     * 于是渲染、导出、截图这些框架完全共用。
     */
    private void rebuild() {
        final BodyProfile snapshot = mProfile.copy();
        final int engine = mEngine;
        final int seq = ++mBuildSeq;
        if (mStat != null) {
            mStat.setText(engine == ENGINE_ANNY
                    ? "Anny 求解中…（原型形变基插值 + 人体测量形变）"
                    : "建模中…（隐式曲面等值面提取）");
        }
        mBuildExecutor.execute(new Runnable() {
            @Override
            public void run() {
                HumanMesh.Result r;
                String warn = null;
                if (engine == ENGINE_ANNY) {
                    AnnyModel model = AnnyModel.get(AvatarActivity.this);
                    if (model == null) {
                        // 没打包 Anny 模型数据时退回原生，页面照常能用
                        warn = "未找到 Anny 模型数据（assets/" + AnnyModel.ASSET_DIR + "/"
                                + AnnyModel.ASSET_NAME + "），先用原生实现";
                        r = HumanMesh.build(snapshot);
                    } else {
                        r = model.build(snapshot);
                    }
                } else {
                    r = HumanMesh.build(snapshot);
                }
                final HumanMesh.Result result = r;
                final String warning = warn;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != mBuildSeq || isFinishing() || isDestroyed()) return;
                        mMesh = result;
                        mGl.setMesh(result);
                        updateStat();
                        updateCupReadout();
                        savePrefs();
                        if (warning != null) toast(warning);
                    }
                });
            }
        });
    }

    private void updateStat() {
        if (mStat == null || mMesh == null) return;
        mStat.setText(String.format(Locale.US,
                "引擎：%s\n"
                        + "身高 %.1f cm · 体重 %.1f kg · BMI %.1f（%s）\n"
                        + "头身比 %.1f · 肩宽 %.1f cm · 臂展 %.1f cm · 下裆 %.1f cm\n"
                        + "胸围 %.1f · 腰围 %.1f · 臀围 %.1f cm\n"
                        + "大腿围 %.1f · 小腿围 %.1f cm · 脚长 %.1f cm\n"
                        + "标准体重 %.1f kg · 网格 %d 顶点 / %d 三角面",
                engineName(),
                mProfile.heightCm, mProfile.weightKg, mProfile.bmi(), mProfile.bmiLabel(),
                mProfile.headRatio, mMesh.shoulderCm, mMesh.armSpanCm, mMesh.inseamCm,
                mMesh.chestCm, mMesh.waistCm, mMesh.hipCm,
                mMesh.thighCm, mMesh.calfCm, mMesh.footCm,
                mProfile.standardWeight(), mMesh.vertices, mMesh.triangles)
                + cupLine());
    }

    private String cupLine() {
        if (mMesh == null || mProfile.gender != 1 || mMesh.underbustCm <= 0f) return "";
        float cup = AnnyModel.cupCm(mMesh);
        return String.format(Locale.US, "\n下胸围 %.1f cm · 罩杯 %s（实测差 %.1f cm）",
                mMesh.underbustCm, BodyProfile.BUST_CUP_LABELS[cupIndexOf(cup)], cup);
    }

    private void onCupPicked(int pos) {
        if (mSyncUi || pos == mCupSelection) return;
        mCupSelection = pos;
        pickCup(pos);
    }

    /** 选一只杯：把目标杯差反解成丰满度再重建（反解要跑两遍形变，放后台线程） */
    private void pickCup(final int pos) {
        if (pos < 0 || pos >= BodyProfile.BUST_CUP_CM.length) return;
        final float target = BodyProfile.BUST_CUP_CM[pos];
        if (mStat != null) mStat.setText("按罩杯反解丰满度中…");
        mBuildExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AnnyModel model = AnnyModel.get(AvatarActivity.this);
                if (model == null) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("罩杯需要高分引擎的模型数据才解得准");
                        }
                    });
                    return;
                }
                final float bustR = model.bustRForCup(mProfile, target);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mProfile.bustR = bustR;
                        if (mBust != null) mBust.set(bustR);
                        rebuild();
                    }
                });
            }
        });
    }

    /** 建模结果出来之后把下拉回显到实测的那一杯 */
    private void updateCupReadout() {
        if (mCup == null || mMesh == null || mProfile.gender != 1) return;
        if (mMesh.underbustCm <= 0f) return;
        final int idx = cupIndexOf(AnnyModel.cupCm(mMesh));
        mSyncUi = true;
        mCupSelection = idx;
        mCup.setSelection(idx, false);
        mSyncUi = false;
    }

    private static int cupIndexOf(float cm) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < BodyProfile.BUST_CUP_CM.length; i++) {
            float d = Math.abs(BodyProfile.BUST_CUP_CM[i] - cm);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private String engineName() {
        if (mEngine == ENGINE_ANNY) {
            return "高分 · Anny（MakeHuman 人体测量形变基）";
        }
        return "原生 · SDF 隐式曲面 + SurfaceNets";
    }

    private void exportObj() {
        if (mMesh == null) return;
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir == null) {
            toast("存储不可用");
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "mkdirs failed " + dir);
        }
        final File file = new File(dir, "avatar_" + System.currentTimeMillis() + ".obj");
        toast("导出中…");
        new Thread(() -> {
            boolean ok = HumanMesh.writeObj(file, mMesh);
            runOnUiThread(() -> toast(ok ? "已导出：" + file.getAbsolutePath() : "导出失败"));
        }).start();
    }

    private void saveShot() {
        toast("正在保存预览图…");
        mGl.capture(bmp -> {
            if (bmp == null) {
                toast("截图失败");
                return;
            }
            File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "mkdirs failed " + dir);
            }
            File file = new File(dir, "avatar_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream os = new FileOutputStream(file)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                toast("已保存：" + file.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "save shot failed", e);
                toast("保存失败：" + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------
    // 记忆上次选择
    // ------------------------------------------------------------------

    private void restorePrefs() {
        SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        // anny.mhb 已内置进 assets，所以默认直接上高分引擎；没打包数据时才退回原生
        int defEngine = AnnyModel.isAvailable(this) ? ENGINE_ANNY : ENGINE_NATIVE;
        mEngine = p.getInt("engine", defEngine);
        if (mEngine != ENGINE_NATIVE && mEngine != ENGINE_ANNY) mEngine = defEngine;
        mProfile.gender = p.getInt("gender", 0);
        mProfile.heightCm = p.getFloat("height", 172f);
        mProfile.weightKg = p.getFloat("weight", 65f);
        mProfile.headRatio = p.getFloat("head", 7.5f);
        // 选项表的长度可能变过（加了 / 减了款），存档里的下标先夹回表内再建下拉框
        mProfile.faceShape = clampIndex(p.getInt("face", 0), BodyProfile.FACE_SHAPES.length);
        mProfile.hairStyle = clampIndex(p.getInt("hairStyle", 2), BodyProfile.HAIR_STYLES.length);
        mProfile.bangs = clampIndex(p.getInt("bangs", 0), BodyProfile.BANGS.length);
        mProfile.shoulderR = p.getFloat("shoulderR", 1f);
        mProfile.chestR = p.getFloat("chestR", 1f);
        mProfile.waistR = p.getFloat("waistR", 1f);
        mProfile.hipR = p.getFloat("hipR", 1f);
        // 老存档可能是两个滑块各拉各的存下来的，读进来先按腰臀比协调一次（滑块此时还没建）
        float[] wh = {mProfile.waistR, mProfile.hipR};
        if (mEngine == ENGINE_ANNY) BodyProfile.waistHipAnny(wh, mProfile.gender, 0);
        else BodyProfile.waistHip(wh, mProfile.gender, 0);
        mProfile.waistR = wh[0];
        mProfile.hipR = wh[1];
        mProfile.legR = p.getFloat("legR", 1f);
        mProfile.torsoR = p.getFloat("torsoR", 1f);
        mProfile.armR = p.getFloat("armR", 1f);
        mProfile.neckLenR = p.getFloat("neckLenR", 1f);
        mProfile.muscleR = p.getFloat("muscleR", 1f);
        mProfile.bustR = p.getFloat("bustR", 1f);
        mProfile.bustShape = p.getInt("bustShape", 1);
        mProfile.thighR = p.getFloat("thighR", 1f);
        mProfile.calfR = p.getFloat("calfR", 1f);
        mProfile.footR = p.getFloat("footR", 1f);
        mProfile.footWR = p.getFloat("footWR", 1f);
        mProfile.faceWidthR = p.getFloat("faceWR", 1f);
        mProfile.faceLenR = p.getFloat("faceLR", 1f);
        mProfile.jawR = p.getFloat("jawR", 1f);
        mProfile.chinR = p.getFloat("chinR", 1f);
        mProfile.cheekR = p.getFloat("cheekR", 1f);
        mProfile.foreheadR = p.getFloat("foreheadR", 1f);
        mProfile.browR = p.getFloat("browR", 1f);
        mProfile.eyeSizeR = p.getFloat("eyeSizeR", 1f);
        mProfile.earSizeR = p.getFloat("earSizeR", 1f);
        mProfile.eyeGapR = p.getFloat("eyeGapR", 1f);
        mProfile.noseWR = p.getFloat("noseWR", 1f);
        mProfile.noseHR = p.getFloat("noseHR", 1f);
        mProfile.lipWR = p.getFloat("lipWR", 1f);
        mProfile.lipTR = p.getFloat("lipTR", 1f);
        mProfile.skin = p.getInt("skin", mProfile.skin);
        mProfile.hair = p.getInt("hair", mProfile.hair);
        mProfile.top = p.getInt("top", mProfile.top);
        mProfile.bottom = p.getInt("bottom", mProfile.bottom);
        // Tripo3D：只记 Key 与面数上限，不记生成的模型
        mTripoKey = p.getString("tripo_key", "");
        mTripoFaces = p.getInt("tripo_faces", 20000);
    }

    private void savePrefs() {
        SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        p.edit()
                .putInt("engine", mEngine)
                .putInt("gender", mProfile.gender)
                .putFloat("height", mProfile.heightCm)
                .putFloat("weight", mProfile.weightKg)
                .putFloat("head", mProfile.headRatio)
                .putInt("face", mProfile.faceShape)
                .putInt("hairStyle", mProfile.hairStyle)
                .putFloat("shoulderR", mProfile.shoulderR)
                .putFloat("chestR", mProfile.chestR)
                .putFloat("waistR", mProfile.waistR)
                .putFloat("hipR", mProfile.hipR)
                .putFloat("legR", mProfile.legR)
                .putFloat("torsoR", mProfile.torsoR)
                .putFloat("armR", mProfile.armR)
                .putFloat("neckLenR", mProfile.neckLenR)
                .putFloat("muscleR", mProfile.muscleR)
                .putFloat("bustR", mProfile.bustR)
                .putInt("bustShape", clampIndex(mProfile.bustShape, BodyProfile.BUST_SHAPES.length))
                .putInt("bangs", mProfile.bangs)
                .putFloat("thighR", mProfile.thighR)
                .putFloat("calfR", mProfile.calfR)
                .putFloat("footR", mProfile.footR)
                .putFloat("footWR", mProfile.footWR)
                .putFloat("faceWR", mProfile.faceWidthR)
                .putFloat("faceLR", mProfile.faceLenR)
                .putFloat("jawR", mProfile.jawR)
                .putFloat("chinR", mProfile.chinR)
                .putFloat("cheekR", mProfile.cheekR)
                .putFloat("foreheadR", mProfile.foreheadR)
                .putFloat("browR", mProfile.browR)
                .putFloat("eyeSizeR", mProfile.eyeSizeR)
                .putFloat("earSizeR", mProfile.earSizeR)
                .putFloat("eyeGapR", mProfile.eyeGapR)
                .putFloat("noseWR", mProfile.noseWR)
                .putFloat("noseHR", mProfile.noseHR)
                .putFloat("lipWR", mProfile.lipWR)
                .putFloat("lipTR", mProfile.lipTR)
                .putInt("skin", mProfile.skin)
                .putInt("hair", mProfile.hair)
                .putInt("top", mProfile.top)
                .putInt("bottom", mProfile.bottom)
                .putString("tripo_key", mTripoKey)
                .putInt("tripo_faces", mTripoFaces)
                .apply();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
