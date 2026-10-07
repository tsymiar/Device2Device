package com.tsymiar.device2device.dialog;
// Android 基础组件

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.RotateAnimation;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.text.TextUtils;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.tsymiar.device2device.R;
import com.tsymiar.device2device.utils.HttpsRequest;
import com.tsymiar.device2device.utils.TimeUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 聊天框：下拉选服务端 → 填 API Key（留空用内置）→ 输入内容发送。
 *
 * 选到 Ollama 时多出一行接入方式：本地（自建服务，地址可填）或在线（官方云）。
 */
public class ChatBoxDialog extends Dialog {
    public static final int CHAT_FILE_REQUEST = 1001;
    /** API Key 与 Ollama 地址的本地存放位置：填过一次之后下次打开自动带出 */
    private static final String CHAT_PREFS = "chat_prefs";
    private static final String KEY_API = "api_key";
    private static final String KEY_OLLAMA_HOST = "ollama_host";
    private static final String KEY_OLLAMA_CLOUD = "ollama_cloud";
    /** 手选的模型按档位分开存：档位名见 {@link #modelKey()} */
    private static final String KEY_MODEL_PREFIX = "model_";
    /** Handler 消息类型：0=收到回复，-1=请求失败 */
    private static final int MSG_RECEIVED = 0;
    private static final int MSG_FAILED = -1;
    /** 附件读进来的最大字符数：一次请求塞几十 MB 进去没有意义 */
    private static final int MAX_ATTACH_CHARS = 32 * 1024;
    /** 自建 Ollama 的服务端口，地址里没写端口时补这个 */
    private static final int OLLAMA_PORT = 11434;
    private static final String DEFAULT_OLLAMA_HOST = "127.0.0.1";

    /**
     * 下拉里每一项对应的服务端。
     *
     * url 与 model 必须成对：只换 model 不改 url 的话请求还是打到另一个服务上，
     * 那边不认这个 model，返回的就是一串看不懂的错误。
     *
     * cloudUrl / cloudModel 为空表示该服务只有一种接入方式；
     * Ollama 两套都给了，可以在本地自建与官方云之间切。
     */
    private enum ChatTarget {
        DEEPSEEK("https://api.deepseek.com/chat/completions",
                "deepseek-chat", "deepseek-reasoner"),
        // 自建 Ollama 的 OpenAI 兼容接口走 http，HttpsRequest 用基类所以支持
        OLLAMA("http://" + DEFAULT_OLLAMA_HOST + ":" + OLLAMA_PORT + "/v1/chat/completions",
                "qwen2.5:latest", "deepseek-r1:latest",
                "https://ollama.com/v1/chat/completions",
                "gpt-oss:20b", "deepseek-r1:671b"),
        OPENROUTER("https://openrouter.ai/api/v1/chat/completions",
                "deepseek/deepseek-chat", "deepseek/deepseek-r1");

        final String url;
        final String model;
        final String reasonModel;
        /** 官方云地址；null 表示该服务没有云端这一档 */
        final String cloudUrl;
        final String cloudModel;
        final String cloudReasonModel;

        ChatTarget(String url, String model, String reasonModel) {
            this(url, model, reasonModel, null, model, reasonModel);
        }

        ChatTarget(String url, String model, String reasonModel,
                   String cloudUrl, String cloudModel, String cloudReasonModel) {
            this.url = url;
            this.model = model;
            this.reasonModel = reasonModel;
            this.cloudUrl = cloudUrl;
            this.cloudModel = cloudModel;
            this.cloudReasonModel = cloudReasonModel;
        }

        boolean hasCloud() {
            return cloudUrl != null;
        }

        String modelOf(boolean think, boolean cloud) {
            if (cloud && hasCloud()) {
                return think ? cloudReasonModel : cloudModel;
            }
            return think ? reasonModel : model;
        }
    }

    /** spinner 位置 → 服务端；chat_options 最后一项「…」还没接，用 null 占位 */
    private static final ChatTarget[] TARGETS = {
            ChatTarget.DEEPSEEK, ChatTarget.OLLAMA, ChatTarget.OPENROUTER, null
    };

    private final Activity mActivity;
    private EditText etMessage;
    private EditText etApiKey;
    private MaterialButton btnKeyMask;
    /** Key 默认打码显示：聊天框常驻在屏幕上，明文挂着不合适 */
    private boolean mKeyMasked = true;
    private RecyclerView rvMessages;
    private MessageAdapter adapter;
    private Spinner spinnerOptions;
    private CheckBox checkBox;
    private TextView chatHint;
    private LinearLayout filePreviewBar;
    private TextView tvFileName;
    private Uri pendingFileUri;
    private final List<Message> messageList = new ArrayList<>();
    Request.Header header = new Request.Header();
    Request request = new Request();
    /** 当前下拉选项下标 */
    private int mModeIndex = 0;
    /** 当前生效的服务端；null 表示选到了还没接的那一项 */
    private ChatTarget mTarget = ChatTarget.DEEPSEEK;
    /** Ollama 的接入方式：true=官方云，false=自建服务（地址由 etOllamaHost 给） */
    private boolean mOllamaCloud = false;
    private LinearLayout ollamaPanel;
    private RadioGroup rgOllamaSource;
    private EditText etOllamaHost;
    private LinearLayout modelRow;
    private Button btnModel;
    private Button btnModelRefresh;
    /** 拉到的模型按服务端分档存：切回来不用再拉一次 */
    private final Map<String, List<String>> mModelCache = new HashMap<>();
    /** 正在拉哪一档；同档连点只提示一次，不刷出一串并发请求 */
    private String mFetchingKey = null;
    /** 已经在等待回复的那条占位气泡，收到回复后原地替换 */
    private Message mPending = null;
    /** 有请求在途：连点发送不该发出去一串并发请求 */
    private boolean mThinking = false;

    public ChatBoxDialog(@NonNull Context context) {
        super(context);
        // 后续要用 startActivityForResult，这里必须是 Activity；不是的话不强转，避免 ClassCastException
        mActivity = context instanceof Activity ? (Activity) context : null;
        setContentView(R.layout.dialog_chat_box);
        setupViews();
        Window window = getWindow();
        if (window != null) {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (int)(metrics.heightPixels * 0.8) // 占据屏幕80%高度
            );
            if (metrics.heightPixels < metrics.widthPixels) {
                // 横屏模式
                window.setLayout(
                        (int)(metrics.widthPixels * 0.6),
                        ViewGroup.LayoutParams.MATCH_PARENT
                );
            }
            window.setBackgroundDrawableResource(android.R.color.darker_gray);
        }
        if (handler != null) {
            header.handler = handler;
        }
        request.setHeader(header);
        applyMode();
    }
    // Looper.getMainLooper()：无论从哪个线程创建都不会因为没有 Looper 直接抛异常
    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @SuppressLint("SetTextI18n")
        @Override
        public void handleMessage(@NonNull android.os.Message msg) {
            super.handleMessage(msg);
            // 对话框已经关掉了（发完就退出再进来时是另一个实例），别再去动已经销毁的 view
            if (!isShowing()) {
                mPending = null;
                mThinking = false;
                return;
            }
            // msg.obj 允许为 null，直接 toString() 会空指针闪退，这里退化成空串
            String text = msg.obj == null ? "" : String.valueOf(msg.obj);
            switch (msg.what) {
                case MSG_RECEIVED:
                    finishPending(text, false);
                    break;
                case MSG_FAILED:
                    finishPending(text, true);
                    break;
                default:
                    break;
            }
        }
    };
    private void setupViews() {
        // 初始化视图
        spinnerOptions = findViewById(R.id.spinner_options);
        etMessage = findViewById(R.id.et_message);
        etApiKey = findViewById(R.id.et_api_key);
        btnKeyMask = findViewById(R.id.btn_key_mask);
        rvMessages = findViewById(R.id.rv_messages);
        checkBox = findViewById(R.id.check_dpsk);
        chatHint = findViewById(R.id.check_hint);
        filePreviewBar = findViewById(R.id.file_preview_bar);
        tvFileName = findViewById(R.id.tv_file_name);
        ImageView ivClearFile = findViewById(R.id.iv_clear_file);

        // 设置下拉选项
        ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(
                getContext(),
                R.array.chat_options,
                R.layout.spinner_selected_item
        );
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        if (spinnerOptions != null) {
            spinnerOptions.setAdapter(spinnerAdapter);
            spinnerOptions.setSelection(mModeIndex);
            spinnerOptions.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    handleSpinnerSelection(position);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    // 无选择时保持当前模式
                }
            });
        }
        setupOllamaPanel();
        setupModelRow();
        autoFetchModels();

        // 设置消息列表
        if (rvMessages != null) {
            rvMessages.setLayoutManager(new LinearLayoutManager(getContext()));
            adapter = new MessageAdapter(messageList);
            rvMessages.setAdapter(adapter);
        }

        // 深度思考 = 换成推理模型，三个服务端都支持
        if (checkBox != null) {
            checkBox.setChecked(false);
            checkBox.setVisibility(View.VISIBLE);
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> applyMode());
        }
        if (chatHint != null) {
            chatHint.setVisibility(View.VISIBLE);
        }

        @SuppressLint("CutPasteId") EditText et_message = findViewById(R.id.et_message);
        if (et_message != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                et_message.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM);
            } else {
                TextViewCompat.setAutoSizeTextTypeWithDefaults(
                        et_message,
                        TextViewCompat.AUTO_SIZE_TEXT_TYPE_UNIFORM
                );
            }
        }

        setupApiKey();

        // 附件按钮点击
        Button btnAttach = findViewById(R.id.btn_attach);
        Button btnSend = findViewById(R.id.btn_send);
        if (btnAttach != null) {
            btnAttach.setOnClickListener(v -> openFileChooser());
        }
        if (etMessage != null) {
            etMessage.setHint(R.string.msg);
        }

        // 清除附件预览
        if (ivClearFile != null) {
            ivClearFile.setOnClickListener(v -> clearFilePreview());
        }

        // 发送按钮点击
        if (btnSend != null) {
            btnSend.setOnClickListener(v -> onSendClicked());
        }

        if (messageList.isEmpty()) {
            appendMessage(new Message(
                    "选一个服务 → 填 API Key（留空沿用内置的）→ 输入内容发送。\n"
                            + "文本文件附件会读进来一起发，其它文件只带文件名。",
                    Message.MessageType.RECEIVED_TEXT, Message.MessageStatus.RECEIVED));
        }
    }

    // ------------------------------------------------------------------
    // 服务端 / 模型
    // ------------------------------------------------------------------

    private void handleSpinnerSelection(int position) {
        String[] items = getContext().getResources().getStringArray(R.array.chat_options);
        // 下标来自外部恢复流程时可能越界，直接 items[position] 会数组越界闪退
        if (position < 0 || position >= items.length) return;
        mModeIndex = position;
        ChatTarget target = position < TARGETS.length ? TARGETS[position] : null;
        if (target == null) {
            // 最后一项「…」还没接：沿用上一个服务端，别让界面变成一个发不出请求的空壳
            Toast.makeText(getContext(),
                    "「" + items[position] + "」暂未接入，沿用 " + mTarget.name(),
                    Toast.LENGTH_SHORT).show();
        } else {
            mTarget = target;
        }
        applyOllamaVisibility();
        applyMode();
        autoFetchModels();
    }

    /** 把当前选项写进请求头（URL / model / token），每次整组重灌 */
    private void applyMode() {
        if (mTarget == null) return;
        saveApiKey();
        saveOllamaPrefs();
        boolean think = checkBox != null && checkBox.isChecked();
        header.reqUrl = resolveUrl();
        // 手选过的模型优先：勾深度思考不再把它换回推理模型
        String chosen = loadModel();
        header.model = TextUtils.isEmpty(chosen) ? mTarget.modelOf(think, mOllamaCloud) : chosen;
        header.think = think;
        header.token = currentApiKey();
        request.setHeader(header);
        applyModelUi();
    }

    // ------------------------------------------------------------------
    // Ollama 接入方式（本地自建 / 官方云）
    // ------------------------------------------------------------------

    private void setupOllamaPanel() {
        ollamaPanel = findViewById(R.id.ollama_panel);
        rgOllamaSource = findViewById(R.id.rg_ollama_source);
        etOllamaHost = findViewById(R.id.et_ollama_host);

        mOllamaCloud = loadOllamaCloud();
        if (etOllamaHost != null) {
            etOllamaHost.setText(loadOllamaHost());
            etOllamaHost.setSelection(etOllamaHost.getText().length());
            // 光标移开就落盘，不用专门点"保存"
            etOllamaHost.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    saveOllamaPrefs();
                    applyMode();
                    // 换了服务地址，之前那份模型列表已经不是这一台上的了
                    mModelCache.remove("ollama_local");
                    autoFetchModels();
                }
            });
        }
        if (rgOllamaSource != null) {
            rgOllamaSource.check(mOllamaCloud ? R.id.rb_ollama_cloud : R.id.rb_ollama_local);
            rgOllamaSource.setOnCheckedChangeListener((group, checkedId) -> {
                mOllamaCloud = (checkedId == R.id.rb_ollama_cloud);
                applyOllamaVisibility();
                applyMode();
                autoFetchModels();
            });
        }
        applyOllamaVisibility();
    }

    /** 面板只在选到 Ollama 时出现；地址框只在自建那一档出现 */
    private void applyOllamaVisibility() {
        boolean isOllama = mTarget == ChatTarget.OLLAMA;
        if (ollamaPanel != null) {
            ollamaPanel.setVisibility(isOllama ? View.VISIBLE : View.GONE);
        }
        if (etOllamaHost != null) {
            etOllamaHost.setVisibility(isOllama && !mOllamaCloud ? View.VISIBLE : View.GONE);
        }
    }

    /** Ollama 分自建与官方云两档，其它服务只有一种接入方式 */
    private String resolveUrl() {
        if (mTarget == ChatTarget.OLLAMA) {
            if (mOllamaCloud) {
                return ChatTarget.OLLAMA.cloudUrl;
            }
            return "http://" + normalizeHost(currentOllamaHost()) + "/v1/chat/completions";
        }
        return mTarget.url;
    }

    /**
     * 把填进来的地址补成 host:port。
     *
     * 只填 IP 时补默认端口 11434；写成 ip:port 就按填的走（换端口时用这个）。
     * 顺手容忍误填进来的 http:// 前缀和末尾斜杠。
     */
    private static String normalizeHost(String raw) {
        String host = raw == null ? "" : raw.trim();
        if (host.startsWith("http://")) {
            host = host.substring("http://".length());
        } else if (host.startsWith("https://")) {
            host = host.substring("https://".length());
        }
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()) {
            host = DEFAULT_OLLAMA_HOST;
        }
        // 末尾一段是数字才算带了端口：IPv6 地址里本身就有冒号
        int cut = host.lastIndexOf(':');
        boolean hasPort = cut > 0 && TextUtils.isDigitsOnly(host.substring(cut + 1));
        if (!hasPort) {
            host = host + ":" + OLLAMA_PORT;
        }
        return host;
    }

    private String currentOllamaHost() {
        if (etOllamaHost == null || etOllamaHost.getText() == null) return DEFAULT_OLLAMA_HOST;
        String raw = etOllamaHost.getText().toString().trim();
        return raw.isEmpty() ? DEFAULT_OLLAMA_HOST : raw;
    }

    private String loadOllamaHost() {
        return getContext().getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_OLLAMA_HOST, DEFAULT_OLLAMA_HOST);
    }

    private boolean loadOllamaCloud() {
        return getContext().getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_OLLAMA_CLOUD, false);
    }

    private void saveOllamaPrefs() {
        getContext().getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_OLLAMA_HOST, currentOllamaHost())
                .putBoolean(KEY_OLLAMA_CLOUD, mOllamaCloud)
                .apply();
    }

    // ------------------------------------------------------------------
    // 模型：Ollama / OpenRouter 从服务端拉列表，选中哪个用哪个
    // ------------------------------------------------------------------

    /** 当前服务端在模型缓存里的档位；null 表示模型固定，没有可拉的列表 */
    private String modelKey() {
        if (mTarget == ChatTarget.OLLAMA) {
            return mOllamaCloud ? "ollama_cloud" : "ollama_local";
        }
        if (mTarget == ChatTarget.OPENROUTER) return "openrouter";
        return null;
    }

    /**
     * 模型列表接口。
     *
     * 自建 Ollama 走原生的 /api/tags：各版本都有，OpenAI 兼容的 /v1/models 未必开着。
     * 官方云与 OpenRouter 都是 OpenAI 兼容的 /v1/models。
     */
    private String modelsUrl() {
        if (mTarget == ChatTarget.OLLAMA) {
            if (mOllamaCloud) return "https://ollama.com/v1/models";
            return "http://" + normalizeHost(currentOllamaHost()) + "/api/tags";
        }
        if (mTarget == ChatTarget.OPENROUTER) return "https://openrouter.ai/api/v1/models";
        return null;
    }

    private void setupModelRow() {
        modelRow = findViewById(R.id.model_row);
        btnModel = findViewById(R.id.btn_model);
        btnModelRefresh = findViewById(R.id.btn_model_refresh);
        if (btnModel != null) {
            btnModel.setOnClickListener(v -> {
                String key = modelKey();
                if (key == null) return;
                List<String> cached = mModelCache.get(key);
                if (cached != null && !cached.isEmpty()) {
                    showModelPicker(cached);
                } else {
                    fetchModels(true);
                }
            });
        }
        if (btnModelRefresh != null) {
            // 刷新也是为了选：拉完直接弹列表，省一次点击
            btnModelRefresh.setOnClickListener(v -> fetchModels(true));
        }
    }

    /** 切到支持的服务端就后台拉一份，点开模型按钮时直接有列表可选 */
    private void autoFetchModels() {
        String key = modelKey();
        if (key == null || mModelCache.containsKey(key)) return;
        fetchModels(false);
    }

    /** showPicker=true 时拉完直接弹选择框；false 只把列表缓存下来 */
    private void fetchModels(boolean showPicker) {
        final String key = modelKey();
        final String url = modelsUrl();
        if (key == null || url == null) return;
        if (key.equals(mFetchingKey)) {
            Toast.makeText(getContext(), R.string.chat_model_fetching, Toast.LENGTH_SHORT).show();
            return;
        }
        mFetchingKey = key;
        if (showPicker) {
            Toast.makeText(getContext(), R.string.chat_model_fetching, Toast.LENGTH_SHORT).show();
        }
        HashMap<String, String> heads = new HashMap<>();
        String token = currentApiKey();
        // 自建 Ollama 与 OpenRouter 的列表接口不验签，官方云要带 Key
        if (!TextUtils.isEmpty(token)) heads.put("Authorization", "Bearer " + token);
        HttpsRequest.executeRequest(url, "GET", heads, null,
                new HttpsRequest.HttpsRequestCallback() {
                    @Override
                    public void onSuccess(int responseCode, String response,
                                          Map<String, String> respHeaders) {
                        if (key.equals(mFetchingKey)) mFetchingKey = null;
                        // 拉的过程中可能已经切走服务端，结果只对当时那一档有效
                        if (!key.equals(modelKey())) return;
                        List<String> models = parseModels(response);
                        mModelCache.put(key, models);
                        if (!isShowing()) return;
                        if (models.isEmpty()) {
                            Toast.makeText(getContext(), R.string.chat_model_none,
                                    Toast.LENGTH_SHORT).show();
                        } else if (showPicker) {
                            showModelPicker(models);
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        if (key.equals(mFetchingKey)) mFetchingKey = null;
                        if (!isShowing()) return;
                        String reason = e == null ? "" : String.valueOf(e.getMessage());
                        Toast.makeText(getContext(), getContext().getString(
                                R.string.chat_model_fetch_failed, reason),
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /** 模型列表只有两种排布：OpenAI 的 data[].id 与 Ollama 原生的 models[].name */
    private List<String> parseModels(String body) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isEmpty()) return out;
        try {
            JSONObject root = new JSONObject(body);
            JSONArray data = root.optJSONArray("data");
            if (data == null) data = root.optJSONArray("models");
            if (data == null) return out;
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id");
                if (TextUtils.isEmpty(id)) id = item.optString("name");
                if (TextUtils.isEmpty(id)) continue;
                // OpenRouter 上绝大多数模型是付费的，只留免费的那一批
                if (mTarget == ChatTarget.OPENROUTER && !isFreeModel(item, id)) continue;
                if (!out.contains(id)) out.add(id);
            }
        } catch (JSONException e) {
            Log.w("ChatBox", "parse model list failed", e);
        }
        Collections.sort(out);
        return out;
    }

    /** OpenRouter 的免费判据：id 带 :free，或者 prompt / completion / request 报价全是 0 */
    private static boolean isFreeModel(JSONObject item, String id) {
        if (id.endsWith(":free")) return true;
        JSONObject pricing = item.optJSONObject("pricing");
        if (pricing == null) return false;
        return isZero(pricing.opt("prompt"))
                && isZero(pricing.opt("completion"))
                && isZero(pricing.opt("request"));
    }

    /** 报价是字符串形式的数字；字段缺失按 0 算 */
    private static boolean isZero(Object value) {
        if (value == null || value == JSONObject.NULL) return true;
        try {
            return Double.parseDouble(String.valueOf(value)) == 0.0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void showModelPicker(List<String> models) {
        final String[] items = models.toArray(new String[0]);
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                .setTitle(R.string.chat_model_pick_title)
                .setItems(items, (dialog, which) -> {
                    saveModel(items[which]);
                    applyMode();
                });
        // 手选过就给一条退回默认的路：深度思考换推理模型也是从这条回来
        if (!TextUtils.isEmpty(loadModel())) {
            builder.setNeutralButton(R.string.chat_model_reset, (dialog, which) -> {
                saveModel("");
                applyMode();
            });
        }
        builder.show();
    }

    /** 模型行只在能拉列表的服务端出现；按钮上显示当前实际会用的那个 */
    private void applyModelUi() {
        if (modelRow != null) {
            modelRow.setVisibility(modelKey() == null ? View.GONE : View.VISIBLE);
        }
        if (btnModel != null) {
            String model = header.model;
            btnModel.setText(TextUtils.isEmpty(model)
                    ? getContext().getString(R.string.chat_model_pick) : model);
        }
    }

    private String loadModel() {
        String key = modelKey();
        if (key == null) return "";
        return getContext().getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODEL_PREFIX + key, "");
    }

    private void saveModel(String model) {
        String key = modelKey();
        if (key == null) return;
        getContext().getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODEL_PREFIX + key, model == null ? "" : model).apply();
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    private void onSendClicked() {
        if (mTarget == null) {
            Toast.makeText(getContext(), "请先选一个可用的服务", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mThinking) {
            Toast.makeText(getContext(), "上一条还在等回复", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = etMessage != null && etMessage.getText() != null
                ? etMessage.getText().toString().trim() : "";
        String fileName = tvFileName == null ? null : String.valueOf(tvFileName.getText());
        // 附件：文本类读进来一起发，非文本只带文件名
        String attachment = pendingFileUri == null ? null : readAttachmentText(pendingFileUri);

        if (text.isEmpty() && pendingFileUri == null) {
            Toast.makeText(getContext(), R.string.msg, Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder payload = new StringBuilder();
        if (!text.isEmpty()) payload.append(text);
        if (attachment != null) {
            if (payload.length() > 0) payload.append("\n\n");
            payload.append("文件 ").append(fileName == null ? "附件" : fileName)
                    .append(" 的内容：\n").append(attachment);
        } else if (pendingFileUri != null) {
            if (payload.length() > 0) payload.append("\n\n");
            payload.append("[附件] ").append(fileName == null ? "未命名文件" : fileName)
                    .append("（非文本，未读取内容）");
        }

        // 气泡上只显示用户自己敲的那段：附件正文可能几万字，铺满整屏就没法聊了
        String bubble = text.isEmpty()
                ? ("[附件] " + (fileName == null ? "文件" : fileName)) : text;
        sendChat(bubble, payload.toString());
        if (etMessage != null) etMessage.setText("");
        clearFilePreview();
    }

    private void sendChat(String bubble, String payload) {
        appendMessage(new Message(bubble, Message.MessageType.SENT_TEXT,
                Message.MessageStatus.SENT));
        // 占位气泡：发出去之后界面上得有个"在等"的反馈，回复回来原地替换
        mPending = new Message("正在思考…", Message.MessageType.RECEIVED_TEXT,
                Message.MessageStatus.RECEIVING);
        appendMessage(mPending);
        mThinking = true;
        applyMode();
        if (request.start(payload) < 0) {
            finishPending("请求构造失败", true);
        }
    }

    /** 回复 / 失败落到占位气泡上；没有占位（例如对话框被关过）就新起一条 */
    private void finishPending(String text, boolean failed) {
        mThinking = false;
        Message target = mPending;
        mPending = null;
        if (failed && isShowing()) {
            Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
        }
        if (target == null) {
            appendMessage(new Message(failed ? ("⚠ " + text) : text,
                    Message.MessageType.RECEIVED_TEXT, Message.MessageStatus.RECEIVED));
            return;
        }
        target.content = failed ? ("⚠ " + text) : text;
        target.setStatus(Message.MessageStatus.RECEIVED);
        int index = messageList.indexOf(target);
        if (index >= 0 && adapter != null) {
            adapter.notifyItemChanged(index);
        }
        scrollToBottom();
    }

    private void appendMessage(Message message) {
        messageList.add(message);
        if (adapter != null) {
            adapter.notifyItemInserted(messageList.size() - 1);
        }
        scrollToBottom();
    }

    // ------------------------------------------------------------------
    // API Key
    // ------------------------------------------------------------------

    /** 输入框 + 打码开关：填过一次就存在本地，下次打开自动带出 */
    private void setupApiKey() {
        if (etApiKey == null) return;
        etApiKey.setText(loadApiKey());
        etApiKey.setSelection(etApiKey.getText().length());
        // 光标移开就落盘，不用专门点"保存"
        etApiKey.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                saveApiKey();
                applyMode();
            }
        });
        if (btnKeyMask != null) {
            btnKeyMask.setOnClickListener(v -> {
                mKeyMasked = !mKeyMasked;
                applyKeyMask();
            });
        }
        applyKeyMask();
    }

    private String loadApiKey() {
        return getContext().getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_API, "");
    }

    private void saveApiKey() {
        if (etApiKey == null) return;
        String key = etApiKey.getText() == null ? "" : etApiKey.getText().toString().trim();
        getContext().getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_API, key).apply();
    }

    /** 切换明文 / 打码；改 inputType 会丢光标位置，这里手动放回去 */
    private void applyKeyMask() {
        if (etApiKey == null || etApiKey.getText() == null) return;
        // 没聚焦过的 EditText，getSelectionStart() 会返回 -1；
        // API 28+ 的 Selection.setSelection 不再自动裁剪，越界直接抛 IndexOutOfBoundsException。
        int len = etApiKey.getText().length();
        int start = clampTo(etApiKey.getSelectionStart(), len);
        int end = clampTo(etApiKey.getSelectionEnd(), len);
        etApiKey.setInputType(mKeyMasked
                ? (android.text.InputType.TYPE_CLASS_TEXT
                   | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
                : (android.text.InputType.TYPE_CLASS_TEXT
                   | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        etApiKey.setSelection(start, end);
        if (btnKeyMask != null) {
            btnKeyMask.setText(mKeyMasked ? R.string.chat_api_key_mask : R.string.chat_api_key_show);
        }
    }

    /** 把可能为负的光标下标夹到 [0, len] */
    private static int clampTo(int value, int len) {
        return Math.max(0, Math.min(value, len));
    }

    /** 当前生效的 Key：空串表示沿用内置的那一个 */
    private String currentApiKey() {
        if (etApiKey == null || etApiKey.getText() == null) return "";
        return etApiKey.getText().toString().trim();
    }

    @Override
    public void dismiss() {
        saveApiKey();
        saveOllamaPrefs();
        super.dismiss();
    }

    public void saveState(Bundle outState) {
        outState.putInt("chat_mode", mModeIndex);
    }

    public void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState == null || spinnerOptions == null) return;
        String[] items = getContext().getResources().getStringArray(R.array.chat_options);
        int modeIndex = savedInstanceState.getInt("chat_mode", 0);
        // 下标越界会让下拉选到一个不存在的位置，兜回第 0 项
        if (modeIndex < 0 || modeIndex >= items.length) modeIndex = 0;
        mModeIndex = modeIndex;
        spinnerOptions.setSelection(modeIndex);
        handleSpinnerSelection(modeIndex);
    }
    private void openFileChooser() {
        // 拿不到 Activity 或已经收摊了就别发起跳转，回来也没人接
        if (mActivity == null || mActivity.isFinishing() || mActivity.isDestroyed()) return;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        Intent choose = Intent.createChooser(intent, "选择文件");
        mActivity.startActivityForResult(choose, CHAT_FILE_REQUEST);
    }

    // 处理文件选择结果
    public void handleFileResult(Uri fileUri) {
        if (fileUri == null) return;
        String fileName = getFileNameFromUri(fileUri);
        pendingFileUri = fileUri;
        showFilePreview(fileName == null || fileName.isEmpty() ? "未命名文件" : fileName);
    }

    /**
     * 读文本文件的内容，准备随消息一起发出去。
     *
     * 读不了（二进制 / 权限已失效 / MIME 与扩展名都不像文本）就返回 null，
     * 调用方退化成只带文件名。
     */
    private String readAttachmentText(Uri uri) {
        String name = getFileNameFromUri(uri);
        String mime = getContext().getContentResolver().getType(uri);
        if (!looksLikeText(mime, name)) return null;
        try {
            InputStream is = getContext().getContentResolver().openInputStream(uri);
            if (is == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                char[] buf = new char[4096];
                int n;
                int total = 0;
                while ((n = reader.read(buf)) > 0) {
                    if (total + n > MAX_ATTACH_CHARS) {
                        sb.append(buf, 0, Math.max(0, MAX_ATTACH_CHARS - total));
                        sb.append("\n…(已截断)");
                        break;
                    }
                    sb.append(buf, 0, n);
                    total += n;
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            Log.w("ChatBox", "read attachment failed", e);
            return null;
        }
    }

    /** MIME 与扩展名只要有一个像文本就试着读：不少 ROM 给不出 MIME */
    private static boolean looksLikeText(String mime, String name) {
        if (mime != null && (mime.startsWith("text/")
                || "application/json".equals(mime) || "application/xml".equals(mime))) {
            return true;
        }
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv")
                || lower.endsWith(".json") || lower.endsWith(".log") || lower.endsWith(".java")
                || lower.endsWith(".kt") || lower.endsWith(".py") || lower.endsWith(".xml")
                || lower.endsWith(".h") || lower.endsWith(".cpp") || lower.endsWith(".gradle");
    }

    private void showFilePreview(String fileName) {
        if (filePreviewBar != null && tvFileName != null) {
            filePreviewBar.setVisibility(View.VISIBLE);
            tvFileName.setText(fileName);
        }
    }

    private void clearFilePreview() {
        pendingFileUri = null;
        if (filePreviewBar != null) {
            filePreviewBar.setVisibility(View.GONE);
        }
    }

    @SuppressLint("Range")
    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if (Objects.equals(uri.getScheme(), "content")) {
            try (Cursor cursor = getContext().getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result != null ? result.lastIndexOf('/') : 0;
            if (cut > 0) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    // 自动滚动到底部
    private void scrollToBottom() {
        if (rvMessages == null || adapter == null || messageList.isEmpty()) return;
        final int last = messageList.size() - 1;
        rvMessages.post(() -> {
            rvMessages.smoothScrollToPosition(last);
            // 或者使用以下代码确保完全滚动到底部
            RecyclerView.LayoutManager lm = rvMessages.getLayoutManager();
            if (lm instanceof LinearLayoutManager) {
                ((LinearLayoutManager) lm).scrollToPositionWithOffset(last, 0);
            }
        });
    }

    // 消息适配器
    public static class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private int lastPosition = -1;
        private static final int TYPE_SENT = 1;
        private static final int TYPE_RECEIVED = 2;

        private final List<Message> messages;

        public MessageAdapter(List<Message> messages) {
            this.messages = messages;
        }

        @Override
        public int getItemViewType(int position) {
            Message message = messages.get(position);
            return message.getType().name().startsWith("SENT") ? TYPE_SENT : TYPE_RECEIVED;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_SENT) {
                View view = inflater.inflate(R.layout.item_msg_sent, parent, false);
                return new SentMessageHolder(view);
            } else {
                View view = inflater.inflate(R.layout.item_msg_received, parent, false);
                return new ReceivedMessageHolder(view);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, @SuppressLint("RecyclerView") int position) {
            Message message = messages.get(position);

            if (holder instanceof SentMessageHolder) {
                SentMessageHolder sentHolder = (SentMessageHolder) holder;
                sentHolder.tvContent.setText(message.getContent());
                sentHolder.tvTime.setText(TimeUtils.smartTimeFormat(message.getTimestamp()));
            } else if (holder instanceof ReceivedMessageHolder) {
                ReceivedMessageHolder receivedHolder = (ReceivedMessageHolder) holder;
                receivedHolder.tvContent.setText(message.getContent());
                receivedHolder.tvTime.setText(TimeUtils.smartTimeFormat(message.getTimestamp()));
                // 进度条：只有"正在思考…"那条占位气泡转，收到回复后收起来
                if (message.getStatus() == Message.MessageStatus.RECEIVING) {
                    receivedHolder.progressBar.setVisibility(View.VISIBLE);
                    receivedHolder.maskView.setVisibility(View.VISIBLE);
                    startProgressAnimation(receivedHolder.progressBar);
                } else {
                    receivedHolder.progressBar.clearAnimation();
                    receivedHolder.progressBar.setVisibility(View.GONE);
                    receivedHolder.maskView.setVisibility(View.GONE);
                }
            }
            if (position > lastPosition) {
                setAnimation(holder.itemView, position);
                lastPosition = position;
            }
        }
        private void startProgressAnimation(ProgressBar progressBar) {
            RotateAnimation rotate = new RotateAnimation(
                    0, 360,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
            );
            rotate.setDuration(800);
            rotate.setRepeatCount(Animation.INFINITE);
            progressBar.startAnimation(rotate);
        }

        private void setAnimation(View view, int position) {
            Animation animation = AnimationUtils.loadAnimation(view.getContext(),
                    shouldAnimateFromLeft(position) ?
                            R.anim.slide_in_left :
                            R.anim.slide_in_right);

            view.startAnimation(animation);
        }

        /**
         * 判断动画方向（收到的消息左进，发送的消息右进）
         */
        private boolean shouldAnimateFromLeft(int position) {
            return messages.get(position).getType().name().startsWith("RECEIVED");
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        static class SentMessageHolder extends RecyclerView.ViewHolder {
            TextView tvContent, tvTime;

            SentMessageHolder(View itemView) {
                super(itemView);
                tvContent = itemView.findViewById(R.id.tv_content);
                tvTime = itemView.findViewById(R.id.tv_time);
            }
        }

        static class ReceivedMessageHolder extends RecyclerView.ViewHolder {
            TextView tvContent, tvTime;
            ProgressBar progressBar;
            View maskView;
            ReceivedMessageHolder(View itemView) {
                super(itemView);
                tvContent = itemView.findViewById(R.id.tv_content);
                tvTime = itemView.findViewById(R.id.tv_time);
                progressBar = itemView.findViewById(R.id.progress_bar);
                maskView = itemView.findViewById(R.id.mask_view);
            }
        }
    }

    // 消息数据类
    public static class Message {
        public enum MessageType {
            SENT_TEXT,   // 发送的文本
            RECEIVED_TEXT, // 接收的文本
            SENT_FILE,    // 发送的文件
            RECEIVED_FILE  // 接收的文件
        }
        public enum MessageStatus {
            RECEIVING,  // 接收中
            RECEIVED,     // 已接收
            SENT  // 已送达
        }
        private MessageStatus status = MessageStatus.RECEIVING;

        public MessageStatus getStatus() { return status; }
        public void setStatus(MessageStatus status) { this.status = status; }
        /** 非 final：占位气泡要在收到回复时原地改内容 */
        String content;
        private final MessageType type;
        private final long timestamp;

        public Message(String content, MessageType type) {
            this(content, type, MessageStatus.RECEIVING);
        }

        public Message(String content, MessageType type, MessageStatus status) {
            this.content = content;
            this.type = type;
            this.status = status;
            this.timestamp = System.currentTimeMillis();
        }

        // Getters
        public String getContent() { return content; }
        public MessageType getType() { return type; }
        public long getTimestamp() { return timestamp; }
    }


    public static class Request {
        private final String TOKEN = "sk-66xxxx";
        private final String REQ_URL = "https://api.deepseek.com/chat/completions";
        private final HashMap<String, String> headers = new HashMap<>();
        public static class Header {
            String token = null;
            String reqUrl = null;
            String model = null;
            Handler handler = null;
            boolean isPost = true;
            boolean think = false;
        }
        Header mHeader = new Header();
        public void setHeader(Header header) {
            mHeader.handler = header.handler;
            mHeader.isPost = header.isPost;
            mHeader.think = header.think;
            // URL / model 每次都整组重灌，避免切了下拉还打到旧地址
            mHeader.reqUrl = (header.reqUrl == null || header.reqUrl.isEmpty())
                    ? REQ_URL : header.reqUrl;
            mHeader.model = (header.model == null || header.model.isEmpty())
                    ? "deepseek-chat" : header.model;
            // 空 Key 回落内置的那一个；不回写 header.token，否则输入框里会凭空多出一串字符
            String token = (header.token == null || header.token.isEmpty())
                    ? TOKEN : header.token;
            headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Safari/605.1.15");
            headers.put("Authorization", "Bearer " + token);
        }

        private String buildChatBody(String userText) throws JSONException {
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", "You are a helpful assistant.");
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", userText == null ? "" : userText);
            JSONArray messages = new JSONArray();
            messages.put(system);
            messages.put(user);
            JSONObject payload = new JSONObject();
            payload.put("model", mHeader.model);
            payload.put("messages", messages);
            payload.put("stream", false);
            return payload.toString();
        }

        /**
         * 把结果丢回主线程。
         *
         * 每次都 obtain 一个新实例：Looper 派发完会把消息回收进全局对象池，
         * 复用同一个对象会命中 MessageQueue 的 "This message is already in use."。
         */
        private void deliver(int what, String text) {
            if (mHeader.handler == null) {
                Log.w("HTTPS", "no handler to deliver result");
                return;
            }
            mHeader.handler.sendMessage(
                    android.os.Message.obtain(mHeader.handler, what, text));
        }

        public int start(String text) {
            String method = "GET";
            String body = null;
            if (mHeader.reqUrl == null || mHeader.reqUrl.isEmpty()) {
                mHeader.reqUrl = REQ_URL;
            }
            if (mHeader.isPost) {
                headers.put("Content-Type", "application/json");
                method = "POST";
                // 用 JSONObject 拼而不是手写字符串：消息里的引号/换行/emoji 会把手拼的 JSON 弄坏
                try {
                    body = buildChatBody(text);
                } catch (JSONException e) {
                    Log.e("HTTPS", "build request body failed", e);
                    return -1;
                }
            }
            HttpsRequest.executeRequest(
                    mHeader.reqUrl,
                    method,
                    headers,
                    body,
                    new HttpsRequest.HttpsRequestCallback() {
                        @Override
                        public void onSuccess(int responseCode, String response, Map<String, String> headers) {
                            String content = response == null ? "" : response;
                            try {
                                JSONObject jsonObject = new JSONObject(content);
                                JSONArray choices = jsonObject.optJSONArray("choices");
                                if (choices != null && choices.length() > 0) {
                                    JSONObject firstChoice = choices.optJSONObject(0);
                                    JSONObject msgObj = firstChoice == null ? null
                                            : firstChoice.optJSONObject("message");
                                    if (msgObj != null) {
                                        content = String.valueOf(msgObj.optString("content", content));
                                    }
                                } else if (jsonObject.has("error")) {
                                    // 服务端回了结构化的错误（key 无效、model 不存在…），
                                    // 走失败分支，界面上才看得出到底哪里不对
                                    deliver(MSG_FAILED, jsonObject.optJSONObject("error")
                                            .optString("message", content));
                                    return;
                                }
                            } catch (JSONException e) {
                                // 非预期格式（多数是网关错误页）直接整段回显示，方便排查
                                Log.w("HTTPS", "unexpected response format", e);
                            }
                            if ((content == null || content.trim().isEmpty()) && responseCode >= 400) {
                                deliver(MSG_FAILED, "HTTP " + responseCode + "，无返回内容");
                                return;
                            }
                            deliver(MSG_RECEIVED, content);
                            Log.d("HTTPS", "Response: " + response);
                        }
                        @Override
                        public void onFailure(Exception e) {
                            // e.getMessage() 经常是 null，直接塞进 msg.obj 会让 Handler 里 toString() 空指针闪退
                            String reason = e == null ? null : e.getMessage();
                            if (reason == null || reason.isEmpty()) {
                                reason = e == null ? "未知错误" : (e.getClass().getSimpleName());
                            }
                            deliver(MSG_FAILED, reason);
                            Log.e("HTTPS", "Error: " + (e == null ? "" : e.getMessage()));
                        }
                    });
            return 0;
        }
    }
}
