package com.tsymiar.device2device.dialog;
// Android 基础组件
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

public class ChatDialog extends Dialog {
    public static final int CHAT_FILE_REQUEST = 1001;
    private EditText etMessage;
    private RecyclerView rvMessages;
    private MessageAdapter adapter;
    private Spinner spinnerOptions;
    private CheckBox checkBox;
    private TextView chatHint;
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
    public static final int[] State =  {
            0,
            -1
    };
    private ChatSpin currentSpinner = ChatSpin.MIXED;
    private ChatSpin currentChatter = ChatSpin.CHAT_DPSK;

    public ChatDialog(@NonNull Context context) {
        super(context);
        setContentView(R.layout.dialog_chat);
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
    private final Handler handler = new Handler() {
        @SuppressLint({"SetTextI18n", "HandlerLeak"})
        @Override
        public void handleMessage(@NonNull android.os.Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case 0:
                    receiveTextMessage(msg.obj.toString());
                    break;
                case -1:
                    Toast.makeText(getContext(), msg.obj.toString(), Toast.LENGTH_SHORT).show();
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
        rvMessages = findViewById(R.id.rv_messages);
        checkBox = findViewById(R.id.check_dpsk);
        chatHint = findViewById(R.id.check_hint);

        // 设置下拉选项
        ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(
                getContext(),
                R.array.chat_options,
                android.R.layout.simple_spinner_item
        );
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
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
                request.setHeader(header);
            }
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

        // 附件按钮点击
        Button btnAttach = findViewById(R.id.btn_attach);
        Button btnSend = findViewById(R.id.btn_send);
        btnAttach.setOnClickListener(v -> openFileChooser());
        btnAttach.setVisibility(View.VISIBLE);
        etMessage.setVisibility(View.VISIBLE);
        etMessage.setHint(R.string.msg);
        // 发送按钮点击
        btnSend.setOnClickListener(v -> {
            String message = etMessage.getText().toString();
            if (!message.isEmpty()) {
                sendTextMessage(message);
                etMessage.setText("");
            } else {
                Toast.makeText(getContext(), R.string.msg, Toast.LENGTH_SHORT).show();
            }
        });
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
            default:
                Toast.makeText(getContext(), "已切换到：" + getContext().getResources()
                        .getStringArray(R.array.chat_options)[position], Toast.LENGTH_SHORT).show();
                break;
        }

        // 清除当前输入内容
        etMessage.setText("");
    }
    public void saveState(Bundle outState) {
        outState.putInt("chat_mode", currentSpinner.ordinal());
    }

    public void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            int modeIndex = savedInstanceState.getInt("chat_mode", 2);
            currentSpinner = ChatSpin.values()[modeIndex];
            spinnerOptions.setSelection(modeIndex);
        }
    }
    private void openFileChooser() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        Intent choose = Intent.createChooser(intent, "选择文件");
        getContext().startActivity(choose);
    }

    // 处理文件选择结果
    public void handleFileResult(Uri fileUri) {
        if (currentChatter == ChatSpin.CHAT_DPSK) {
            Toast.makeText(getContext(), "该模式不支持发送文件", Toast.LENGTH_SHORT).show();
            return;
        }

        String fileName = getFileNameFromUri(fileUri);
        if (fileName != null) {
            sendFileMessage(fileName);
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
        request.start(text);
        messageList.add(message);
        adapter.notifyItemInserted(messageList.size() - 1);
        scrollToBottom();
        // 模拟接收消息（实际开发中替换为网络请求）
        // new Handler().postDelayed(this::receiveMockMessage, 1000);
    }
    private void sendFileMessage(String text) {
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

    // 接收模拟消息
    private void receiveMockMessage() {
        Message message = new Message("这是一条模拟回复", Message.MessageType.RECEIVED_TEXT);
        messageList.add(message);
        adapter.notifyItemInserted(messageList.size() - 1);
        scrollToBottom();
    }
    private void receiveTextMessage(String content) {
        Message message = new Message(content, Message.MessageType.RECEIVED_TEXT);
        message.setStatus(Message.MessageStatus.RECEIVED);
        messageList.add(message);
        adapter.notifyItemInserted(messageList.size() - 1);
        scrollToBottom();
    }

    // 自动滚动到底部
    private void scrollToBottom() {
        if (!messageList.isEmpty()) {
            rvMessages.post(() -> {
                rvMessages.smoothScrollToPosition(messageList.size() - 1);
                // 或者使用以下代码确保完全滚动到底部
                LinearLayoutManager layoutManager = (LinearLayoutManager) rvMessages.getLayoutManager();
                Objects.requireNonNull(layoutManager).scrollToPositionWithOffset(messageList.size() - 1, 0);
            });
        }
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
                            R.anim.slide_in_sent :
                            R.anim.slide_in_recv);

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
        android.os.Message message = new android.os.Message();
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

        public int start(String text) {
            String method = "GET";
            String body = null;
            if (mHeader.reqUrl == null || mHeader.reqUrl.isEmpty()) {
                mHeader.reqUrl = REQ_URL;
            }
            if (mHeader.isPost) {
                headers.put("Content-Type", "application/json");
                method = "POST";
                body = "{\"model\": \"deepseek-chat\",\"messages\" : [{\"role\": \"system\", \"content\" : \"You are a helpful assistant.\"},{ \"role\": \"user\", \"content\" : \"" + text + "\" }] ,\"stream\" : false}";
                if (mHeader.think) {
                    body = "{\"model\": \"deepseek-reasoner\",\"messages\" : [{\"role\": \"system\", \"content\" : \"You are a helpful assistant.\"},{ \"role\": \"user\", \"content\" : \"" + text + "\" }] ,\"stream\" : false}";
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
                            message.what = State[0];
                            String content;
                            try {
                                JSONObject jsonObject = new JSONObject(response);
                                JSONArray choices = jsonObject.getJSONArray("choices");
                                JSONObject firstChoice = choices.getJSONObject(0);
                                JSONObject message = firstChoice.getJSONObject("message");
                                content = message.getString("content");
                            } catch (JSONException e) {
                                throw new RuntimeException(e);
                            }
                            message.obj = content;
                            mHeader.handler.sendMessage(message);
                            Log.d("HTTPS", "Response: " + response);
                        }
                        @Override
                        public void onFailure(Exception e) {
                            message.what = State[1];
                            message.obj = e.getMessage();
                            mHeader.handler.sendMessage(message);
                            Log.e("HTTPS", "Error: " + e.getMessage());
                        }
                    });
            return 0;
        }
    }
}
