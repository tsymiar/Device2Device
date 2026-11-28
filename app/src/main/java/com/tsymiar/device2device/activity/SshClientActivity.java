package com.tsymiar.device2device.activity;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Filter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.entity.SshConnection;
import com.tsymiar.device2device.service.SshClientCore;
import com.tsymiar.device2device.service.SshClientService;
import com.tsymiar.device2device.utils.SshClientHolder;
import com.tsymiar.device2device.utils.SshKeyStore;
import com.tsymiar.device2device.utils.SshKnownHostsStore;
import com.tsymiar.device2device.utils.SshSessionStore;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * SSH 客户端：持久交互 shell + 流式输出，命令历史联想与回溯。
 * 从会话列表进入时自动载入配置与认证方式；也支持「临时连接」的手动表单。
 * 网络操作在后台线程，结果回主线程刷新 UI。
 */
public class SshClientActivity extends AppCompatActivity {
    private static final String TAG = "SshClientActivity";
    private static final String PREFS = "ssh_client_prefs";
    private static final int MAX_HISTORY = 50;
    /** 终端输出缓冲上限：超过后砍掉最旧一段，避免长时间连接把内存吃满 */
    private static final int MAX_OUTPUT = 200_000;
    private static final int KEEP_OUTPUT = 120_000;

    private EditText mHost;
    private EditText mPort;
    private EditText mUser;
    private EditText mPass;
    private Button mConnectBtn;
    private TextView mOutput;
    private ScrollView mScroll;
    private TextView mConnStatus;
    private AutoCompleteTextView mCmd;
    private LinearLayout mConnToggle;
    private TextView mConnArrow;
    private LinearLayout mConnBody;

    private Button mKeyEnter;
    private Button mKeyCtrlC;
    private Button mKeyTab;
    private Button mKeyEsc;
    private Button mKeyLeft;
    private Button mKeyRight;
    private Button mKeyHome;
    private Button mKeyEnd;
    private Button mKeyUp;
    private Button mKeyDown;
    private final Handler mRepeatHandler = new Handler(Looper.getMainLooper());
    private int mLastVisibleHeight;
    private Button mKeyFontMinus;
    private Button mKeyFontPlus;
    private Button mKeyTheme;
    private Button mKeyClear;

    private View mFabSend;
    private View mFabRestore;
    // ↵ 恢复入口可拖动；短按恢复发送键，移动超过阈值则只挪位置
    private boolean mRestoreDragging;
    private boolean mRestorePosConverted;
    private float mRestoreDownRawX, mRestoreDownRawY;
    private int mRestoreStartLeft, mRestoreStartTop;

    private final SshClientCore mCore = SshClientHolder.get().core();
    private boolean mConnecting = false;
    private boolean mConnExpanded = true;
    /** 从会话列表进入时非空；临时连接为 null */
    private SshConnection mConn;

    private List<String> mHistory = new ArrayList<>();
    /** 从远端 shell 静默拉取的命令表，用于本地联想（不写入终端输出） */
    private final List<String> mRemoteCommands = new ArrayList<>();
    private boolean mRemoteLoaded;
    private final List<String> mSuggestions = new ArrayList<>();
    private ArrayAdapter<String> mSuggestAdapter;
    private int mHistoryPos = -1;

    private AnsiRenderer mAnsi;
    private int mFontSize = 12;
    private float mFontSizeFloat = 12f;
    private ScaleGestureDetector mScaleDetector;
    private float mTerminalDownX, mTerminalDownY;
    private int mThemeIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ssh_client);

        mHost = findViewById(R.id.et_host);
        mPort = findViewById(R.id.et_port);
        mUser = findViewById(R.id.et_user);
        mPass = findViewById(R.id.et_pass);
        mConnectBtn = findViewById(R.id.btn_connect);
        mOutput = findViewById(R.id.tv_output);
        mScroll = findViewById(R.id.sv_output);
        mConnStatus = findViewById(R.id.tv_ssh_conn_status);
        mCmd = findViewById(R.id.et_cmd);
        mConnToggle = findViewById(R.id.conn_toggle);
        mConnArrow = findViewById(R.id.conn_toggle_arrow);
        mConnBody = findViewById(R.id.conn_body);
        mKeyEnter = findViewById(R.id.key_enter);
        mKeyCtrlC = findViewById(R.id.key_ctrlc);
        mKeyTab = findViewById(R.id.key_tab);
        mKeyEsc = findViewById(R.id.key_esc);
        mKeyLeft = findViewById(R.id.key_left);
        mKeyRight = findViewById(R.id.key_right);
        mKeyHome = findViewById(R.id.key_home);
        mKeyEnd = findViewById(R.id.key_end);
        mKeyUp = findViewById(R.id.key_up);
        mKeyDown = findViewById(R.id.key_down);
        mKeyFontMinus = findViewById(R.id.key_font_minus);
        mKeyFontPlus = findViewById(R.id.key_font_plus);
        mKeyTheme = findViewById(R.id.key_theme);
        mKeyClear = findViewById(R.id.key_clear);
        mFabSend = findViewById(R.id.fab_send);
        mFabRestore = findViewById(R.id.fab_send_restore);

        mAnsi = new AnsiRenderer(mOutput);
        mFontSize = prefs().getInt("font_size", 12);
        mFontSizeFloat = mFontSize;
        mThemeIndex = prefs().getInt("theme", 0);
        if (mThemeIndex < 0 || mThemeIndex >= THEMES.length) {
            mThemeIndex = 0;
        }
        applyFontSize();
        applyTheme();

        String connId = getIntent().getStringExtra("conn_id");
        mConn = connId == null ? null : SshSessionStore.get(this, connId);
        if (mConn != null) {
            mHost.setText(mConn.host);
            mPort.setText(String.valueOf(mConn.port));
            mUser.setText(mConn.user);
            mPass.setText(mConn.authType == SshConnection.AUTH_PASSWORD ? mConn.password : "");
            setConnExpanded(false);
        } else {
            loadConnection();
        }
        updateConnStatus();

        // 后台可能还连着：恢复缓存输出，接上实时监听，并还原已连接 UI
        String cached = SshClientHolder.get().cachedOutput();
        if (!TextUtils.isEmpty(cached)) {
            mAnsi.resetStyle();
            mAnsi.feed(cached);
            mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
        }
        SshClientHolder.get().setListener(text -> runOnUiThread(() -> appendServerOutput(text)));
        if (mCore.isConnected()) {
            mConnectBtn.setText(R.string.ssh_disconnect);
            setConnectedUi(true);
            updateConnStatus();
            loadRemoteCompletions(); // 从通知/重建回来时补拉一次
        }

        mHistory = loadHistory();
        rebuildSuggestions();
        mCmd.setOnTouchListener((v, event) -> {
            mHistoryPos = -1;
            return false;
        });
        mCmd.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (mCmd.isPopupShowing()) {
                    return false; // 联想下拉自己处理上下选候选
                }
                recallHistory(keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                moveInputCursor(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                moveInputCursor(1);
                return true;
            }
            return false;
        });
        mCmd.setOnEditorActionListener((v, actionId, event) -> {
            sendCommand();
            return true;
        });

        mConnToggle.setOnClickListener(v -> setConnExpanded(!mConnExpanded));

        mKeyEnter.setOnClickListener(v -> sendCommand());
        // 长按悬浮发送键隐藏它，原位置留下一个恢复入口
        mKeyEnter.setOnLongClickListener(v -> {
            mFabSend.setVisibility(View.GONE);
            mFabRestore.setVisibility(View.VISIBLE);
            return true;
        });
        mFabRestore.setOnClickListener(v -> {
            mFabSend.setVisibility(View.VISIBLE);
            mFabRestore.setVisibility(View.GONE);
        });
        // 拖动 ↵ 入口：按下不动/短按触发上面的恢复点击，移动则只改位置
        mFabRestore.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mRestoreDragging = false;
                    mRestoreDownRawX = event.getRawX();
                    mRestoreDownRawY = event.getRawY();
                    mRestoreStartLeft = mFabRestore.getLeft();
                    mRestoreStartTop = mFabRestore.getTop();
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getRawX() - mRestoreDownRawX) > 12
                            || Math.abs(event.getRawY() - mRestoreDownRawY) > 12) {
                        mRestoreDragging = true;
                        convertRestoreToAbsolute();
                        FrameLayout.LayoutParams lp =
                                (FrameLayout.LayoutParams) mFabRestore.getLayoutParams();
                        int nx = mRestoreStartLeft + (int) (event.getRawX() - mRestoreDownRawX);
                        int ny = mRestoreStartTop + (int) (event.getRawY() - mRestoreDownRawY);
                        ViewGroup parent = (ViewGroup) mFabRestore.getParent();
                        nx = Math.max(0, Math.min(nx, parent.getWidth() - mFabRestore.getWidth()));
                        ny = Math.max(0, Math.min(ny, parent.getHeight() - mFabRestore.getHeight()));
                        lp.leftMargin = nx;
                        lp.topMargin = ny;
                        mFabRestore.setLayoutParams(lp);
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mRestoreDragging) {
                        mRestoreDragging = false;
                        return true; // 拖动结束不当点击，避免误恢复
                    }
                    return false;
                default:
                    return false;
            }
        });
        // 内置客户端为「本地行编辑 + 回车整行发送」，编辑键不能发 ESC 序列给服务端
        mKeyCtrlC.setOnClickListener(v -> {
            clearInputLine();
            mCore.send(new byte[]{0x03});
        });
        mKeyTab.setOnClickListener(v -> completeCommand());
        mKeyEsc.setOnClickListener(v -> clearInputLine());
        mKeyHome.setOnClickListener(v -> moveInputCursorTo(0));
        mKeyEnd.setOnClickListener(v -> moveInputCursorTo(mCmd.length()));
        attachRepeat(mKeyUp, () -> recallHistory(-1));
        attachRepeat(mKeyDown, () -> recallHistory(1));
        attachRepeat(mKeyLeft, () -> moveInputCursor(-1));
        attachRepeat(mKeyRight, () -> moveInputCursor(1));
        mKeyFontMinus.setOnClickListener(v -> changeFontSize(-1));
        mKeyFontPlus.setOnClickListener(v -> changeFontSize(1));
        mKeyTheme.setOnClickListener(v -> {
            mThemeIndex = (mThemeIndex + 1) % THEMES.length;
            applyTheme();
            Toast.makeText(this, getString(R.string.ssh_theme_prefix, THEMES[mThemeIndex].name), Toast.LENGTH_SHORT).show();
        });
        mKeyClear.setOnClickListener(v -> clearHistory());

        mConnectBtn.setOnClickListener(v -> {
            if (mCore.isConnected() || mConnecting) {
                disconnect();
            } else {
                connect();
            }
        });

        // ScrollView 会消费 click，用 ACTION_UP 位移判断「点击」
        mScroll.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mTerminalDownX = event.getX();
                    mTerminalDownY = event.getY();
                    break;
                case MotionEvent.ACTION_UP:
                    float dx = Math.abs(event.getX() - mTerminalDownX);
                    float dy = Math.abs(event.getY() - mTerminalDownY);
                    if (dx < 12 && dy < 12) {
                        if (mConnExpanded) {
                            setConnExpanded(false);
                        }
                        focusTerminalInput();
                    }
                    break;
                default:
                    break;
            }
            return false; // 不拦截，保留终端自身滚动与双指缩放
        });

        // 双指缩放调整终端字号；用整个 Activity 的触摸分发，输入框/终端都能响应
        mScaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        mFontSizeFloat = mFontSize;
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        mFontSizeFloat = Math.max(5f,
                                Math.min(30f, mFontSizeFloat * detector.getScaleFactor()));
                        int next = Math.round(mFontSizeFloat);
                        if (next != mFontSize) {
                            mFontSize = next;
                            applyFontSize();
                            prefs().edit().putInt("font_size", mFontSize).apply();
                        }
                        return true;
                    }
                });

        // 键盘弹出（可用高度变小）时重排会让之前的滚动失效，再滚一次到底部
        View root = findViewById(R.id.ssh_client_root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect r = new Rect();
            root.getWindowVisibleDisplayFrame(r);
            int visibleHeight = r.height();
            if (mLastVisibleHeight == 0) {
                mLastVisibleHeight = visibleHeight;
                return;
            }
            int diff = mLastVisibleHeight - visibleHeight;
            mLastVisibleHeight = visibleHeight;
            if (diff > 100) {
                mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mScaleDetector != null) {
            mScaleDetector.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    private void setConnExpanded(boolean expanded) {
        mConnExpanded = expanded;
        mConnBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
        mConnArrow.setText(expanded ? "▾" : "▴");
    }

    /** 点终端任意空白处：让嵌在终端里的输入框拿焦点并弹键盘 */
    private void focusTerminalInput() {
        if (!mCore.isConnected() || mCmd == null || !mCmd.isEnabled()) {
            return;
        }
        mCmd.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(mCmd, InputMethodManager.SHOW_IMPLICIT);
        }
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** 首次拖动时把 gravity 定位换成绝对 margin，避免布局重排后位置跑掉 */
    private void convertRestoreToAbsolute() {
        if (mRestorePosConverted) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mFabRestore.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = mFabRestore.getLeft();
        lp.topMargin = mFabRestore.getTop();
        lp.rightMargin = 0;
        lp.bottomMargin = 0;
        mFabRestore.setLayoutParams(lp);
        mRestorePosConverted = true;
    }

    @Override
    protected void onDestroy() {
        // 连接交给进程级持有者：Activity 销毁不断开，只摘掉 UI 监听避免泄漏
        SshClientHolder.get().setListener(null);
        mRepeatHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mCore.isConnected()) {
            // 只结束本页，连接由前台服务保持
            finish();
        } else {
            super.onBackPressed();
        }
    }

    private void connect() {
        final String host = mHost.getText().toString().trim();
        final String portStr = mPort.getText().toString().trim();
        final String user = mUser.getText().toString().trim();
        final String pass = mPass.getText().toString();
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(user)) {
            Toast.makeText(this, R.string.ssh_client_need_host_user, Toast.LENGTH_SHORT).show();
            return;
        }
        int parsedPort;
        try {
            parsedPort = Integer.parseInt(TextUtils.isEmpty(portStr) ? "22" : portStr);
        } catch (NumberFormatException e) {
            parsedPort = 22;
        }
        final int port = parsedPort;

        SshClientCore.Auth auth;
        if (mConn != null && mConn.authType == SshConnection.AUTH_KEY) {
            File keyFile = SshKeyStore.file(this, mConn.keyFile);
            if (keyFile == null || !keyFile.exists()) {
                Toast.makeText(this, R.string.ssh_session_need_key, Toast.LENGTH_SHORT).show();
                return;
            }
            auth = SshClientCore.Auth.key(keyFile.getAbsolutePath(), mConn.keyPassphrase, pass);
            mConn.host = host;
            mConn.port = port;
            mConn.user = user;
        } else {
            auth = SshClientCore.Auth.password(pass);
            if (mConn != null) {
                mConn.host = host;
                mConn.port = port;
                mConn.user = user;
                mConn.password = pass;
            } else {
                saveConnection(host, portStr, user, pass);
            }
        }
        if (mConn != null) {
            SshSessionStore.save(this, mConn);
            SshSessionStore.setLastId(this, mConn.id);
            updateConnStatus();
        }

        mConnecting = true;
        setConnectedUi(false);
        mConnectBtn.setEnabled(false);
        SshClientHolder.get().clearOutput();
        mOutput.setText("");
        mRemoteLoaded = false;
        mRemoteCommands.clear();
        appendOutput("→ 正在连接 " + user + "@" + host + ":" + port + " …\n");

        // 先挂监听，避免错过开场 banner
        SshClientHolder.get().setListener(text -> runOnUiThread(() -> appendServerOutput(text)));

        new Thread(() -> {
            try {
                mCore.connect(getApplicationContext(), host, port, user, auth);
                runOnUiThread(() -> {
                    mConnecting = false;
                    mConnectBtn.setText(R.string.ssh_disconnect);
                    mConnectBtn.setEnabled(true);
                    setConnectedUi(true);
                    updateConnStatus();
                    // 起前台服务：状态栏常驻，下拉点击回到本会话
                    String title = user + "@" + host + ":" + port;
                    SshClientHolder.get().setSession(mConn == null ? null : mConn.id, title);
                    SshClientService.start(getApplicationContext(), title);
                    loadRemoteCompletions();
                    appendOutput("✔ 已连接。主机指纹 " + mCore.getServerFingerprint()
                            + "\n提示：Tab/Esc/Ctrl 组合键和方向键已在下方工具栏。\n");
                });
            } catch (SshClientCore.HostKeyChangedException e) {
                runOnUiThread(() -> showHostKeyWarning(host, port, e));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    resetAfterFailure();
                    appendOutput("✘ 连接失败：" + e.getMessage() + "\n");
                });
            }
        }).start();
    }

    private void resetAfterFailure() {
        mConnecting = false;
        mConnectBtn.setText(R.string.ssh_connect);
        mConnectBtn.setEnabled(true);
        setConnectedUi(false);
        setInputsEnabled(true);
    }

    /** 主机密钥变了：默认拒绝，用户明确确认后才清掉旧指纹重连 */
    private void showHostKeyWarning(String host, int port, SshClientCore.HostKeyChangedException e) {
        resetAfterFailure();
        String msg = "服务器 " + host + ":" + port + " 的主机密钥与上次记录不一致。\n\n"
                + "上次：" + e.expected + "\n本次：" + e.actual
                + "\n\n若服务器确实重装过，可信任新密钥并重试。";
        new AlertDialog.Builder(this)
                .setTitle(R.string.ssh_hostkey_changed)
                .setMessage(msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("信任并重试", (dialog, which) -> {
                    SshKnownHostsStore.forget(this, host, port);
                    connect();
                })
                .show();
        appendOutput(getString(R.string.ssh_host_key_changed) + "\n");
    }

    private void disconnect() {
        new Thread(() -> {
            mCore.disconnect();
            SshClientService.stop(getApplicationContext());
            runOnUiThread(() -> {
                mConnectBtn.setText(R.string.ssh_connect);
                setConnectedUi(false);
                setInputsEnabled(true);
                updateConnStatus();
                appendOutput("— 已断开连接 —\n");
            });
        }).start();
    }

    private void setConnectedUi(boolean connected) {
        Button[] keys = {mKeyEnter, mKeyCtrlC, mKeyTab, mKeyEsc, mKeyLeft, mKeyRight,
                mKeyHome, mKeyEnd, mKeyUp, mKeyDown};
        for (Button b : keys) {
            b.setEnabled(connected);
        }
        mCmd.setEnabled(connected);
        setInputsEnabled(!connected);
    }

    private void sendCommand() {
        if (!mCore.isConnected()) {
            Toast.makeText(this, R.string.ssh_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        Editable e = mCmd.getText();
        final String cmd = e != null ? e.toString() : "";
        mCmd.setText("");
        mHistoryPos = -1;
        if (!cmd.trim().isEmpty()) {
            addHistory(cmd.trim());
        }
        // clear 是纯显示效果：本地直接清屏，不发服务端（否则服务端刷 40 空行，旧内容还在）
        if ("clear".equalsIgnoreCase(cmd.trim())) {
            SshClientHolder.get().clearOutput();
            mOutput.setText("");
            mAnsi.resetStyle();
            mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
            return;
        }
        mCore.send(cmd + "\n");
    }

    /** 箭头键长按连续触发：按下立即执行一次，长按后定时重复，松手停止 */
    private void attachRepeat(View view, final Runnable action) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    action.run();
                    mRepeatHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            action.run();
                            mRepeatHandler.postDelayed(this, 90);
                        }
                    }, 400);
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mRepeatHandler.removeCallbacksAndMessages(null);
                    v.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });
    }

    /** dir<0 上溯更旧，dir>0 下溯更新 */
    private void recallHistory(int dir) {
        if (mHistory.isEmpty()) {
            return;
        }
        if (dir < 0) {
            if (mHistoryPos < 0) {
                mHistoryPos = mHistory.size();
            }
            mHistoryPos = Math.max(0, mHistoryPos - 1);
        } else {
            if (mHistoryPos < 0) {
                return;
            }
            mHistoryPos++;
            if (mHistoryPos >= mHistory.size()) {
                mHistoryPos = -1;
                setCmdTextNoSuggest("");
                return;
            }
        }
        setCmdTextNoSuggest(mHistory.get(mHistoryPos));
    }

    /** 程序化回填历史时临时摘掉 adapter，避免触发联想下拉预选框 */
    private void setCmdTextNoSuggest(String text) {
        mCmd.setAdapter(null);
        mCmd.setText(text);
        mCmd.setSelection(mCmd.getText().length());
        mCmd.setAdapter(mSuggestAdapter);
    }

    /** 在本地输入框内移动光标（内置客户端靠本地行编辑，不把 ESC 发给服务端） */
    private void moveInputCursor(int delta) {
        if (!mCmd.isEnabled()) {
            return;
        }
        moveInputCursorTo(Math.max(0, Math.min(mCmd.length(), mCmd.getSelectionStart() + delta)));
    }

    /** 光标跳到指定位置并重新聚焦输入框（按钮点击会抢焦点，不聚焦就看不到光标） */
    private void moveInputCursorTo(int pos) {
        if (!mCmd.isEnabled()) {
            return;
        }
        mCmd.setSelection(Math.max(0, Math.min(mCmd.length(), pos)));
        mCmd.requestFocus();
    }

    private void clearInputLine() {
        if (!mCmd.isEnabled()) {
            return;
        }
        mCmd.setText("");
        mHistoryPos = -1;
        mCmd.requestFocus();
    }

    /** Tab 本地补全：取第一个以当前输入为前缀的联想候选 */
    private void completeCommand() {
        if (!mCmd.isEnabled() || mSuggestions.isEmpty()) {
            return;
        }
        String prefix = mCmd.getText().toString();
        if (prefix.isEmpty()) {
            return;
        }
        for (String c : mSuggestions) {
            if (c.startsWith(prefix) && !c.equals(prefix)) {
                mCmd.setText(c);
                mCmd.setSelection(c.length());
                return;
            }
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void loadConnection() {
        SharedPreferences sp = prefs();
        mHost.setText(sp.getString("host", ""));
        mPort.setText(sp.getString("port", "22"));
        mUser.setText(sp.getString("user", ""));
        mPass.setText(sp.getString("pass", ""));
    }

    private void saveConnection(String host, String port, String user, String pass) {
        prefs().edit()
                .putString("host", host)
                .putString("port", port)
                .putString("user", user)
                .putString("pass", pass)
                .apply();
    }

    private List<String> loadHistory() {
        List<String> list = new ArrayList<>();
        String raw = prefs().getString("cmd_history", null);
        if (raw == null) {
            return list;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    private void saveHistory() {
        JSONArray arr = new JSONArray();
        for (String s : mHistory) {
            arr.put(s);
        }
        prefs().edit().putString("cmd_history", arr.toString()).apply();
    }

    private void addHistory(String cmd) {
        mHistory.remove(cmd);
        mHistory.add(cmd);
        while (mHistory.size() > MAX_HISTORY) {
            mHistory.remove(0);
        }
        saveHistory();
        rebuildSuggestions();
    }

    private void setInputsEnabled(boolean enabled) {
        mHost.setEnabled(enabled);
        mPort.setEnabled(enabled);
        mUser.setEnabled(enabled);
        mPass.setEnabled(enabled);
    }

    /** 服务端输出：流式渲染，保留 ANSI 样式状态 */
    private void appendServerOutput(String text) {
        mAnsi.feed(text);
        trimOutput();
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** 本地提示：先复位颜色，否则会被上一条彩色输出染色 */
    private void appendOutput(String text) {
        mAnsi.resetStyle();
        mAnsi.feed(text);
        trimOutput();
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void trimOutput() {
        CharSequence cs = mOutput.getText();
        if (cs instanceof SpannableStringBuilder && cs.length() > MAX_OUTPUT) {
            SpannableStringBuilder sb = (SpannableStringBuilder) cs;
            sb.delete(0, sb.length() - KEEP_OUTPUT);
        }
    }

    private void changeFontSize(int delta) {
        int next = mFontSize + delta;
        if (next < 5) {
            next = 5;
        }
        if (next > 30) {
            next = 30;
        }
        mFontSize = next;
        mFontSizeFloat = next;
        applyFontSize();
        prefs().edit().putInt("font_size", mFontSize).apply();
    }

    private void applyFontSize() {
        mOutput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, mFontSize);
        // 输入行比输出小一号，缩放时同步跟随
        if (mCmd != null) {
            mCmd.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Math.max(8, mFontSize - 1));
        }
    }

    // ---------- 终端主题（JuiceSSH 的 Color scheme） ----------

    private static final class TerminalTheme {
        final String name;
        final int bg;
        final int fg;

        TerminalTheme(String name, int bg, int fg) {
            this.name = name;
            this.bg = bg;
            this.fg = fg;
        }
    }

    private static final TerminalTheme[] THEMES = {
            new TerminalTheme("墨青", 0xFF0B1219, 0xFFC8E6DF),
            new TerminalTheme("绿屏", 0xFF000000, 0xFF33FF66),
            new TerminalTheme("浅色", 0xFFFFFFFF, 0xFF1F2937),
            new TerminalTheme("Solarized", 0xFF002B36, 0xFF93A1A1)
    };

    private void applyTheme() {
        TerminalTheme t = THEMES[mThemeIndex];
        mScroll.setBackgroundColor(t.bg);
        mOutput.setTextColor(t.fg);
        if (mCmd != null) {
            mCmd.setTextColor(t.fg);
            mCmd.setHintTextColor(t.fg);
        }
        prefs().edit().putInt("theme", mThemeIndex).apply();
    }

    private void updateConnStatus() {
        String name = mConn == null ? getString(R.string.ssh_temp_connection) : mConn.displayName();
        String fp = mCore.getServerFingerprint();
        String fpSuffix = TextUtils.isEmpty(fp) ? ""
                : getString(R.string.ssh_fingerprint_suffix, fp.substring(0, Math.min(fp.length(), 24)));
        mConnStatus.setText(getString(R.string.ssh_conn_status, name, fpSuffix));
    }

    /** 静默拉取对端命令表与 bash 历史，作为输入框联想候选；不写入终端输出 */
    private void loadRemoteCompletions() {
        if (mRemoteLoaded || !mCore.isConnected()) {
            return;
        }
        mRemoteLoaded = true;
        new Thread(() -> {
            List<String> cmds = new ArrayList<>();
            List<String> hist = new ArrayList<>();
            try {
                String raw = mCore.execRemote("bash -lc 'compgen -c 2>/dev/null' | sort -u | head -600");
                if (TextUtils.isEmpty(raw.trim())) {
                    raw = mCore.execRemote("ls /bin /usr/bin /usr/local/bin 2>/dev/null | sort -u | head -600");
                }
                for (String line : raw.split("\\r?\\n")) {
                    String s = line.trim();
                    // 只剔除含空白/控制字符的异常行，允许中文与特殊字符命令
                    if (!s.isEmpty() && s.length() <= 40 && !s.matches(".*[\\p{Cntrl}\\s].*")) {
                        cmds.add(s);
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                String raw = mCore.execRemote("tail -n 200 ~/.bash_history 2>/dev/null");
                for (String line : raw.split("\\r?\\n")) {
                    String s = line.trim();
                    if (!s.isEmpty() && s.length() <= 200) {
                        hist.add(0, s);
                    }
                }
            } catch (Exception ignored) {
            }
            final List<String> remoteCmds = cmds;
            final List<String> remoteHist = hist;
            runOnUiThread(() -> {
                mRemoteCommands.clear();
                mRemoteCommands.addAll(remoteCmds);
                for (String h : remoteHist) {
                    if (!mHistory.contains(h)) {
                        mHistory.add(h);
                    }
                }
                rebuildSuggestions();
                if (!remoteCmds.isEmpty()) {
                    appendOutput("✔ 已从对端载入 " + remoteCmds.size() + " 条命令候选；Tab 补全走对端 shell。\n");
                }
            });
        }, "ssh-load-completions").start();
    }

    /** 联想基础候选，运行时与历史命令合并去重 */
    private static final String[] COMMON_COMMANDS = {
            "ls", "ls -l", "ls -la", "cd ..", "pwd", "cat", "tail -f", "head",
            "grep", "find", "chmod +x", "cp", "mv", "rm", "mkdir", "touch",
            "ps", "top", "df -h", "du -sh", "free -m", "uname -a", "whoami",
            "id", "date", "uptime", "clear", "history", "exit",
            "ifconfig", "ip addr", "ping", "ssh", "scp", "tar -xzvf", "unzip",
            "vim", "nano", "sudo", "systemctl status", "docker ps"
    };

    private void rebuildSuggestions() {
        mSuggestions.clear();
        // 远端真实命令优先，其次是内置常用命令与本地历史
        for (String c : mRemoteCommands) {
            if (!mSuggestions.contains(c)) {
                mSuggestions.add(c);
            }
        }
        for (String c : COMMON_COMMANDS) {
            if (!mSuggestions.contains(c)) {
                mSuggestions.add(c);
            }
        }
        for (int i = mHistory.size() - 1; i >= 0; i--) {
            String h = mHistory.get(i);
            if (!mSuggestions.contains(h)) {
                mSuggestions.add(h);
            }
        }
        // 重建适配器：ArrayAdapter 过滤缓存不随 notifyDataSetChanged 刷新，只改后备列表会残留旧候选
        mSuggestAdapter = new AutoHeightAdapter(mSuggestions);
        if (mCmd != null) {
            mCmd.setAdapter(mSuggestAdapter);
        }
    }

    /** 过滤后按候选行数调整下拉高度：少于 4 行时贴合内容，最多 4 行 */
    private final class AutoHeightAdapter extends ArrayAdapter<String> {
        private final List<String> all;
        private final int rowPx;
        private final int maxHeightPx;

        AutoHeightAdapter(List<String> items) {
            super(SshClientActivity.this, R.layout.item_suggestion, new ArrayList<>(items));
            // 必须持有快照：performFiltering 在后台线程遍历，原列表可能被主线程 rebuild 修改
            all = new ArrayList<>(items);
            rowPx = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    26, getResources().getDisplayMetrics()));
            maxHeightPx = rowPx * 4;
        }

        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults r = new FilterResults();
                    List<String> out = new ArrayList<>();
                    try {
                        String q = constraint == null ? "" : constraint.toString().toLowerCase();
                        for (String s : all) {
                            if (s != null && (q.isEmpty() || s.toLowerCase().startsWith(q))) {
                                out.add(s);
                            }
                        }
                    } catch (Exception ignored) {
                        // 必须自己兜住，否则 Filter 框架会向 publishResults 传 values=null 导致闪退
                    }
                    r.values = out;
                    r.count = out.size();
                    return r;
                }

                @Override
                @SuppressWarnings("unchecked")
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    // 页面销毁或 results.values 为 null（框架兜底路径）时直接跳过
                    if (isFinishing() || isDestroyed() || results == null
                            || !(results.values instanceof List)) {
                        return;
                    }
                    try {
                        clear();
                        addAll((List<String>) results.values);
                        notifyDataSetChanged();
                        int rows = Math.min(results.count, 4);
                        int height = rows <= 0 ? rowPx : Math.min(maxHeightPx, rows * rowPx);
                        mCmd.setDropDownHeight(height);
                    } catch (Exception ignored) {
                    }
                }
            };
        }
    }

    /** 清空历史（保留常见命令） */
    private void clearHistory() {
        mHistory.clear();
        mHistoryPos = -1;
        saveHistory();
        rebuildSuggestions();
        if (mCmd != null) {
            mCmd.dismissDropDown();
        }
        Toast.makeText(this, R.string.ssh_cleared_history, Toast.LENGTH_SHORT).show();
    }

    /**
     * 流式 ANSI 渲染器：SGR 转 Span，丢弃光标/清屏等序列；
     * 跨数据块保持样式，并缓存被截断的半个转义序列。
     */
    private static final class AnsiRenderer {
        private final TextView target;
        private final StringBuilder pending = new StringBuilder();

        private int fg = -1;
        private int bg = -1;
        private boolean bold, italic, underline, inverse;

        /** xterm 8 色 + 高亮 8 色（Tango） */
        private static final int[] BASE = {
                0xFF000000, 0xFFCC0000, 0xFF4E9A06, 0xFFC4A000,
                0xFF3465A4, 0xFF75507B, 0xFF06989A, 0xFFD3D7CF };
        private static final int[] BRIGHT = {
                0xFF555753, 0xFFEF2929, 0xFF8AE234, 0xFFFCE94F,
                0xFF729FCF, 0xFFAD7FA8, 0xFF34E2E2, 0xFFEEEEEC };

        AnsiRenderer(TextView target) {
            this.target = target;
        }

        void resetStyle() {
            fg = -1;
            bg = -1;
            bold = italic = underline = inverse = false;
        }

        void feed(String chunk) {
            String data = pending.length() == 0 ? chunk : pending + chunk;
            pending.setLength(0);
            SpannableStringBuilder out = new SpannableStringBuilder();
            int segStart = 0;
            int i = 0;
            int n = data.length();
            while (i < n) {
                char c = data.charAt(i);
                if (c == 0x1B) {
                    if (i > segStart) {
                        styled(out, data, segStart, i);
                    }
                    int next = handleEscape(data, i);
                    if (next < 0) {
                        // 转义序列被数据块截断，留到下次
                        pending.append(data, i, n);
                        target.append(out);
                        return;
                    }
                    i = next;
                    segStart = i;
                } else {
                    i++;
                }
            }
            if (n > segStart) {
                styled(out, data, segStart, n);
            }
            if (out.length() > 0) {
                target.append(out);
            }
        }

        private void styled(SpannableStringBuilder out, String data, int start, int end) {
            String text = data.substring(start, end);
            if (text.isEmpty()) {
                return;
            }
            SpannableStringBuilder sb = new SpannableStringBuilder(text);
            int len = sb.length();
            int f = inverse ? bg : fg;
            int b = inverse ? fg : bg;
            if (f != -1) {
                sb.setSpan(new ForegroundColorSpan(f), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (b != -1) {
                sb.setSpan(new BackgroundColorSpan(b), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (bold) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (italic) {
                sb.setSpan(new StyleSpan(Typeface.ITALIC), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (underline) {
                sb.setSpan(new UnderlineSpan(), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            out.append(sb);
        }

        /** 返回序列结束下标；被截断返回 -1（调用方缓存剩余部分） */
        private int handleEscape(String data, int i) {
            int n = data.length();
            if (i + 1 >= n) {
                return -1;
            }
            char kind = data.charAt(i + 1);
            if (kind == '[') {
                int j = i + 2;
                while (j < n) {
                    char ch = data.charAt(j);
                    if (ch >= 0x40 && ch <= 0x7E) {
                        if (ch == 'm') {
                            applySgr(data, i + 2, j);
                        }
                        return j + 1;
                    }
                    j++;
                }
                return -1;
            }
            if (kind == ']') {
                int j = i + 2;
                while (j < n) {
                    char ch = data.charAt(j);
                    if (ch == 0x07) {
                        return j + 1;
                    }
                    if (ch == 0x1B && j + 1 < n && data.charAt(j + 1) == '\\') {
                        return j + 2;
                    }
                    j++;
                }
                return -1;
            }
            return i + 2;
        }

        private void applySgr(String data, int start, int end) {
            if (end <= start) {
                resetStyle();
                return;
            }
            String[] parts = data.substring(start, end).split(";", -1);
            int k = 0;
            while (k < parts.length) {
                int code = parts[k].isEmpty() ? 0 : parseInt(parts[k]);
                if (code == 0) {
                    resetStyle();
                } else if (code == 1) {
                    bold = true;
                } else if (code == 22) {
                    bold = false;
                } else if (code == 3) {
                    italic = true;
                } else if (code == 23) {
                    italic = false;
                } else if (code == 4) {
                    underline = true;
                } else if (code == 24) {
                    underline = false;
                } else if (code == 7) {
                    inverse = true;
                } else if (code == 27) {
                    inverse = false;
                } else if (code >= 30 && code <= 37) {
                    fg = BASE[code - 30];
                } else if (code == 39) {
                    fg = -1;
                } else if (code >= 90 && code <= 97) {
                    fg = BRIGHT[code - 90];
                } else if (code >= 40 && code <= 47) {
                    bg = BASE[code - 40];
                } else if (code == 49) {
                    bg = -1;
                } else if (code >= 100 && code <= 107) {
                    bg = BRIGHT[code - 100];
                } else if (code == 38 || code == 48) {
                    // 扩展色：38;5;n（256 色）或 38;2;r;g;b（真彩色）
                    if (k + 1 < parts.length) {
                        int mode = parseInt(parts[k + 1]);
                        if (mode == 5 && k + 2 < parts.length) {
                            int color = xterm256(parseInt(parts[k + 2]));
                            if (code == 38) {
                                fg = color;
                            } else {
                                bg = color;
                            }
                            k += 2;
                        } else if (mode == 2 && k + 4 < parts.length) {
                            int r = clamp(parseInt(parts[k + 2]));
                            int g = clamp(parseInt(parts[k + 3]));
                            int b = clamp(parseInt(parts[k + 4]));
                            int color = Color.rgb(r, g, b);
                            if (code == 38) {
                                fg = color;
                            } else {
                                bg = color;
                            }
                            k += 4;
                        }
                    }
                }
                k++;
            }
        }

        private static int parseInt(String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static int clamp(int v) {
            return v < 0 ? 0 : (v > 255 ? 255 : v);
        }

        private static int xterm256(int idx) {
            if (idx < 0) {
                idx = 0;
            }
            if (idx > 255) {
                idx = 255;
            }
            if (idx < 8) {
                return BASE[idx];
            }
            if (idx < 16) {
                return BRIGHT[idx - 8];
            }
            if (idx < 232) {
                int c = idx - 16;
                int r = c / 36;
                int g = (c % 36) / 6;
                int b = c % 6;
                return Color.rgb(lev(r), lev(g), lev(b));
            }
            int v = 8 + (idx - 232) * 10;
            return Color.rgb(v, v, v);
        }

        private static int lev(int x) {
            return x == 0 ? 0 : 55 + x * 40;
        }
    }
}
