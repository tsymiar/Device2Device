package com.tsymiar.device2device.dialog;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;
import com.tsymiar.device2device.R;
import com.tsymiar.device2device.service.FileMsgServerService;
import com.tsymiar.device2device.utils.FileMsgStore;
import com.tsymiar.device2device.view.WrapContentViewPager;
import com.tsymiar.device2device.utils.Utils;
import com.tsymiar.device2device.wrapper.NetworkWrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FileMsgDialog extends DialogFragment {
    private static final String TAG = "FileMsgDialog";
    private static final int REQUEST_PERMISSION = 1001;

    /** 日志最多保留的行数：超出丢最旧的，长传也不会把内存撑大 */
    private static final int MAX_LOG_LINES = 400;

    /** 发送端上次输入的对端地址：接收端的本机监听地址不会覆盖它 */
    private static final String PREFS_CLIENT = "file_msg_client_prefs";
    private static final String KEY_PEER_IP = "peer_ip";
    private static final String KEY_PEER_PORT = "peer_port";

    private EditText etIp, etPort, etRemote;      // 发送端：对端地址（会持久化）
    private EditText etLocalIp, etListenPort;     // 接收端：本机地址（只读）+ 监听端口
    private ViewPager viewPager;
    private TabLayout tabLayout;
    private final View[] pageViews = new View[2];
    private TextView tvFilePath, tvSpeed;
    private TextView tvLog, tvLogHint, tvState;
    private TextView tvClearLog;
    private View vDot;
    private ScrollView svLog;
    private ProgressBar pbTransfer;
    private CheckBox cbBackoff;
    /** 接收端页的已接收文件列表（Download/Device2Device，见 FileMsgStore） */
    private View receivedFilesHeader;
    private LinearLayout receivedFilesList;

    /** 日志行（带时间戳），渲染时 join 成一段文本 */
    private final Deque<String> logLines = new ArrayDeque<>();
    private final SimpleDateFormat logTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private Button btnSelect, btnStartServer, btnConnect, btnSendFile, btnDisconnect;
    private Button btnCancel, btnDeletePartial, btnPull;
    private View clientPanel;
    private View btnClose;

    private boolean isServerStarted = false;
    private boolean isConnected = false;
    private String selectedFilePath = null;
    private OnFileSelectedListener listener = null;

    /** 待发送文件（主线程维护）；点 Send 后整体转入串行发送队列 */
    private final List<String> selectedFiles = new ArrayList<>();
    private final Queue<String> sendQueue = new ConcurrentLinkedQueue<>();
    /** 传输进行中：期间禁止断开，避免阻塞中的 native 调用被销毁（与 Mac/Transfer 一致） */
    private volatile boolean isBusy = false;
    /** 取消后留在对端的半截文件的原始文件名，供 CMD_DELETE 精确删除 */
    private volatile String pendingDeleteFile = null;

    private long lastProgressBytes = 0;
    private long lastProgressTime = 0;
    private int lastLoggedPct = -1;

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (getActivity() == null) return;
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;

                List<String> picked = new ArrayList<>();
                android.content.ClipData clip = result.getData().getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        String path = getPathFromUri(clip.getItemAt(i).getUri());
                        if (path != null) picked.add(path);
                    }
                } else {
                    Uri single = result.getData().getData();
                    String path = getPathFromUri(single);
                    if (path != null) picked.add(path);
                }
                if (picked.isEmpty()) return;

                selectedFiles.addAll(picked);
                selectedFilePath = picked.get(0);
                if (tvFilePath != null) {
                    tvFilePath.setText(picked.size() == 1
                            ? selectedFilePath
                            : getString(R.string.file_msg_files, picked.size()));
                }
                for (String path : picked) {
                    if (listener != null) listener.onFileSelected(path);
                }
                updateUI();
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
        // 尺寸在 onStart() 里按屏幕算：94% 宽 / 88% 高，内容超出时由根 NestedScrollView 滚动

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

    /** 高度随内容走：内容少时收窄，展开文件面板时长高；只有超过 88% 屏高才兜底夹紧 */
    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        // 去掉主题自带的方形窗口底，露出根卡片的圆角
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager wm = (WindowManager) requireContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        final int width = (int) (dm.widthPixels * 0.94f);
        final int maxHeight = (int) (dm.heightPixels * 0.88f);
        // WRAP_CONTENT 让系统按内容测量；页面高度由 WrapContentViewPager 按内容给出
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        // 极端情况（超大字体 / 展开后的面板）仍可能顶出屏幕，兜底夹紧一次
        window.getDecorView().post(() -> {
            if (getDialog() == null) return;
            if (window.getDecorView().getMeasuredHeight() > maxHeight) {
                window.setLayout(width, maxHeight);
            }
        });
        // 键盘弹出时压缩窗口，别把输入框顶出屏幕
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void initViews(Dialog dialog) {
        // 共用区：标题、状态卡片、日志只有一份，放在 ViewPager 外
        tvSpeed = dialog.findViewById(R.id.tv_file_trans_speed);
        tvLog = dialog.findViewById(R.id.tv_file_trans_log);
        tvLogHint = dialog.findViewById(R.id.tv_file_trans_log_hint);
        tvState = dialog.findViewById(R.id.tv_file_trans_state);
        vDot = dialog.findViewById(R.id.v_file_trans_dot);
        svLog = dialog.findViewById(R.id.sv_file_trans_log);
        tvClearLog = dialog.findViewById(R.id.tv_file_trans_clear);
        pbTransfer = dialog.findViewById(R.id.pb_file_trans);
        btnClose = dialog.findViewById(R.id.btn_file_trans_close);
        viewPager = dialog.findViewById(R.id.vp_file_trans);
        tabLayout = dialog.findViewById(R.id.tab_file_trans);

        if (tvClearLog != null) {
            tvClearLog.setOnClickListener(v -> clearLog());
        }

        // 两页提前 inflate，Adapter 复用这两个 View：控件一开始就能绑定，
        // 不受 ViewPager 何时 instantiateItem 的影响。
        LayoutInflater inflater = LayoutInflater.from(dialog.getContext());
        pageViews[0] = inflater.inflate(R.layout.page_file_msg_receive, viewPager, false);
        pageViews[1] = inflater.inflate(R.layout.page_file_msg_send, viewPager, false);
        View receive = pageViews[0];
        View send = pageViews[1];

        // 接收端：本机 IP 只读，端口是监听端口，与发送端的对端地址互不干扰
        View epReceive = receive.findViewById(R.id.endpoint_receive);
        etLocalIp = epReceive.findViewById(R.id.ep_ip);
        etListenPort = epReceive.findViewById(R.id.ep_port);
        ((TextView) epReceive.findViewById(R.id.ep_ip_label))
                .setText(R.string.file_msg_label_local_ip);
        ((TextView) epReceive.findViewById(R.id.ep_port_label))
                .setText(R.string.file_msg_label_listen_port);
        etLocalIp.setEnabled(false);
        etLocalIp.setText(localIp());
        etListenPort.setText(getString(R.string.file_msg_default_port));
        btnStartServer = receive.findViewById(R.id.btn_file_trans_server);
        cbBackoff = receive.findViewById(R.id.cb_file_trans_backoff);
        receivedFilesHeader = receive.findViewById(R.id.received_files_header);
        receivedFilesList = receive.findViewById(R.id.received_files_list);
        TextView openDownloadsBtn = receive.findViewById(R.id.btn_file_trans_open_downloads);
        if (openDownloadsBtn != null) {
            openDownloadsBtn.setOnClickListener(v -> openDownloads());
        }
        refreshReceivedList();

        // 发送端：对端 IP / 端口恢复上次输入
        View epSend = send.findViewById(R.id.endpoint_send);
        etIp = epSend.findViewById(R.id.ep_ip);
        etPort = epSend.findViewById(R.id.ep_port);
        etRemote = send.findViewById(R.id.et_file_trans_remote);
        tvFilePath = send.findViewById(R.id.tv_file_trans_path);
        btnSelect = send.findViewById(R.id.btn_file_trans_select);
        btnConnect = send.findViewById(R.id.btn_file_trans_connect);
        btnSendFile = send.findViewById(R.id.btn_file_trans_send);
        btnDisconnect = send.findViewById(R.id.btn_file_trans_disconnect);
        btnCancel = send.findViewById(R.id.btn_file_trans_cancel);
        btnDeletePartial = send.findViewById(R.id.btn_file_trans_delete);
        btnPull = send.findViewById(R.id.btn_file_trans_pull);
        clientPanel = send.findViewById(R.id.client_panel);
        restoreClientEndpoint();

        viewPager.setAdapter(new PageAdapter());
        viewPager.setOffscreenPageLimit(2);
        ((WrapContentViewPager) viewPager).setMeasuredPages(pageViews[0], pageViews[1]);
        tabLayout.setupWithViewPager(viewPager);
        // 状态卡片跟随当前页：滑到接收端显示服务端状态，滑到发送端显示客户端状态
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updateStateCard(getContext());
            }
        });
    }

    @SuppressLint({"SetTextI18n", "DefaultLocale"})
    private void initListeners(Dialog dialog) {
        if (btnClose != null) btnClose.setOnClickListener(v -> dismiss());
        btnSelect.setOnClickListener(v -> checkPermissionAndOpenPicker());
        if (cbBackoff != null) {
            cbBackoff.setOnCheckedChangeListener((v, checked) ->
                    NetworkWrapper.setFileBackoffEnabled(checked));
        }

        btnStartServer.setOnClickListener(v -> {
            if (!isServerStarted) {
                int port = readListenPort();
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
            if (isConnected || isBusy) return;
            final String ip = etIp.getText() == null ? "" : etIp.getText().toString().trim();
            final int port = readPort();
            if (port < 0) return;
            saveClientEndpoint();
            // connect 有超时、JNI 还可能等全局锁：必须离开 UI 线程，否则会 ANR
            new Thread(() -> {
                int ret = NetworkWrapper.connectFileMsgServer(ip, port);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (ret >= 0) {
                        isConnected = true;
                        applySavePath();
                        appendLog(String.format("Connected to %s:%d", ip, port));
                    } else {
                        appendLog(String.format("Connect failed: %s:%d (code %d)", ip, port, ret));
                        Toast.makeText(getContext(), "连接失败, 请检查IP/端口: " + ret,
                                Toast.LENGTH_SHORT).show();
                    }
                    updateUI();
                });
            }, "file-msg-connect").start();
        });

        btnSendFile.setOnClickListener(v -> {
            if (!isConnected) {
                Toast.makeText(getContext(), "Not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            if (selectedFiles.isEmpty()) {
                Toast.makeText(getContext(), "Please select a file", Toast.LENGTH_SHORT).show();
                return;
            }
            for (String path : selectedFiles) {
                if (!new File(path).exists()) {
                    Toast.makeText(getContext(), "File not found: " + path, Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            startSendQueue();
        });

        btnCancel.setOnClickListener(v -> {
            if (!isBusy) return;
            // 不能等 postLocalFile 返回：取消要在下一个分片边界立即生效。
            // 进度条保持：取消真正生效时 native 会发 CANCELLED 终态事件，由事件路由收起
            NetworkWrapper.cancelFileTransfer();
            appendLog("Cancel requested - takes effect at next chunk boundary");
        });

        btnDeletePartial.setOnClickListener(v -> {
            final String name = pendingDeleteFile;
            if (name == null || isBusy) return;
            new Thread(() -> {
                int ret = NetworkWrapper.deletePartialFile(name);
                pendingDeleteFile = null;
                appendLog(ret >= 0 ? "Partial deleted on peer: " + name
                        : "Delete partial failed (code " + ret + ")");
                if (getActivity() != null) getActivity().runOnUiThread(this::updateUI);
            }, "file-msg-delete").start();
        });

        btnPull.setOnClickListener(v -> {
            if (isBusy) return;
            String name = etRemote.getText() == null ? "" : etRemote.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(getContext(), "Please enter a remote file name", Toast.LENGTH_SHORT).show();
                return;
            }
            String ip = etIp.getText() == null ? "" : etIp.getText().toString().trim();
            int port = readPort();
            if (port < 0) return;
            saveClientEndpoint();
            isBusy = true;
            updateUI();
            applySavePath();
            appendLog("Pulling: " + name + " from " + ip + ":" + port);
            new Thread(() -> {
                int ret = NetworkWrapper.requestFile(ip, port, name);
                // 拉取自带连接流程，完成后连接保持，UI 状态跟着对齐
                boolean connected = NetworkWrapper.isFileMsgConnected();
                if (getActivity() != null) {
                    final int result = ret;
                    final boolean stillConnected = connected;
                    getActivity().runOnUiThread(() -> {
                        isBusy = false;
                        isConnected = stillConnected;
                        if (result == 0) {
                            appendLog("Pull complete: " + name);
                        } else if (result == -5) {
                            appendLog("File not found on peer: " + name);
                        } else if (result == -8 || result == -2) {
                            appendLog("Pull cancelled: " + name);
                        } else {
                            appendLog("Pull failed (code " + result + ")");
                        }
                        updateUI();
                    });
                }
            }, "file-msg-pull").start();
        });

        btnDisconnect.setOnClickListener(v -> {
            if (isConnected && !isBusy) {
                // 断开会在 JNI 里换出引擎并回收后台线程；即便碰到全局锁也不许占着 UI 线程
                isConnected = false;
                // 终态不等 native 事件：断开就该收起进度条，事件路由赶上重开窗口会丢
                hideTransferBar();
                appendLog("Disconnected");
                updateUI();
                new Thread(NetworkWrapper::disconnectFileMsg, "file-msg-disconnect").start();
            }
        });
    }

    /** 一次点选可多选：串行发送，避免同一连接上多个文件的分片交错 */
    private void startSendQueue() {
        if (isBusy) return;
        for (String path : selectedFiles) sendQueue.offer(path);
        int count = selectedFiles.size();
        selectedFiles.clear();
        if (tvFilePath != null) tvFilePath.setText(getString(R.string.file_msg_files, count));
        isBusy = true;
        updateUI();
        new Thread(this::drainSendQueue, "file-msg-send").start();
    }

    private void drainSendQueue() {
        String path;
        while ((path = sendQueue.poll()) != null) {
            File file = new File(path);
            appendLog("Sending: " + file.getName() + " (" + file.length() + " bytes)");
            int ret = NetworkWrapper.postLocalFile(path);
            if (ret == -2) {
                pendingDeleteFile = file.getName();
                appendLog("Send cancelled: " + file.getName() + " - partial file kept on peer");
                break;
            }
            if (ret < 0) {
                appendLog("Send failed: " + file.getName() + " (code " + ret + ")");
                break;
            }
            appendLog("File sent: " + file.getName());
        }
        sendQueue.clear();
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                isBusy = false;
                updateUI();
            });
        }
    }

    /** 本机 IPv4：先取 Wi-Fi，取不到再遍历网卡（Utils 里的实现，拿不到时回 127.0.0.1） */
    private String localIp() {
        android.content.Context ctx = getContext();
        if (ctx == null) return "127.0.0.1";
        String ip = Utils.getLocalWifiIp(ctx);
        return (ip == null || ip.isEmpty()) ? "127.0.0.1" : ip;
    }

    /** 端口输入框可能为空或非法：需避免 NumberFormatException 导致对话框崩溃，并记录一行日志。
     *  两页各有一个端口输入框（监听端口 / 对端端口），共用这段解析。 */
    private int parsePort(EditText field) {
        if (field == null) return -1;
        String text = field.getText() == null ? "" : field.getText().toString().trim();
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

    /** 发送端：对端端口 */
    private int readPort() {
        return parsePort(etPort);
    }

    /** 接收端：监听端口 */
    private int readListenPort() {
        return parsePort(etListenPort);
    }

    // ===== 日志：带时间戳、可滚动回溯 =====
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
            // 用户上翻查看历史时不应将其拉回底部：仅当本就位于底部时才自动跟随
            boolean follow = isLogAtBottom();
            logLines.addLast(line);
            while (logLines.size() > MAX_LOG_LINES) {
                logLines.removeFirst();
            }
            tvLog.setText(TextUtils.join("\n", logLines));
            if (tvLogHint != null) tvLogHint.setVisibility(View.GONE);
            if (tvClearLog != null) tvClearLog.setEnabled(true);
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
        if (tvLogHint != null) tvLogHint.setVisibility(View.VISIBLE);
        if (tvClearLog != null) tvClearLog.setEnabled(false);
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
                (ok, dest) -> {
                    if (ok && getActivity() != null) {
                        getActivity().runOnUiThread(this::refreshReceivedList);
                    }
                    appendLog(ok ? "Saved: " + dest
                            : "Kept in app storage (public save unavailable): " + dest);
                });
    }

    /** 重建「已接收文件」列表：FileMsgStore 的持久化记录，新的在前；没有记录时整块隐藏 */
    private void refreshReceivedList() {
        Context ctx = getContext();
        if (ctx == null || receivedFilesList == null) return;
        receivedFilesList.removeAllViews();
        JSONArray arr = FileMsgStore.receivedList(ctx);
        if (receivedFilesHeader != null) {
            receivedFilesHeader.setVisibility(arr.length() > 0 ? View.VISIBLE : View.GONE);
        }
        int text = ContextCompat.getColor(ctx, R.color.ui_text);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            TextView row = new TextView(ctx);
            row.setText(o.optString("name"));
            row.setTextSize(12);
            row.setTextColor(text);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.setPadding(0, (int) dp(6), 0, (int) dp(6));
            row.setOnClickListener(v -> openDownloads());
            receivedFilesList.addView(row);
        }
    }

    /** 跳系统下载管理器：接收的文件都在 Download/Device2Device 下，从那里能定位打开 */
    private void openDownloads() {
        Activity act = getActivity();
        if (act == null) return;
        try {
            act.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(act, R.string.file_msg_open_downloads, Toast.LENGTH_SHORT).show();
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
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
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        filePickerLauncher.launch(intent);
    }

    private String getPathFromUri(Uri uri) {
        if (uri == null) return null;
        if (getContext() == null) return uri.getLastPathSegment();

        // 使用 ContentResolver 复制文件到 app 私有目录
        try {
            ContentResolver resolver = getContext().getContentResolver();
            String fileName = null;

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

    /** 收起进度条与速度行：断开 / 取消等终态动作的即时反馈，不等事件路由 */
    private void hideTransferBar() {
        if (pbTransfer != null) pbTransfer.setVisibility(View.GONE);
        if (tvSpeed != null) tvSpeed.setVisibility(View.GONE);
    }

    private void updateUI() {
        Context ctx = getContext();
        if (ctx != null) {
            btnStartServer.setText(ctx.getString(isServerStarted
                    ? R.string.file_msg_stop_server : R.string.file_msg_start_server));
        }

        updateStateCard(ctx);

        if (isServerStarted) {
            // 服务端在跑：只更新接收端的本机地址 / 监听端口，
            // 发送端「上次输入的对端地址」保持不动（以前会被本机 IP 覆盖）。
            String ep = FileMsgServerService.getEndpoint(requireContext());
            int idx = ep.lastIndexOf(':');
            if (idx > 0) {
                etLocalIp.setText(ep.substring(0, idx));
                etListenPort.setText(ep.substring(idx + 1));
            }
            etListenPort.setEnabled(false);
        } else {
            etLocalIp.setText(localIp());
            etListenPort.setEnabled(true);
        }

        // 服务端运行时不能当客户端去连；已连接时 Connect 也禁用
        btnStartServer.setEnabled(!isBusy);
        btnConnect.setEnabled(!isServerStarted && !isConnected && !isBusy);
        // 发送端面板（选文件 + 发送/断开）只在连接成功后展开
        if (clientPanel != null) {
            clientPanel.setVisibility(isConnected ? View.VISIBLE : View.GONE);
        }
        btnSelect.setEnabled(!isBusy);
        btnSendFile.setEnabled(!selectedFiles.isEmpty() && isConnected && !isBusy);
        // 传输中禁止断开：native 的发送还在阻塞，销毁引擎会踩到已释放对象
        btnDisconnect.setEnabled(isConnected && !isBusy);
        btnCancel.setEnabled(isBusy);
        btnDeletePartial.setEnabled(isConnected && !isBusy && pendingDeleteFile != null);
        btnPull.setEnabled(!isBusy && !isServerStarted);
        if (etRemote != null) etRemote.setEnabled(!isBusy);
    }

    /** 顶部状态卡片：一个圆点 + 一行文字，别让"服务端在跑/已连接/传输中"只藏在日志里。
     *  状态跟着当前页走：接收端页只呈现服务端状态，发送端的连接/传输状态混进来容易误解 */
    private void updateStateCard(Context ctx) {
        if (ctx == null) return;
        int page = viewPager != null ? viewPager.getCurrentItem() : 0;
        boolean active;
        int res;
        if (page == 0) {
            active = isServerStarted;
            res = active ? R.string.file_msg_state_server : R.string.file_msg_state_idle;
        } else {
            active = isBusy || isConnected;
            if (isBusy) res = R.string.file_msg_state_busy;
            else if (isConnected) res = R.string.file_msg_state_connected;
            else res = R.string.file_msg_state_idle;
        }
        if (vDot != null) vDot.setActivated(active);
        if (tvState == null) return;
        tvState.setText(ctx.getString(res));
        tvState.setTextColor(ContextCompat.getColor(ctx,
                active ? R.color.ui_ok : R.color.ui_text_dim));
    }

    /** 两页的 Adapter：复用 initViews 里提前 inflate 的 View，避免页面创建时机问题 */
    private final class PageAdapter extends PagerAdapter {
        @Override
        public int getCount() {
            return pageViews.length;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            View page = pageViews[position];
            if (page.getParent() != null) {
                ((ViewGroup) page.getParent()).removeView(page);
            }
            container.addView(page);
            return page;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }

        @Nullable
        @Override
        public CharSequence getPageTitle(int position) {
            return getString(position == 0
                    ? R.string.file_msg_tab_receive : R.string.file_msg_tab_send);
        }
    }

    /** 恢复发送端上次输入的对端地址；接收端的监听端口不参与持久化 */
    private void restoreClientEndpoint() {
        Context ctx = getContext();
        if (ctx == null || etIp == null || etPort == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_CLIENT, Context.MODE_PRIVATE);
        etIp.setText(prefs.getString(KEY_PEER_IP, getString(R.string.file_msg_default_ip)));
        etPort.setText(prefs.getString(KEY_PEER_PORT, getString(R.string.file_msg_default_port)));
    }

    private void saveClientEndpoint() {
        Context ctx = getContext();
        if (ctx == null || etIp == null || etPort == null) return;
        String ip = etIp.getText() == null ? "" : etIp.getText().toString().trim();
        String port = etPort.getText() == null ? "" : etPort.getText().toString().trim();
        if (ip.isEmpty() && port.isEmpty()) return;
        ctx.getSharedPreferences(PREFS_CLIENT, Context.MODE_PRIVATE).edit()
                .putString(KEY_PEER_IP, ip)
                .putString(KEY_PEER_PORT, port)
                .apply();
    }

    public void updateStatus(String status) {
        appendLog(readableStatus(status));
    }

    /** DialogFragment 本身没有 isShowing()，委托给内部 Dialog */
    public boolean isShowing() {
        Dialog d = getDialog();
        return d != null && d.isShowing();
    }

    /** 兼容调用：没有事件码时按常规进度处理 */
    public void updateProgress(long current, long total, String status) {
        updateProgress(current, total, status, Progress.EVT_NONE);
    }

    public void updateProgress(long current, long total, String status, int event) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            // 新文件开始 / 上一个文件结束：速度采样要重新起算
            if (event == Progress.EVT_RECEIVE_START || event == Progress.EVT_SEND_START
                    || event == Progress.EVT_TRANSFER_COMPLETE) {
                lastProgressBytes = 0;
                lastProgressTime = 0;
                lastLoggedPct = -1;
            }
            // 终态里只有「断开」收进度条；取消按设计冻结在最后位置
            boolean terminal = event == Progress.EVT_TRANSFER_COMPLETE
                    || event == Progress.EVT_DISCONNECTED;
            boolean ongoing = !terminal && total > 0 && current < total;
            if (pbTransfer != null) {
                // total<=0 是纯状态消息（客户端上下线等），没有进度可言，直接收起进度条
                if (!ongoing) {
                    pbTransfer.setVisibility(View.GONE);
                } else {
                    pbTransfer.setVisibility(View.VISIBLE);
                    pbTransfer.setProgress((int) (current * 100 / total));
                }
            }

            long now = System.currentTimeMillis();
            if (ongoing && lastProgressTime > 0 && current > lastProgressBytes && now > lastProgressTime) {
                double mbps = (double) (current - lastProgressBytes) * 1000.0
                        / (now - lastProgressTime) / (1024.0 * 1024.0);
                if (tvSpeed != null) {
                    tvSpeed.setText(String.format(Locale.US, "%d%%  ·  %.1f MB/s  ·  %s",
                            (int) (current * 100 / total), mbps, readableStatus(status)));
                    tvSpeed.setVisibility(View.VISIBLE);
                }
            } else if (!ongoing && tvSpeed != null) {
                tvSpeed.setVisibility(View.GONE);
            }
            lastProgressBytes = current;
            lastProgressTime = now;

            // 事件消息必打；常规进度按 1% 步进去重，避免刷屏
            int pct = total > 0 ? (int) (current * 100 / total) : 0;
            if (event != Progress.EVT_NONE || pct != lastLoggedPct) {
                String text = readableStatus(status);
                if (total > 0) {
                    appendLog(String.format(Locale.US, "%s  %d%% (%d/%d)",
                            text, pct, current, total));
                } else {
                    appendLog(text);
                }
                lastLoggedPct = pct;
            }

            // 完成消息把真实落盘路径带在 "|" 后面：搬到公共下载目录（Android/data 用户看不到）
            if (event == Progress.EVT_TRANSFER_COMPLETE && status != null) {
                int bar = status.indexOf('|');
                if (bar >= 0) publishToDownloads(status.substring(bar + 1));
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

    /**
     * native 进度消息 "status[|落盘路径]|current|total|event" 的解析结果。
     * 事件码与 FileMsgSocket.h 的 FILE_EVT_* / Mac/Transfer 的 FT_EVT_* 一一对应；
     * 末段不是合法事件码时按旧三段格式解析，兼容旧版 native。
     */
    public static final class Progress {
        public static final int EVT_NONE = 0;
        public static final int EVT_CONNECTED = 1;
        public static final int EVT_DISCONNECTED = 2;
        public static final int EVT_RECEIVE_START = 3;
        public static final int EVT_TRANSFER_COMPLETE = 4;
        public static final int EVT_TRANSFER_CANCELLED = 5;
        public static final int EVT_REQUEST_PULL = 6;
        public static final int EVT_SEND_START = 7;

        public final String status;
        public final long current;
        public final long total;
        public final int event;
        public final boolean valid;

        private Progress(String status, long current, long total, int event, boolean valid) {
            this.status = status;
            this.current = current;
            this.total = total;
            this.event = event;
            this.valid = valid;
        }

        public static Progress parse(String data) {
            if (data == null) return new Progress("", 0, 0, EVT_NONE, false);
            String[] parts = data.split("\\|", -1);
            int n = parts.length;
            if (n < 3) return new Progress(data, 0, 0, EVT_NONE, false);

            int event = -1;
            if (n >= 4) {
                try {
                    int code = Integer.parseInt(parts[n - 1].trim());
                    if (code >= EVT_NONE && code <= EVT_SEND_START) event = code;
                } catch (NumberFormatException ignored) {
                }
            }
            final int totalIdx = event >= 0 ? n - 2 : n - 1;
            final int currentIdx = event >= 0 ? n - 3 : n - 2;
            try {
                long total = Long.parseLong(parts[totalIdx].trim());
                long current = Long.parseLong(parts[currentIdx].trim());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < currentIdx; i++) {
                    if (i > 0) sb.append('|');
                    sb.append(parts[i]);
                }
                return new Progress(sb.toString(), current, total,
                        event >= 0 ? event : EVT_NONE, true);
            } catch (NumberFormatException e) {
                return new Progress(data, 0, 0, EVT_NONE, false);
            }
        }

        /** 去掉 "|真实落盘路径" 后的可读状态 */
        public String readable() {
            if (status == null) return "";
            int bar = status.indexOf('|');
            return bar >= 0 ? status.substring(0, bar) : status;
        }

        /** native 暂存路径（完成消息才有），供搬到公共 Download 目录 */
        @Nullable
        public String savedPath() {
            if (status == null) return null;
            int bar = status.indexOf('|');
            if (bar < 0 || bar == status.length() - 1) return null;
            return status.substring(bar + 1);
        }

        /** 状态卡片的一行文字 */
        public String hintLine() {
            String text = readable();
            if (total > 0) {
                return String.format(Locale.US, "%s  %d%% (%d/%d)",
                        text, (int) (current * 100 / total), current, total);
            }
            return text;
        }
    }

    @Override
    public void onDestroy() {
        // 服务端由前台服务托管，这里不再停 —— 对话框关掉后对端照样能连上来收文件。
        // 只有显式点「Stop Server」才走 FileMsgServerService.stop()。
        saveClientEndpoint();   // 改了地址没点 Connect 也要记住
        if (isConnected) {
            if (isBusy) NetworkWrapper.cancelFileTransfer();
            // onDestroy 也在主线程：断开可能等全局锁 / 后台线程，放到子线程避免 ANR
            new Thread(NetworkWrapper::disconnectFileMsg, "file-msg-disconnect").start();
            isConnected = false;
        }
        try {
            requireContext().unregisterReceiver(mStateReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
