package com.tsymiar.device2device.dialog;// Android 基础组件

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.utils.TimeUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class ChatDialog extends Dialog {
    public static final int CHAT_FILE_REQUEST = 1001;
    private Spinner spinnerOptions;
    private EditText etMessage;
    private RecyclerView rvMessages;
    private MessageAdapter adapter;
    private List<Message> messageList = new ArrayList<>();

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
    }

    private void setupViews() {
        // 初始化视图
        spinnerOptions = findViewById(R.id.spinner_options);
        etMessage = findViewById(R.id.et_message);
        rvMessages = findViewById(R.id.rv_messages);
        Button btnAttach = findViewById(R.id.btn_attach);
        Button btnSend = findViewById(R.id.btn_send);

        // 设置下拉选项
        ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(
                getContext(),
                R.array.chat_options,
                android.R.layout.simple_spinner_item
        );
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerOptions.setAdapter(spinnerAdapter);

        // 设置消息列表
        rvMessages.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new MessageAdapter(messageList);
        rvMessages.setAdapter(adapter);

        // 附件按钮点击
        btnAttach.setOnClickListener(v -> openFileChooser());

        // 发送按钮点击
        btnSend.setOnClickListener(v -> {
            String message = etMessage.getText().toString();
            if (!message.isEmpty()) {
                sendTextMessage(message);
                etMessage.setText("");
            }
        });
    }

    private void openFileChooser() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        ((Activity) getContext()).startActivityForResult(
                Intent.createChooser(intent, "选择文件"),
                CHAT_FILE_REQUEST
        );
    }

    // 处理文件选择结果
    public void handleFileResult(Uri fileUri) {
        String filePath = getFilePathFromUri(fileUri);
        if (filePath != null) {
            sendFileMessage(filePath);
        }
    }

    private String getFilePathFromUri(Uri uri) {
        if (uri == null) return null;

        try (Cursor cursor = getContext().getContentResolver().query(
                uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(
                        cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                );
            }
        }
        return uri.getPath();
    }
    private void sendFileMessage(String text) {
        Message message = new Message(text, Message.MessageType.SENT_FILE);
        messageList.add(message);
        adapter.notifyItemInserted(messageList.size() - 1);
        rvMessages.smoothScrollToPosition(messageList.size() - 1);
        rvMessages.post(() -> {
            rvMessages.smoothScrollToPosition(messageList.size() - 1);
        });
    }
    // 发送文本消息
    private void sendTextMessage(String text) {
        Message message = new Message(text, Message.MessageType.SENT_TEXT);
        messageList.add(message);
        adapter.notifyItemInserted(messageList.size() - 1);
        scrollToBottom();
    }

    // 接收模拟消息
    private void receiveMockMessage() {
        Message message = new Message("这是一条模拟回复", Message.MessageType.RECEIVED_TEXT);
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
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());

            if (holder instanceof SentMessageHolder) {
                SentMessageHolder sentHolder = (SentMessageHolder) holder;
                sentHolder.tvContent.setText(message.getContent());
                sentHolder.tvTime.setText(TimeUtils.smartTimeFormat(message.getTimestamp()));

            } else if (holder instanceof ReceivedMessageHolder) {
                ReceivedMessageHolder receivedHolder = (ReceivedMessageHolder) holder;
                receivedHolder.tvContent.setText(message.getContent());
                receivedHolder.tvTime.setText(TimeUtils.smartTimeFormat(message.getTimestamp()));
            }
            if (position > lastPosition) {
                setAnimation(holder.itemView, position);
                lastPosition = position;
            }
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

        class ReceivedMessageHolder extends RecyclerView.ViewHolder {
            TextView tvContent, tvTime;

            ReceivedMessageHolder(View itemView) {
                super(itemView);
                tvContent = itemView.findViewById(R.id.tv_content);
                tvTime = itemView.findViewById(R.id.tv_time);
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
}
