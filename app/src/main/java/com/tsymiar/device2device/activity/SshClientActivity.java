package com.tsymiar.device2device.activity;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.service.SshClientCore;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * SSH 客户端：连接任意 SSH 服务器，持久交互 shell + 流式输出，命令历史联想与回溯。
 * 输入区可折叠；控制键（↵ / Ctrl-C / ↑↓ / Tab 等）直接发往对端。
 * 网络操作在后台线程，结果回主线程刷新 UI。
 */
public class SshClientActivity extends AppCompatActivity {
    private static final String TAG = "SshClientActivity";
    private static final String PREFS = "ssh_client_prefs";
    private static final int MAX_HISTORY = 50;

    private EditText mHost;
    private EditText mPort;
    private EditText mUser;
    private EditText mPass;
    private Button mConnectBtn;
    private TextView mOutput;
    private ScrollView mScroll;
    private AutoCompleteTextView mCmd;
    private LinearLayout mConnToggle;
    private TextView mConnArrow;
    private LinearLayout mConnBody;
    private Button mKeyEnter;
    private Button mKeyCtrlC;
    private Button mKeyUp;
    private Button mKeyDown;
    private Button mKeyFontMinus;
    private Button mKeyFontPlus;
    private Button mKeyClear;

    private final SshClientCore mCore = new SshClientCore();
    private boolean mConnecting = false;
    private boolean mConnExpanded = true;

    /** 命令历史，最新在末尾 */
    private List<String> mHistory = new ArrayList<>();
    private final List<String> mSuggestions = new ArrayList<>();
    private ArrayAdapter<String> mSuggestAdapter;
    /** 回溯位置，-1 表示不在回溯态 */
    private int mHistoryPos = -1;

    /** ANSI 渲染器：跨数据块保持样式状态 */
    private AnsiRenderer mAnsi;
    private int mFontSize = 12;

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
        mCmd = findViewById(R.id.et_cmd);
        mConnToggle = findViewById(R.id.conn_toggle);
        mConnArrow = findViewById(R.id.conn_toggle_arrow);
        mConnBody = findViewById(R.id.conn_body);
        mKeyEnter = findViewById(R.id.key_enter);
        mKeyCtrlC = findViewById(R.id.key_ctrlc);
        mKeyUp = findViewById(R.id.key_up);
        mKeyDown = findViewById(R.id.key_down);
        mKeyFontMinus = findViewById(R.id.key_font_minus);
        mKeyFontPlus = findViewById(R.id.key_font_plus);
        mKeyClear = findViewById(R.id.key_clear);

        mAnsi = new AnsiRenderer(mOutput);
        mFontSize = prefs().getInt("font_size", 12);
        applyFontSize();

        loadConnection();

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
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                recallHistory(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                recallHistory(1);
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
        mKeyCtrlC.setOnClickListener(v -> mCore.send(new byte[] { 0x03 }));
        mKeyUp.setOnClickListener(v -> recallHistory(-1));
        mKeyDown.setOnClickListener(v -> recallHistory(1));
        mKeyFontMinus.setOnClickListener(v -> changeFontSize(-1));
        mKeyFontPlus.setOnClickListener(v -> changeFontSize(1));
        mKeyClear.setOnClickListener(v -> clearHistory());

        mConnectBtn.setOnClickListener(v -> {
            if (mCore.isConnected() || mConnecting) {
                disconnect();
            } else {
                connect();
            }
        });
    }

    private void setConnExpanded(boolean expanded) {
        mConnExpanded = expanded;
        mConnBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
        mConnArrow.setText(expanded ? "▾" : "▴");
    }

    @Override
    protected void onDestroy() {
        mCore.disconnect();
        super.onDestroy();
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

        saveConnection(host, portStr, user, pass);

        mConnecting = true;
        setConnectedUi(false);
        mConnectBtn.setEnabled(false);
        appendOutput("→ 正在连接 " + user + "@" + host + ":" + port + " …\n");

        // 先挂监听，避免错过开场 banner
        mCore.setOutputListener(text -> runOnUiThread(() -> appendServerOutput(text)));

        new Thread(() -> {
            try {
                mCore.connect(getApplicationContext(), host, port, user, pass);
                runOnUiThread(() -> {
                    mConnecting = false;
                    mConnectBtn.setText(R.string.ssh_disconnect);
                    mConnectBtn.setEnabled(true);
                    setConnectedUi(true);
                    appendOutput("✔ 已连接。点 「↵ 发送」执行命令；可用 ↑↓ 回溯历史。\n");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    mConnecting = false;
                    mConnectBtn.setText(R.string.ssh_connect);
                    mConnectBtn.setEnabled(true);
                    setConnectedUi(false);
                    setInputsEnabled(true);
                    appendOutput("✘ 连接失败：" + e.getMessage() + "\n");
                });
            }
        }).start();
    }

    private void disconnect() {
        new Thread(() -> {
            mCore.disconnect();
            runOnUiThread(() -> {
                mConnectBtn.setText(R.string.ssh_connect);
                setConnectedUi(false);
                setInputsEnabled(true);
                appendOutput("— 已断开连接 —\n");
            });
        }).start();
    }

    private void setConnectedUi(boolean connected) {
        mCmd.setEnabled(connected);
        mKeyEnter.setEnabled(connected);
        mKeyCtrlC.setEnabled(connected);
        mKeyUp.setEnabled(connected);
        mKeyDown.setEnabled(connected);
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
            // 不回显命令：服务端 PTY 会回显，否则重复
            addHistory(cmd.trim());
        }
        mCore.send(cmd + "\n");
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
                mCmd.setText("");
                return;
            }
        }
        mCmd.setText(mHistory.get(mHistoryPos));
        mCmd.setSelection(mCmd.getText().length());
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
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** 本地提示：先复位颜色，否则会被上一条彩色输出染色 */
    private void appendOutput(String text) {
        mAnsi.resetStyle();
        mAnsi.feed(text);
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
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
        applyFontSize();
        prefs().edit().putInt("font_size", mFontSize).apply();
    }

    private void applyFontSize() {
        mOutput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, mFontSize);
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
        for (String c : COMMON_COMMANDS) {
            mSuggestions.add(c);
        }
        for (int i = mHistory.size() - 1; i >= 0; i--) {
            String h = mHistory.get(i);
            if (!mSuggestions.contains(h)) {
                mSuggestions.add(h);
            }
        }
        // 重建适配器：ArrayAdapter 过滤缓存不随 notifyDataSetChanged 刷新，只改后备列表会残留旧候选
        mSuggestAdapter = new ArrayAdapter<>(this, R.layout.item_suggestion, new ArrayList<>(mSuggestions));
        if (mCmd != null) {
            mCmd.setAdapter(mSuggestAdapter);
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
        Toast.makeText(this, "已清空命令历史", Toast.LENGTH_SHORT).show();
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
