package com.tsymiar.device2device.dialog;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.service.FileMsgServerService;
import com.tsymiar.device2device.utils.FileMsgStore;
import com.tsymiar.device2device.utils.Utils;
import com.tsymiar.device2device.wrapper.NetworkWrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;

public class FileMsgDialog extends DialogFragment {
    private static final String TAG = "FileMsgDialog";
    private static final int REQUEST_PERMISSION = 1001;

    /** 日志最多保留的行数：超出丢最旧的，长传也不会把内存撑大 */
    private static final int MAX_LOG_LINES = 400;

    private EditText etIp, etPort;
    private TextView tvFilePath;
    private TextView tvLog;
    private TextView tvClearLog;
    private ScrollView svLog;
    private ProgressBar pbTransfer;

    /** 日志行（带时间戳），渲染时 join 成一段文本 */
    private final Deque<String> logLines = new ArrayDeque<>();
    private final SimpleDateFormat logTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private Button btnSelect, btnStartServer, btnConnect, btnSendFile, btnDisconnect;
    private View clientPanel;

    private boolean isServerStarted = false;
    private boolean isConnected = false;
    private String selectedFilePath = null;
    private OnFileSelectedListener listener = null;

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (getActivity() == null) return;
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        selectedFilePath = getPathFromUri(uri);
                        if (tvFilePath != null) {
                            tvFilePath.setText(selectedFilePath != null ? selectedFilePath : uri.getLastPathSegment());
                        }
                        if (btnSendFile != null) {
                            btnSendFile.setEnabled(selectedFilePath != null && isConnected);
                        }
                        if (listener != null && selectedFilePath != null) {
                            listener.onFileSelected(selectedFilePath);
                        }
                    }
                }
            }
    );

    /**
     * 前台服务的启动 / 停止结果。服务是异步起来的（startForegroundService），
     * 真正的成功失败由这条广播回写日志。
     */
    private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !FileMsgServerService.ACTION_STATE.equals(intent.getAction())) {
                return;
            }
            String state = intent.getStringExtra(FileMsgServerService.EXTRA_STATE);
            if (FileMsgServerService.STATE_STARTED.equals(state)) {
                isServerStarted = true;
                appendLog("Server started - peer connects to "
                        + FileMsgServerService.getEndpoint(context)
                        + " · 对话框关闭后仍在后台运行");
            } else if (FileMsgServerService.STATE_FAILED.equals(state)) {
                isServerStarted = false;
                String reason = intent.getStringExtra(FileMsgServerService.EXTRA_REASON);
                appendLog("Server start failed: " + (reason == null ? "unknown" : reason));
            } else if (FileMsgServerService.STATE_STOPPED.equals(state)) {
                isServerStarted = false;
                appendLog("Server has stopped by service");
            }
            updateUI();
        }
    };

    private final ActivityResultLauncher<String> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    openFilePicker();
                } else {
                    if (getContext() != null) {
                        Toast.makeText(getContext(), "Storage permission required", Toast.LENGTH_SHORT).show();
                    }
                }
            }
    );

    public interface OnFileSelectedListener {
        void onFileSelected(String filePath);
    }

    public static FileMsgDialog newInstance() {
        return new FileMsgDialog();
    }

    public void setOnFileSelectedListener(OnFileSelectedListener listener) {
        this.listener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @NonNull
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setContentView(R.layout.dialog_file_msg);
        Objects.requireNonNull(dialog.getWindow()).setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT);

        initViews(dialog);
        initListeners(dialog);
        // 服务不会因为对话框关闭而停：重新打开时先把按钮状态对齐真实运行情况
        isServerStarted = FileMsgServerService.isRunning();
        updateUI();

        try {
            requireContext().registerReceiver(mStateReceiver,
                    new IntentFilter(FileMsgServerService.ACTION_STATE));
        } catch (Exception e) {
            Log.w(TAG, "register state receiver failed: " + e.getMessage());
        }

        // 开头几条：本机 IP（对端要连的就是它）+ 最终落盘位置 + 服务是否已在跑
        FileMsgStore.applySavePath(requireContext());
        appendLog("Local IP: " + localIp());
        appendLog("Ready - files will be saved to " + FileMsgStore.publicDir());
        if (isServerStarted) {
            appendLog("Server already running - peer connects to "
                    + FileMsgServerService.getEndpoint(requireContext()));
        }
        // 补搬：上次收完时对话框已关、没来得及搬走的残留文件
        FileMsgStore.flushInbox(requireContext(),
                (ok, dest) -> appendLog(ok ? "Saved: " + dest : "Kept in app storage: " + dest));
        return dialog;
    }

    private void initViews(Dialog dialog) {
        etIp = dialog.findViewById(R.id.et_file_trans_ip);
        etPort = dialog.findViewById(R.id.et_file_trans_port);
        tvFilePath = dialog.findViewById(R.id.tv_file_trans_path);
        tvLog = dialog.findViewById(R.id.tv_file_trans_log);
        svLog = dialog.findViewById(R.id.sv_file_trans_log);
        tvClearLog = dialog.findViewById(R.id.tv_file_trans_clear);
        pbTransfer = dialog.findViewById(R.id.pb_file_trans);

        if (tvClearLog != null) {
            tvClearLog.setOnClickListener(v -> clearLog());
        }

        btnSelect = dialog.findViewById(R.id.btn_file_trans_select);
        btnStartServer = dialog.findViewById(R.id.btn_file_trans_server);
        btnConnect = dialog.findViewById(R.id.btn_file_trans_connect);
        btnSendFile = dialog.findViewById(R.id.btn_file_trans_send);
        btnDisconnect = dialog.findViewById(R.id.btn_file_trans_disconnect);
        clientPanel = dialog.findViewById(R.id.client_panel);
    }

    @SuppressLint({"SetTextI18n", "DefaultLocale"})
    private void initListeners(Dialog dialog) {
        btnSelect.setOnClickListener(v -> checkPermissionAndOpenPicker());

        btnStartServer.setOnClickListener(v -> {
            if (!isServerStarted) {
                int port = readPort();
                if (port < 0) return;
                // 交给前台服务托管：对话框关掉 / 息屏后服务照样在，对端才连得上。
                // 启动结果由 ACTION_STATE 广播回写日志（异步，见 mStateReceiver）。
                FileMsgServerService.start(requireContext(), port);
                appendLog("Starting server on port " + port + " ...");
            } else {
                FileMsgServerService.stop(requireContext());
                isServerStarted = false;
                appendLog("Server has stopped by user");
            }
            updateUI();
        });

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                String ip = etIp.getText() == null ? "" : etIp.getText().toString().trim();
                int port = readPort();
                if (port < 0) return;
                int ret = NetworkWrapper.connectFileMsgServer(ip, port);
                if (ret >= 0) {
                    isConnected = true;
                    applySavePath();
                    appendLog(String.format("Connected to %s:%d", ip, port));
                } else {
                    appendLog(String.format("Connect failed: %s:%d (code %d)", ip, port, ret));
                    Toast.makeText(getContext(), "连接失败, 请检查IP/端口: " + ret, Toast.LENGTH_SHORT).show();
                }
            }
            updateUI();
        });

        btnSendFile.setOnClickListener(v -> {
            if (!isConnected) {
                Toast.makeText(getContext(), "Not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            if (selectedFilePath == null) {
                Toast.makeText(getContext(), "Please select a file", Toast.LENGTH_SHORT).show();
                return;
            }
            File file = new File(selectedFilePath);
            if (!file.exists()) {
                Toast.makeText(getContext(), "File not found: " + selectedFilePath, Toast.LENGTH_SHORT).show();
                return;
            }
            appendLog("Sending: " + file.getName() + " (" + file.length() + " bytes)");
            new Thread(() -> {
                int ret = NetworkWrapper.postLocalFile(selectedFilePath);
                if (ret >= 0) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            appendLog("File sent: " + file.getName());
                            Toast.makeText(getContext(), "File sent successfully", Toast.LENGTH_SHORT).show();
                        });
                    }
                } else {
                    appendLog("Send failed: " + ret);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() ->
                                Toast.makeText(getContext(), "Send failed: " + ret, Toast.LENGTH_SHORT).show()
                        );
                    }
                }
            }).start();
        });

        btnDisconnect.setOnClickListener(v -> {
            if (isConnected) {
                NetworkWrapper.disconnectFileMsg();
                isConnected = false;
                appendLog("Disconnected");
            }
            updateUI();
        });
    }

    /** 本机 IPv4：先取 Wi-Fi，取不到再遍历网卡（Utils 里的实现，拿不到时回 127.0.0.1） */
    private String localIp() {
        android.content.Context ctx = getContext();
        if (ctx == null) return "127.0.0.1";
        String ip = Utils.getLocalWifiIp(ctx);
        return (ip == null || ip.isEmpty()) ? "127.0.0.1" : ip;
    }

    /** 端口输入框可能为空 / 非法：别让 NumberFormatException 把对话框崩掉，记一行日志 */
    private int readPort() {
        if (etPort == null) return -1;
        String text = etPort.getText() == null ? "" : etPort.getText().toString().trim();
        try {
            int port = Integer.parseInt(text);
            if (port <= 0 || port > 65535) {
                appendLog("Port out of range: " + text);
                return -1;
            }
            return port;
        } catch (NumberFormatException e) {
            appendLog("Invalid port: '" + text + "'");
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // 日志：带时间戳、可滚动回溯
    // ------------------------------------------------------------------

    /** 追加一行日志；可在任意线程调用，内部切回主线程 */
    private void appendLog(String message) {
        if (tvLog == null || message == null) return;

        String stamp;
        synchronized (logTime) {
            stamp = logTime.format(new Date());
        }
        final String line = stamp + "  " + message;

        Runnable append = () -> {
            if (tvLog == null) return;
            // 用户翻上去看历史时别把他拽回底部：只有本来就在底部才自动跟随
            boolean follow = isLogAtBottom();
            logLines.addLast(line);
            while (logLines.size() > MAX_LOG_LINES) {
                logLines.removeFirst();
            }
            tvLog.setText(TextUtils.join("\n", logLines));
            if (follow && svLog != null) {
                svLog.post(() -> {
                    if (svLog != null) svLog.fullScroll(View.FOCUS_DOWN);
                });
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            append.run();
        } else if (getActivity() != null) {
            getActivity().runOnUiThread(append);
        }
    }

    /** 当前是不是停在日志底部（容差 32px，覆盖最后一行露一半的情况） */
    private boolean isLogAtBottom() {
        if (svLog == null || tvLog == null) return true;
        return svLog.getScrollY() >= tvLog.getHeight() - svLog.getHeight() - 32;
    }

    private void clearLog() {
        logLines.clear();
        if (tvLog != null) tvLog.setText("");
    }

    /**
     * 给 native 的落盘目录（服务端收文件时靠它落盘）。
     * 具体位置与"搬到公共下载目录"的逻辑都在 FileMsgStore 里，服务与对话框共用一套。
     */
    private String applySavePath() {
        android.content.Context ctx = getContext();
        if (ctx == null) return "";
        return FileMsgStore.applySavePath(ctx);
    }

    /**
     * 收完一个文件后把暂存文件搬到公共下载目录（Android/data 用户打不开，所以必须搬）。
     * 搬运行为在 FileMsgStore 里，服务托管时对话框可能已经关了，那里同样能搬。
     */
    private void publishToDownloads(final String srcPath) {
        if (srcPath == null || srcPath.isEmpty()) return;
        Context ctx = getContext();
        if (ctx == null) return;
        FileMsgStore.publishAsync(ctx, srcPath,
                (ok, dest) -> appendLog(ok ? "Saved: " + dest
                        : "Kept in app storage (public save unavailable): " + dest));
    }

    private void checkPermissionAndOpenPicker() {
        if (getContext() == null) return;
        /*
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED) {
                openFilePicker();
            } else {
                permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES);
            }
        } else */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openFilePicker();
        } else {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                openFilePicker();
            } else {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    private String getPathFromUri(Uri uri) {
        if (uri == null) return null;
        if (getContext() == null) return uri.getLastPathSegment();

        // 使用 ContentResolver 复制文件到 app 私有目录
        try {
            ContentResolver resolver = getContext().getContentResolver();
            String fileName = null;

            // 尝试获取文件名
            String[] projection = {"_display_name"};
            try (android.database.Cursor cursor = resolver.query(uri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex("_display_name");
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex);
                    }
                }
            }

            if (fileName == null) {
                fileName = "temp_file_" + System.currentTimeMillis();
            }

            // 复制到 app 私有目录
            File cacheDir = getContext().getCacheDir();
            File tempFile = new File(cacheDir, fileName);

            try (InputStream input = resolver.openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(tempFile)) {
                if (input == null) return uri.getLastPathSegment();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
            }

            return tempFile.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return uri.getLastPathSegment();
        }
    }

    private void updateUI() {
        btnStartServer.setText(isServerStarted ? "Stop Server" : "Start Server");

        if (isServerStarted) {
            // 服务端在跑：IP/Port 填上监听地址并置灰，不可再编辑
            String ep = FileMsgServerService.getEndpoint(requireContext());
            int idx = ep.lastIndexOf(':');
            if (idx > 0) {
                etIp.setText(ep.substring(0, idx));
                etPort.setText(ep.substring(idx + 1));
            }
            etIp.setEnabled(false);
            etPort.setEnabled(false);
        } else {
            etIp.setEnabled(true);
            etPort.setEnabled(true);
        }

        // 服务端运行时不能当客户端去连；已连接时 Connect 也禁用
        btnConnect.setEnabled(!isServerStarted && !isConnected);
        // 发送端面板（选文件 + 发送/断开）只在连接成功后展开
        if (clientPanel != null) {
            clientPanel.setVisibility(isConnected ? View.VISIBLE : View.GONE);
        }
        btnSendFile.setEnabled(selectedFilePath != null && isConnected);
        btnDisconnect.setEnabled(isConnected);
    }

    public void updateStatus(String status) {
        appendLog(readableStatus(status));
    }

    /** DialogFragment 本身没有 isShowing()，委托给内部 Dialog */
    public boolean isShowing() {
        Dialog d = getDialog();
        return d != null && d.isShowing();
    }

    public void updateProgress(long current, long total, String status) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (pbTransfer != null) {
                // total<=0 是纯状态消息（客户端上下线等），没有进度可言，直接收起进度条
                if (total <= 0 || current >= total) {
                    pbTransfer.setVisibility(View.GONE);
                } else {
                    pbTransfer.setVisibility(View.VISIBLE);
                    pbTransfer.setProgress((int) (current * 100 / total));
                }
            }
            String text = readableStatus(status);
            if (total > 0) {
                appendLog(String.format(Locale.US, "%s  %d%% (%d/%d)",
                        text, (int) (current * 100 / total), current, total));
            } else {
                appendLog(text);
            }
            // 完成消息把真实落盘路径带在 "|" 后面：搬到公共下载目录（Android/data 用户看不到）
            int bar = status == null ? -1 : status.indexOf('|');
            if (bar >= 0) {
                publishToDownloads(status.substring(bar + 1));
            }
        });
    }

    /**
     * 完成类消息会把真实落盘路径带在 "|" 后面（接收端文件名带时间戳，UI 自己拼不出来）。
     * 这里只留前半句：后半段是 native 的暂存路径，马上会被搬走并删掉，打出来反而误导，
     * 最终位置由 publishToDownloads() 单独打一行 "Saved: ..."。
     */
    private static String readableStatus(String status) {
        if (status == null) return "";
        int bar = status.indexOf('|');
        if (bar < 0) return status;
        return status.substring(0, bar);
    }

    @Override
    public void onDestroy() {
        // 服务端由前台服务托管，这里不再停 —— 对话框关掉后对端照样能连上来收文件。
        // 只有显式点「Stop Server」才走 FileMsgServerService.stop()。
        if (isConnected) {
            NetworkWrapper.disconnectFileMsg();
            isConnected = false;
        }
        try {
            requireContext().unregisterReceiver(mStateReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
