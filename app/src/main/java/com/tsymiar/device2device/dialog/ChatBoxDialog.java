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
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ChatBoxDialog extends Dialog {
    public static final int CHAT_FILE_REQUEST = 1001;
    /** API Key 的本地存放位置：填过一次之后下次打开自动带出 */
    private static final String CHAT_PREFS = "chat_prefs";
    private static final String KEY_API = "api_key";
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
    // 在类头部添加枚举定义
    private enum ChatSpin {
        CHAT_DPSK,
        CHAT_THINK,
        CHAT_GPT,
        MIXED,         // 混合模式
    }
    /** Handler 消息类型：0=收到回复，-1=请求失败 */
    private static final int MSG_RECEIVED = 0;
    private static final int MSG_FAILED = -1;
    private ChatSpin currentSpinner = ChatSpin.MIXED;
    private ChatSpin currentChatter = ChatSpin.CHAT_DPSK;

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
    }
    // Looper.getMainLooper()：无论从哪个线程创建都不会因为没有 Looper 直接抛异常
    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @SuppressLint("SetTextI18n")
        @Override
        public void handleMessage(@NonNull android.os.Message msg) {
            super.handleMessage(msg);
            // 对话框已经关掉了（发完就退出再进来时是另一个实例），别再去动已经销毁的 view
            if (!isShowing()) return;
            // msg.obj 可能是 null：以前 msg.obj.toString() 会直接空指针闪退
            String text = msg.obj == null ? "" : String.valueOf(msg.obj);
            switch (msg.what) {
                case MSG_RECEIVED:
                    receiveTextMessage(text);
                    break;
                case MSG_FAILED:
                    Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
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
        spinnerOptions.setAdapter(spinnerAdapter);
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
        // 设置消息列表
        rvMessages.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new MessageAdapter(messageList);
        rvMessages.setAdapter(adapter);

        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                currentChatter = ChatSpin.CHAT_THINK;
                header.think = true;
            } else {
                currentChatter = ChatSpin.CHAT_DPSK;
                header.think = false;
            }
            request.setHeader(header);
        });

        @SuppressLint("CutPasteId") EditText et_message = findViewById(R.id.et_message);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            et_message.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM);
        } else {
            TextViewCompat.setAutoSizeTextTypeWithDefaults(
                    et_message,
                    TextViewCompat.AUTO_SIZE_TEXT_TYPE_UNIFORM
            );
        }

        setupApiKey();

        // 附件按钮点击
        Button btnAttach = findViewById(R.id.btn_attach);
        Button btnSend = findViewById(R.id.btn_send);
        btnAttach.setOnClickListener(v -> openFileChooser());
        btnAttach.setVisibility(View.VISIBLE);
        etMessage.setVisibility(View.VISIBLE);
        etMessage.setHint(R.string.msg);

        // 清除附件预览
        if (ivClearFile != null) {
            ivClearFile.setOnClickListener(v -> clearFilePreview());
        }

        // 发送按钮点击
        btnSend.setOnClickListener(v -> {
            String message = etMessage.getText() == null ? "" : etMessage.getText().toString();
            if (!message.isEmpty()) {
                sendTextMessage(message);
                etMessage.setText("");
                clearFilePreview();
            } else if (pendingFileUri != null) {
                // 无文本但有附件：发送文件消息
                sendFileMessage(tvFileName.getText().toString());
                clearFilePreview();
            } else {
                Toast.makeText(getContext(), R.string.msg, Toast.LENGTH_SHORT).show();
            }
        });
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
            if (!hasFocus) saveApiKey();
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
        super.dismiss();
    }

    // 处理选项选择的私有方法
    private void handleSpinnerSelection(int position) {
        switch (position) {
            case 0:
                currentChatter = ChatSpin.CHAT_DPSK;
                checkBox.setVisibility(View.VISIBLE);
                chatHint.setVisibility(View.VISIBLE);
                break;
            case 2:
                currentChatter = ChatSpin.CHAT_GPT;
                checkBox.setVisibility(View.GONE);
                chatHint.setVisibility(View.GONE);
                // fall through：切到 OpenRouter 也提示一下当前选项
            default: {
                String[] items = getContext().getResources()
                        .getStringArray(R.array.chat_options);
                // 下标来自外部恢复流程时可能越界，直接 items[position] 会数组越界闪退
                if (position < 0 || position >= items.length) return;
                String current = items[position];
                if (!current.equals(items[items.length - 1])) {
                    Toast.makeText(getContext(), "已切换：" + current, Toast.LENGTH_SHORT).show();
                }
                break;
            }
        }

        // 清除当前输入内容
        etMessage.setText("");
        clearFilePreview();
    }
    public void saveState(Bundle outState) {
        outState.putInt("chat_mode", currentSpinner.ordinal());
    }

    public void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState == null || spinnerOptions == null) return;
        int modeIndex = savedInstanceState.getInt("chat_mode", 2);
        ChatSpin[] values = ChatSpin.values();
        // 存档里的下标可能来自旧版本（枚举项增减过），越界访问会直接崩
        if (modeIndex < 0 || modeIndex >= values.length) modeIndex = 2;
        currentSpinner = values[modeIndex];
        spinnerOptions.setSelection(modeIndex);
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
        if (currentChatter == ChatSpin.CHAT_DPSK) {
            Toast.makeText(getContext(), "该模式不支持发送文件", Toast.LENGTH_SHORT).show();
            return;
        }

        String fileName = getFileNameFromUri(fileUri);
        if (fileName != null) {
            pendingFileUri = fileUri;
            showFilePreview(fileName);
        }
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
    // 发送文本消息
    private void sendTextMessage(String text) {
        Message message = new Message(text, Message.MessageType.SENT_TEXT);
        message.setStatus(Message.MessageStatus.RECEIVING);
        // 每次发送前重灌一次请求头：Key 刚改过也能立刻生效（留空则回落到内置 Key）
        saveApiKey();
        header.token = currentApiKey();
        request.setHeader(header);
        request.start(text);
        messageList.add(message);
        adapter.notifyItemInserted(messageList.size() - 1);
        scrollToBottom();
        // 模拟接收消息（实际开发中替换为网络请求）
        // new Handler().postDelayed(this::receiveMockMessage, 1000);
    }
    private void sendFileMessage(String text) {
        if (adapter == null || rvMessages == null) return;
        Message message = new Message(text, currentSpinner == ChatSpin.MIXED ?
                Message.MessageType.SENT_FILE :
                Message.MessageType.SENT_TEXT);
        messageList.add(message);
        adapter.notifyItemInserted(messageList.size() - 1);
        rvMessages.smoothScrollToPosition(messageList.size() - 1);
        rvMessages.post(() -> {
            rvMessages.smoothScrollToPosition(messageList.size() - 1);
        });
    }


    private void receiveTextMessage(String content) {
        if (adapter == null) return;
        Message message = new Message(content, Message.MessageType.RECEIVED_TEXT);
        message.setStatus(Message.MessageStatus.RECEIVED);
        messageList.add(message);
        adapter.notifyItemInserted(messageList.size() - 1);
        scrollToBottom();
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
                // 进度条
                if (message.getStatus() == Message.MessageStatus.RECEIVING) {
                    receivedHolder.progressBar.setVisibility(View.VISIBLE);
                    receivedHolder.maskView.setVisibility(View.VISIBLE);
                    startProgressAnimation(receivedHolder.progressBar);
                } else {
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
        // 在原有属性基础上添加
        private MessageStatus status = MessageStatus.RECEIVING;

        // 添加状态getter/setter
        public MessageStatus getStatus() { return status; }
        public void setStatus(MessageStatus status) { this.status = status; }
        private final String content;
        private final MessageType type;
        private final long timestamp;

        public Message(String content, MessageType type) {
            this.content = content;
            this.type = type;
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
            Handler handler = null;
            boolean isPost = true;
            boolean think = false;
        }
        Header mHeader = new Header();
        public void setHeader(Header header) {
            mHeader.handler = header.handler;
            mHeader.isPost = header.isPost;
            if (header.token == null || header.token.isEmpty()) {
                header.token = TOKEN;
            }
            headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Safari/605.1.15");
            headers.put("Authorization", "Bearer " + header.token);
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
            payload.put("model", mHeader.think ? "deepseek-reasoner" : "deepseek-chat");
            payload.put("messages", messages);
            payload.put("stream", false);
            return payload.toString();
        }

        /**
         * 把结果丢回主线程。
         *
         * 以前这里复用同一个 android.os.Message 实例：Looper 派发完会把消息回收进全局对象池
         * （recycleUnchecked 仍然保留 FLAG_IN_USE），再 sendMessage 同一个对象就会命中
         * MessageQueue 的 "This message is already in use." IllegalStateException——
         * 也就是「第一轮能聊，第二轮必崩」的根因。必须每次 obtain 一个新的。
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
                // 用 JSONObject 拼而不是手写字符串：用户消息里的引号/换行/emoji 以前会把
                // JSON 拼坏，服务端回 400，界面上就成了"发了没反应"
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
                                }
                            } catch (JSONException e) {
                                // 非预期格式（多数是网关错误页）直接整段回显示，方便排查
                                Log.w("HTTPS", "unexpected response format", e);
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
