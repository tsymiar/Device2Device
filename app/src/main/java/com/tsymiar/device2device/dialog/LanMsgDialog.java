package com.tsymiar.device2device.dialog;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.utils.Utils;
import com.tsymiar.device2device.wrapper.NetworkWrapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 局域网文本消息对话框。
 *
 * 一个实例同时是服务端也是客户端，收发走 KCP over UDP：
 *  - 收发：C++ 侧只开一个绑 0.0.0.0:本端口的 KCP socket，局域网里谁都能发进来；
 *          发给谁就按对方地址取（必要时新建）一份 ikcp 会话，不需要先建立连接；
 *          超过一条 KCP 消息放得下的大图会自动分片，对端拼回去再往上抛。
 *  - 设备发现：C++ 侧周期性 UDP 广播喊话，收到别人的喊话就进列表，超时不喊就摘掉。
 *
 * 发现结果与收发内容由 SelectActivity 的轮询线程（Receiver.LAN_PEER / LAN_MESSAGE）转发进来，
 * 转发走的是主线程 Handler，所以这里可以直接碰 View。
 */
public class LanMsgDialog extends Dialog {

    /** 选图请求码：SelectActivity.onActivityResult 按它把结果转发回来 */
    public static final int LAN_IMAGE_REQUEST = 1002;

    private static final class Peer {
        final String ip;
        final int port;
        String name;

        Peer(String ip, int port, String name) {
            this.ip = ip;
            this.port = port;
            this.name = name;
        }

        String key() {
            return ip + ":" + port;
        }
    }

    /** 消息行的说话方，决定这一行用什么颜色 */
    private static final int KIND_SYS = 0;    // 系统提示（启动 / 停止 / 发现）
    private static final int KIND_SELF = 1;   // 我发出去的
    private static final int KIND_PEER = 2;   // 对端发来的
    private static final int KIND_ERROR = 3;  // 发送失败

    private EditText etName;
    private EditText etPort;
    private Button btnStart;
    private Button btnRefresh;
    private View vDot;
    private TextView tvState;
    private ListView lvPeers;
    private ScrollView svChat;
    /** 气泡容器：每条消息是一个 item_lan_bubble */
    private LinearLayout llChat;
    /** 消息区空态提示，第一条消息进来就隐藏 */
    private TextView tvChatHint;
    /** 「清空」：删掉所有历史气泡 */
    private TextView btnClear;
    private EditText etMsg;
    private Button btnSend;

    /** 已发现的对端，按发现顺序排（LinkedHashMap 保证列表顺序稳定） */
    private final Map<String, Peer> mPeers = new LinkedHashMap<>();
    private PeerAdapter mPeerAdapter;
    /** 当前选中的发送目标；为 null 时发送按钮不可用 */
    private Peer mTarget = null;

    private boolean mStarted = false;
    /** 这次停是"退到后台超时"自动停的；回到前台据此恢复，用户自己点的「停止」不该被拉起来 */
    private boolean mAutoStopped = false;
    private int mPort = 39001;
    /** 发现广播地址/端口（与收发 KCP 端口 39001 分开；默认广播 255.255.255.255 + 端口 39000） */
    private EditText etDiscoveryAddr;
    private EditText etDiscoveryPort;
    private int mDiscoveryPort = 39000;
    /** 消息区最多保留的条数，长聊也不会把内存撑大 */
    private static final int MAX_CHAT_LINES = 300;

    // ---------- 图片 ----------
    /** 发送前把最长边缩到这个尺寸：再大画质提升有限，报文长度却成倍涨 */
    private static final int IMAGE_SEND_MAX_PX = 1024;
    /** 气泡里显示的尺寸：气泡只有半屏宽，按发送尺寸留着是白占内存 */
    private static final int IMAGE_SHOW_MAX_PX = 480;
    /** base64 字符数上限，要和 native 的 LAN_MAX_IMAGE_CHARS 对上 */
    private static final int IMAGE_MAX_CHARS = 256 * 1024;
    /** 压不下来时逐档降的 {最长边, JPEG 质量} */
    private static final int[] IMAGE_QUALITY_STEPS = {1024, 80, 800, 70, 640, 60, 480, 50};

    /** 设备列表最多显示的行数：超出部分在该高度内滚动，避免对话框超出屏幕 */
    private static final int MAX_PEER_ROWS = 3;
    /** 设备列表行高，要和 item_lan_peer 里的 layout_height 对上 */
    private static final float PEER_ROW_DP = 34f;
    /** 系统提示的字号：聊天气泡是 13sp，系统提示小一号 */
    private static final float SYS_TEXT_SP = 11f;

    /** native 侧正在收尾：这期间不能再启动，否则新旧两轮订阅会抢同一个端口 */
    private boolean mStopping = false;

    /** 广播接收在部分 ROM 上要持有 MulticastLock 才不被过滤掉 */
    private WifiManager.MulticastLock mMulticastLock = null;

    private final SimpleDateFormat mTimeFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    /** 选图要 startActivityForResult，不是 Activity 就没法发起 */
    private final android.app.Activity mActivity;
    private Button btnImage;
    /** 刚发出去的那张图的气泡行；SENDINGIMG 事件回来后按它把消息 id 绑上去 */
    /**
     * 等着 SENDINGIMG 事件回来绑 id 的图片气泡行，按发送顺序排。
     *
     * 必须是队列而不能是单个字段：图片气泡建出来时还没有 id，id 由 native 在
     * sendLanImage 里同步抛出的 SENDINGIMG 事件带回来，而轮询线程最迟 50ms 才取到它。
     * 这期间再发一张，单个字段就会被覆盖 —— 第一条永远绑不上，第二条绑的是第一条的 id，
     * 两张图一起卡在"发送中"。图文交替连发时这个窗口必中。
     */
    private final java.util.ArrayDeque<View> mPendingImageRows = new java.util.ArrayDeque<>();

    public LanMsgDialog(@NonNull Context context) {
        super(context);
        mActivity = context instanceof android.app.Activity
                ? (android.app.Activity) context : null;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_lan_msg);
        bindViews();
        setupPeers();
    }

    @SuppressLint("SetTextI18n")
    private void bindViews() {
        etName = findViewById(R.id.et_lan_name);
        etPort = findViewById(R.id.et_lan_port);
        etDiscoveryAddr = findViewById(R.id.et_lan_discovery_addr);
        etDiscoveryPort = findViewById(R.id.et_lan_discovery_port);
        btnStart = findViewById(R.id.btn_lan_start);
        btnRefresh = findViewById(R.id.btn_lan_refresh);
        vDot = findViewById(R.id.v_lan_dot);
        tvState = findViewById(R.id.tv_lan_state);
        lvPeers = findViewById(R.id.lv_lan_peers);
        svChat = findViewById(R.id.sv_lan_chat);
        llChat = findViewById(R.id.ll_lan_chat);
        tvChatHint = findViewById(R.id.tv_lan_chat_hint);
        btnClear = findViewById(R.id.btn_lan_clear);
        etMsg = findViewById(R.id.et_lan_msg);
        btnSend = findViewById(R.id.btn_lan_send);
        btnImage = findViewById(R.id.btn_lan_image);
        if (btnImage != null) {
            btnImage.setOnClickListener(v -> openImagePicker());
        }

        String selfIp = Utils.getLocalWifiIp(getContext());
        // 默认用设备型号当名字：局域网里比清一色的 "Android" 好认
        if (etName != null) {
            String model = android.os.Build.MODEL;
            etName.setText(TextUtils.isEmpty(model)
                    ? getContext().getString(R.string.lan_msg_default_name) : model);
        }
        // 状态行是单行的，字段间用分隔符隔开：一换行对话框高度就跟着跳
        setState(false, getContext().getString(R.string.lan_msg_idle)
                + " · 本机 IP " + selfIp);

        if (btnStart != null) {
            btnStart.setOnClickListener(v -> toggleStart());
        }
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                if (!mStarted) return;
                NetworkWrapper.refreshLanPeers();
                appendChat("已广播一轮发现请求…", KIND_SYS);
            });
        }
        if (btnSend != null) {
            btnSend.setOnClickListener(v -> sendMessage());
        }
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> clearChat());
        }
        View close = findViewById(R.id.btn_lan_close);
        if (close != null) {
            close.setOnClickListener(v -> dismiss());
        }
        updateSendEnabled();
    }

    private void setupPeers() {
        if (lvPeers == null) return;
        mPeerAdapter = new PeerAdapter();
        lvPeers.setAdapter(mPeerAdapter);
        lvPeers.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        lvPeers.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Peer picked = peerAt(position);
                if (picked == null) return;
                selectTarget(picked, position);
            }
        });
        refreshPeerLabels();
    }

    /** 选中一台设备作为发送目标：列表高亮 + 状态行显示"发给谁" */
    @SuppressLint("SetTextI18n")
    private void selectTarget(Peer picked, int position) {
        mTarget = picked;
        if (lvPeers != null) {
            lvPeers.setItemChecked(position, true);
        }
        if (tvState != null) {
            tvState.setText("发给 " + picked.name + "  " + picked.ip + ":" + picked.port);
        }
        updateSendEnabled();
    }

    private Peer peerAt(int position) {
        if (position < 0) return null;
        int i = 0;
        for (Peer p : mPeers.values()) {
            if (i++ == position) return p;
        }
        return null;
    }

    /** 返回 peer 在列表里的位置，找不到返回 -1 */
    private int positionOf(Peer peer) {
        if (peer == null) return -1;
        int i = 0;
        for (Peer p : mPeers.values()) {
            if (p.key().equals(peer.key())) return i;
            i++;
        }
        return -1;
    }

    private void refreshPeerLabels() {
        if (mPeerAdapter == null) return;
        mPeerAdapter.notifyDataSetChanged();
        updatePeersHeight();
    }

    /**
     * 让设备列表的高度跟着设备数走：一台就一行，两台两行，超过上限就在固定高度里滚。
     *
     * 高度 = 行数 × 34dp（item_lan_peer 的行高）+ 分隔线 + 面板内边距。
     * 面板背景 bg_lan_panel 自带 4dp padding，上下共 8dp 必须算进去，
     * 否则最后一行将被截去一半。
     */
    private void updatePeersHeight() {
        if (lvPeers == null) return;
        ViewGroup.LayoutParams lp = lvPeers.getLayoutParams();
        if (lp == null) return;
        // 一台都没有时也要给一行，放"未发现设备"的占位
        int rows = mPeers.isEmpty() ? 1 : Math.min(mPeers.size(), MAX_PEER_ROWS);
        float density = getContext().getResources().getDisplayMetrics().density;
        int rowH = Math.round(PEER_ROW_DP * density);
        int divider = Math.round(1f * density);
        int panelPadding = Math.round(8f * density);
        lp.height = rows * rowH + (rows - 1) * divider + panelPadding;
        lvPeers.setLayoutParams(lp);
    }

    /**
     * 设备列表适配器：设备名（粗）在左，IP:端口（次级灰）靠右。
     *
     * 不用 ArrayAdapter + simple_list_item_activated_1：那个布局一行 48dp 往上，
     * 选中态还是主题那个灰蓝底，跟卡片的青绿主色不搭。现在行高 34dp，选中态由
     * bg_lan_peer_row 的 state_activated 负责 —— ListView.setItemChecked() 会把
     * 激活态设于行上，ViewGroup 再将其传递给子控件，文字色因而随之变化。
     */
    private final class PeerAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            // 一台都没有时也要占一行，空白列表会被误认为列表异常
            return mPeers.isEmpty() ? 1 : mPeers.size();
        }

        @Override
        public Object getItem(int position) {
            return peerAt(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.item_lan_peer, parent, false);
            TextView tvName = row.findViewById(R.id.tv_peer_name);
            TextView tvAddr = row.findViewById(R.id.tv_peer_addr);
            Peer peer = peerAt(position);
            if (peer == null) {
                // 占位行：次级灰、不加粗
                if (tvName != null) {
                    tvName.setText(R.string.lan_msg_peer_none);
                    tvName.setTextColor(resolveColor(R.color.lan_text_dim));
                    tvName.setTypeface(Typeface.DEFAULT);
                }
                if (tvAddr != null) {
                    tvAddr.setText("");
                }
                // 占位行不该被当成可选中的设备
                row.setEnabled(false);
            } else {
                if (tvName != null) {
                    tvName.setText(peer.name);
                    // 传 ColorStateList 而不是单一颜色：setTextColor(int) 会把
                    // XML 里那个随 activated 变色的选择器冲掉
                    ColorStateList csl = ContextCompat.getColorStateList(getContext(),
                            R.color.sel_lan_peer_text);
                    if (csl != null) {
                        tvName.setTextColor(csl);
                    }
                    tvName.setTypeface(Typeface.DEFAULT_BOLD);
                }
                if (tvAddr != null) {
                    tvAddr.setText(peer.ip + ":" + peer.port);
                }
                row.setEnabled(true);
            }
            return row;
        }
    }

    /** 状态行 + 圆点：跑起来是青绿，停着是灰 */
    private void setState(boolean online, CharSequence text) {
        if (tvState != null) {
            tvState.setText(text);
            tvState.setTextColor(resolveColor(online ? R.color.lan_online : R.color.lan_idle));
        }
        if (vDot != null) {
            // 圆点底色是 bg_lan_dot 这个 selector，靠 activated 在青绿 / 灰之间切
            vDot.setActivated(online);
        }
    }

    private int resolveColor(int resId) {
        try {
            return getContext().getResources().getColor(resId);
        } catch (Exception e) {
            return Color.GRAY;
        }
    }

    // ---------- 启停 ----------

    private void toggleStart() {
        if (mStarted) {
            stopMessenger();
            return;
        }
        startMessenger();
    }

    /**
     * 启动收发。
     *
     * 除了「启动」按钮，退到后台被超时停掉、回到前台时也会走这里，所以是 public。
     */
    @SuppressLint("SetTextI18n")
    public void startMessenger() {
        if (mStarted) return;
        if (mStopping) return;   // 上一轮的 native 收尾还没走完，这时重启会和旧线程抢同一个端口
        int port = readPort();
        if (port <= 0) {
            Toast.makeText(getContext(), R.string.lan_msg_bad_port, Toast.LENGTH_SHORT).show();
            return;
        }
        mPort = port;
        String name = etName != null && etName.getText() != null
                ? etName.getText().toString().trim() : "";
        if (name.isEmpty()) {
            name = getContext().getString(R.string.lan_msg_default_name);
        }
        // 发现在 start 之前设到单例上（start 里才会真正 open 发现 socket）：
        // 地址空 = 走广播；填 224.224.224.224 等组播/单播地址则额外发一份并加入组播组
        String discAddr = etDiscoveryAddr != null && etDiscoveryAddr.getText() != null
                ? etDiscoveryAddr.getText().toString().trim() : "";
        int discPort = readDiscoveryPort();
        if (discPort > 0) {
            mDiscoveryPort = discPort;
            NetworkWrapper.setLanDiscoveryPort(discPort);
        }
        if (!discAddr.isEmpty()) {
            NetworkWrapper.setLanDiscoveryAddress(discAddr);
        }
        acquireMulticastLock();
        int ret = NetworkWrapper.startLanMessenger(name, Utils.getLocalWifiIp(getContext()), port);
        if (ret < 0) {
            appendChat("启动失败(" + ret + ")：端口可能被占用", KIND_ERROR);
            releaseMulticastLock();
            return;
        }
        mStarted = true;
        mAutoStopped = false;
        mTarget = null;
        if (btnStart != null) {
            btnStart.setText(R.string.lan_msg_stop);
        }
        if (btnRefresh != null) {
            btnRefresh.setEnabled(true);
        }
        if (etPort != null) {
            etPort.setEnabled(false);
        }
        if (etDiscoveryAddr != null) etDiscoveryAddr.setEnabled(false);
        if (etDiscoveryPort != null) etDiscoveryPort.setEnabled(false);
        if (etName != null) {
            // 名称于启动时传递给对端，中途修改不会同步，因此启动后即锁定，以免误认为已生效
            etName.setEnabled(false);
        }
        setState(true, getContext().getString(R.string.lan_msg_listening,
                Utils.getLocalWifiIp(getContext()), port, name));
        updateSendEnabled();
        appendChat("已开始收发，点「发现设备」查找同网段机器", KIND_SYS);
    }

    /**
     * 停止收发。
     *
     * native 侧的收尾要 join 发现线程和 KCP 线程、再关 socket 释放端口，是有耗时的；
     * 放在主线程上做，点一下"停止"按钮就会明显顿一下才回弹。
     * 所以这里 UI 立刻切回"已停止"的状态，收尾丢到后台线程去做，做完再把启动按钮放出来。
     */
    /** 用户点「停止」或退出页面时走这里：不会被自动重新拉起来 */
    public void stopMessenger() {
        stopMessenger(false);
    }

    /**
     * @param autoStopped true 表示这次是"退到后台超时"自动停的，回到前台会自动恢复；
     *                    用户自己点「停止」、退出页面都是 false，不该被重新拉起来。
     */
    public void stopMessenger(boolean autoStopped) {
        if (mStopping) return;
        mStopping = true;
        mStarted = false;
        mAutoStopped = autoStopped;
        mTarget = null;
        mPeers.clear();
        refreshPeerLabels();
        releaseMulticastLock();
        if (btnStart != null) {
            btnStart.setText(R.string.lan_msg_start);
            // native 还没收完就再启动时，新旧两轮订阅会抢同一个端口
            btnStart.setEnabled(false);
        }
        if (btnRefresh != null) {
            btnRefresh.setEnabled(false);
        }
        if (etPort != null) {
            etPort.setEnabled(true);
        }
        if (etDiscoveryAddr != null) etDiscoveryAddr.setEnabled(true);
        if (etDiscoveryPort != null) etDiscoveryPort.setEnabled(true);
        if (etName != null) {
            etName.setEnabled(true);
        }
        setState(false, getContext().getString(R.string.lan_msg_stopping));
        updateSendEnabled();

        new Thread(() -> {
            NetworkWrapper.stopLanMessenger();
            new Handler(Looper.getMainLooper()).post(() -> {
                mStopping = false;
                if (btnStart != null) {
                    btnStart.setEnabled(true);
                }
                if (isShowing()) {
                    setState(false, getContext().getString(R.string.lan_msg_stopped));
                }
            });
        }, "lan-msg-stop").start();
    }

    private int readDiscoveryPort() {
        if (etDiscoveryPort == null || etDiscoveryPort.getText() == null) return -1;
        try {
            int port = Integer.parseInt(etDiscoveryPort.getText().toString().trim());
            return (port > 0 && port <= 65535) ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private int readPort() {
        if (etPort == null || etPort.getText() == null) return -1;
        try {
            int port = Integer.parseInt(etPort.getText().toString().trim());
            return (port > 1024 && port <= 65535) ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wm = (WifiManager) getContext().getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            if (mMulticastLock == null) {
                mMulticastLock = wm.createMulticastLock("device2device:lan-msg");
                mMulticastLock.setReferenceCounted(false);
            }
            mMulticastLock.acquire();
        } catch (Exception ignored) {
            // 拿不到锁最多是部分 ROM 上收不到广播，不影响发送
        }
    }

    private void releaseMulticastLock() {
        try {
            if (mMulticastLock != null && mMulticastLock.isHeld()) {
                mMulticastLock.release();
            }
        } catch (Exception ignored) { }
    }

    // ---------- 发送 ----------

    @SuppressLint("SetTextI18n")
    private void sendMessage() {
        if (!mStarted) {
            Toast.makeText(getContext(), R.string.lan_msg_idle, Toast.LENGTH_SHORT).show();
            return;
        }
        if (mTarget == null) {
            Toast.makeText(getContext(), R.string.lan_msg_no_peer, Toast.LENGTH_SHORT).show();
            return;
        }
        String text = etMsg != null && etMsg.getText() != null
                ? etMsg.getText().toString().trim() : "";
        if (text.isEmpty()) {
            Toast.makeText(getContext(), R.string.lan_msg_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        // 真正的发送结果（SENT / SENDFAIL）由 LAN_MESSAGE 事件回传，这里只负责派发
        int ret = NetworkWrapper.sendLanMessage(mTarget.ip, mTarget.port, text);
        if (ret < 0) {
            appendChat("发送失败(" + ret + ") → " + mTarget.ip, KIND_ERROR);
            return;
        }
        if (etMsg != null) {
            etMsg.setText("");
        }
    }

    private void updateSendEnabled() {
        if (btnSend != null) {
            btnSend.setEnabled(mStarted && mTarget != null);
        }
        if (btnImage != null) {
            btnImage.setEnabled(mStarted && mTarget != null);
        }
    }

    // ---------- 图片 ----------

    private void openImagePicker() {
        // 拿不到 Activity 或已经收摊了就别发起跳转，回来也没人接
        if (mActivity == null || mActivity.isFinishing() || mActivity.isDestroyed()) return;
        Intent intent = new Intent(Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        mActivity.startActivityForResult(Intent.createChooser(intent,
                getContext().getString(R.string.lan_msg_pick_image)), LAN_IMAGE_REQUEST);
    }

    /**
     * 由 SelectActivity.onActivityResult 转发进来。
     *
     * 编码在调用方线程上跑，可能上百毫秒，所以先派到后台再回来发；否则点完图库回来
     * 界面会卡住一下。
     */
    public void handleImageResult(Uri uri) {
        if (uri == null) return;
        if (!mStarted || mTarget == null) {
            Toast.makeText(getContext(), R.string.lan_msg_no_peer, Toast.LENGTH_SHORT).show();
            return;
        }
        final Peer target = mTarget;
        appendChat(getContext().getString(R.string.lan_msg_encoding), KIND_SYS);
        new Thread(() -> {
            Bitmap bmp = decodeForSend(uri);
            if (bmp == null) {
                reportImageFailure(R.string.lan_msg_image_failed);
                return;
            }
            final String base64 = encodeToBase64(bmp);
            if (base64 == null) {
                bmp.recycle();
                reportImageFailure(R.string.lan_msg_image_too_large);
                return;
            }
            // 缩到显示尺寸要在编码之后：编码那一步不回收传入的 bitmap，但这里缩完会回收原件
            final Bitmap show = downscaleForDisplay(bmp);
            new Handler(Looper.getMainLooper()).post(() -> {
                // 气泡先绘制（bitmap 仅此处持有），id 由随后的 SENDINGIMG 事件绑定
                View row = appendImage(show, KIND_SELF, null,
                        getContext().getString(R.string.lan_msg_sending));
                // 建失败就别入队：native 也不会为这次发送抛 SENDINGIMG，队列会错位
                if (row != null) {
                    mPendingImageRows.add(row);
                }
                int ret = NetworkWrapper.sendLanImage(target.ip, target.port, base64);
                if (ret < 0) {
                    // 这三个错误码都在 native 入队之前就返回了，不会有 SENDINGIMG 回来，
                    // 所以这里直接把队首那条划掉
                    View failed = mPendingImageRows.poll();
                    if (failed != null) {
                        updateBubbleStatus(failed,
                                getContext().getString(R.string.lan_msg_undelivered), true);
                    }
                    appendChat(getContext().getString(R.string.lan_msg_image_too_large),
                            KIND_ERROR);
                }
            });
        }).start();
    }

    private void reportImageFailure(int messageRes) {
        new Handler(Looper.getMainLooper()).post(
                () -> appendChat(getContext().getString(messageRes), KIND_ERROR));
    }

    /** 读图并按最长边缩到 maxSide，避免把原图（动辄几 MB）直接塞进报文 */
    private Bitmap decodeForSend(Uri uri) {
        return decode(uri, IMAGE_SEND_MAX_PX, true);
    }

    /** 显示用再缩一档：气泡只有屏幕一半宽，按原尺寸留着只是白占内存 */
    private Bitmap downscaleForDisplay(Bitmap src) {
        int max = Math.max(src.getWidth(), src.getHeight());
        if (max <= IMAGE_SHOW_MAX_PX) return src;
        float scale = (float) IMAGE_SHOW_MAX_PX / max;
        Bitmap out = Bitmap.createScaledBitmap(src,
                Math.round(src.getWidth() * scale), Math.round(src.getHeight() * scale), true);
        if (out != src) src.recycle();
        return out;
    }

    private Bitmap decode(Uri uri, int maxSide, boolean exact) {
        android.content.ContentResolver cr = getContext().getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream probe = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(probe, null, bounds);
        } catch (Exception e) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while (max / sample > maxSide) {
            sample *= 2;
        }
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        opt.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream in = cr.openInputStream(uri)) {
            Bitmap bmp = BitmapFactory.decodeStream(in, null, opt);
            if (bmp == null) return null;
            // inSampleSize 只能取 2 的幂，缩完还是偏大就再精确缩一次
            int now = Math.max(bmp.getWidth(), bmp.getHeight());
            if (exact && now > maxSide) {
                float scale = (float) maxSide / now;
                Bitmap out = Bitmap.createScaledBitmap(bmp,
                        Math.round(bmp.getWidth() * scale),
                        Math.round(bmp.getHeight() * scale), true);
                bmp.recycle();
                return out;
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * JPEG → base64，压到上限以内为止。
     *
     * 直接按原质量编码，一张 1024 的图常常超出 native 侧的上限（LAN_MAX_IMAGE_CHARS，
     * base64 又是按字符算的），所以逐档降质量 / 降尺寸重压。
     *
     * 传入的 bitmap 归调用方所有，这里只回收自己缩出来的那份：一开始图大于 1024 就得
     * 降一档再压，那时候回收的其实是调用方传进来的原件，外部若再以其生成显示缩略图则会
     * "trying to use a recycled bitmap"，直接崩。
     */
    private String encodeToBase64(Bitmap bmp) {
        Bitmap src = bmp;
        Bitmap owned = null;
        try {
            for (int i = 0; i < IMAGE_QUALITY_STEPS.length; i += 2) {
                int maxSide = IMAGE_QUALITY_STEPS[i];
                int quality = IMAGE_QUALITY_STEPS[i + 1];
                int now = Math.max(src.getWidth(), src.getHeight());
                if (now > maxSide) {
                    float scale = (float) maxSide / now;
                    Bitmap scaled = Bitmap.createScaledBitmap(src,
                            Math.round(src.getWidth() * scale),
                            Math.round(src.getHeight() * scale), true);
                    if (owned != null) {
                        owned.recycle();
                    }
                    owned = scaled;
                    src = scaled;
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                if (!src.compress(Bitmap.CompressFormat.JPEG, quality, buf)) return null;
                String base64 = Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP);
                if (base64.length() <= IMAGE_MAX_CHARS) {
                    return base64;
                }
                if (i + 2 >= IMAGE_QUALITY_STEPS.length) {
                    return null;
                }
            }
            return null;
        } finally {
            if (owned != null) {
                owned.recycle();
            }
        }
    }

    /** 来消息的这台还没在列表里（它可能没广播）就补上，方便直接回一句 */
    private void ensurePeer(String ip, String name) {
        Peer from = mPeers.get(ip + ":" + mPort);
        if (from == null) {
            from = new Peer(ip, mPort, name);
            mPeers.put(from.key(), from);
            refreshPeerLabels();
        } else {
            from.name = name;
        }
        if (mTarget == null) {
            selectTarget(from, positionOf(from));
        }
    }

    /** 接收到的 base64 → Bitmap，按显示尺寸降采样，别按原图占内存 */
    private Bitmap decodeReceived(String base64) {
        byte[] raw;
        try {
            raw = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while (max / sample > IMAGE_SHOW_MAX_PX) {
            sample *= 2;
        }
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        opt.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, opt);
    }

    // ---------- native 事件 ----------

    /**
     * 对端表变化：CLEAR 清空、ADD|ip|port|name 加入或改名、DEL|ip|port|name 下线。
     */
    @SuppressLint("SetTextI18n")
    public void onPeerEvent(String event) {
        if (event == null || event.isEmpty()) return;
        String[] p = event.split("\\|");
        if (p.length == 0) return;
        if (p[0].equals("CLEAR")) {
            /**
             * 只清列表，不清选中目标：CLEAR 后面紧跟着一轮重新 ADD，对端还在时马上就回来，
             * 连 mTarget 一起清时每点一次「发现设备」就要重选一次目标，像是连接断开。
             * 真离线的设备走 DEL 才清。
             */
            mPeers.clear();
            refreshPeerLabels();
            return;
        }
        if (p.length < 4) return;
        String ip = p[1];
        int port;
        try {
            port = Integer.parseInt(p[2]);
        } catch (NumberFormatException e) {
            return;
        }
        String name = p[3];
        Peer peer = new Peer(ip, port, name);
        if (p[0].equals("ADD")) {
            boolean isNew = !mPeers.containsKey(peer.key());
            mPeers.put(peer.key(), peer);
            refreshPeerLabels();
            if (mTarget != null && mTarget.key().equals(peer.key())) {
                mTarget = peer;
                // 列表刚被 CLEAR 重建过，高亮要跟着重新打上，否则选中项看着是空的
                int at = positionOf(peer);
                if (lvPeers != null && at >= 0) {
                    lvPeers.setItemChecked(at, true);
                }
            } else if (mTarget == null && isNew && mPeers.size() == 1) {
                // 第一台被发现的设备自动选为目标：只有一台时不该还要用户额外点一下
                selectTarget(peer, 0);
            }
        } else if (p[0].equals("DEL")) {
            if (mTarget != null && mTarget.key().equals(peer.key())) {
                mTarget = null;
                updateSendEnabled();
            }
            mPeers.remove(peer.key());
            refreshPeerLabels();
            appendChat("设备离线：" + name + " " + ip, KIND_SYS);
        }
    }

    /** 收发内容与启动结果：LISTEN / RECV / SENT / SENDFAIL */
    @SuppressLint("SetTextI18n")
    public void onMessageEvent(String event) {
        if (event == null || event.isEmpty()) return;
        String[] p = event.split("\\|");
        if (p.length == 0) return;
        switch (p[0]) {
            case "LISTEN":
                // p = [LISTEN, ip, port, name]
                if (p.length >= 4) {
                    int port = 0;
                    try {
                        port = Integer.parseInt(p[2]);
                    } catch (NumberFormatException e) {
                        port = 0;
                    }
                    setState(true, getContext().getString(R.string.lan_msg_listening,
                            p[1], port, p[3]));
                    appendChat("本机已开始监听 " + p[1] + ":" + p[2], KIND_SYS);
                }
                break;
            case "RECVIMG":
                // p = [RECVIMG, peerIp, name, base64]
                if (p.length >= 4) {
                    Bitmap photo = decodeReceived(p[3]);
                    if (photo != null) {
                        appendImage(photo, KIND_PEER, null, p[2]);
                    } else {
                        appendChat(p[2] + " 发来一张图片（解码失败）", KIND_ERROR);
                    }
                    ensurePeer(p[1], p[2]);
                }
                break;
            case "RECV":
                // p = [RECV, peerIp, name, text]
                if (p.length >= 4) {
                    appendChat(p[2] + "：" + p[3], KIND_PEER);
                    ensurePeer(p[1], p[2]);
                }
                break;
            case "SENDING":
                // p = [SENDING, ip, id, body]
                if (p.length >= 4) {
                    appendChat("我 → " + p[1] + "：" + p[3], KIND_SELF, p[2],
                            getContext().getString(R.string.lan_msg_sending));
                }
                break;
            case "SENDINGIMG":
                // p = [SENDINGIMG, ip, id]：图片本体不随事件走，把 id 绑到最早那条没绑上的图片行上
                if (p.length >= 3) {
                    View row = mPendingImageRows.poll();
                    if (row != null) {
                        row.setTag(p[2]);
                    }
                }
                break;
            case "DELIVERED":
                // p = [DELIVERED, ip, id]：对端回了确认
                if (p.length >= 3) {
                    updateBubbleStatus(p[2], getContext().getString(R.string.lan_msg_delivered),
                            false);
                }
                break;
            case "UNDELIVERED":
                // p = [UNDELIVERED, ip, id, reason]：重试几次都没等到确认
                if (p.length >= 3) {
                    updateBubbleStatus(p[2], getContext().getString(R.string.lan_msg_undelivered),
                            true);
                }
                break;
            case "SENDFAIL":
                // p = [SENDFAIL, ip, id, reason]：连不上对端
                if (p.length >= 3) {
                    if (!updateBubbleStatus(p[2], getContext().getString(R.string.lan_msg_undelivered),
                            true)) {
                        appendChat("发送失败 → " + p[1] + (p.length >= 4 ? "：" + p[3] : ""),
                                KIND_ERROR);
                    }
                }
                break;
            default:
                appendChat(event, KIND_SYS);
                break;
        }
    }

    // ---------- 消息区 ----------

    private void appendChat(String line) {
        appendChat(line, KIND_SYS);
    }

    private void appendChat(String line, int kind) {
        appendRow(line, null, kind, null, null);
    }

    private void appendChat(String line, int kind, String tagId, String status) {
        appendRow(line, null, kind, tagId, status);
    }

    /** 图片消息：正文位置放一张图，时间戳和送达状态跟文本消息一样 */
    private View appendImage(Bitmap image, int kind, String tagId, String status) {
        return appendRow(null, image, kind, tagId, status);
    }

    /**
     * 追加一条气泡消息。
     *
     * 每条一个 item_lan_bubble：所有行拼进一个 TextView 再靠 ForegroundColorSpan 上色时，
     * 一屏下来分不清谁说的，也没法单独选中某一条去复制。
     * 自己发的靠右淡蓝、对端来的靠左淡青、报错居中淡红、系统提示居中不要气泡底。
     * 气泡里的 TextView 开了 textIsSelectable，长按即可选中复制。
     *
     * @param image  非 null 时这条是图片，正文 TextView 让位给 ImageView
     * @param tagId  消息 id，用来回找到这一行去改送达状态；null 表示不需要
     * @param status 时间戳后面跟的那截状态（发送中 / 已送达 / 未送达）
     * @return 这一行的 root View，null 表示没加进去
     */
    private View appendRow(String line, Bitmap image, int kind, String tagId, String status) {
        if (llChat == null) return null;
        if ((line == null || line.isEmpty()) && image == null) return null;
        View row = LayoutInflater.from(getContext())
                .inflate(R.layout.item_lan_bubble, llChat, false);
        TextView bubble = row.findViewById(R.id.bubble_text);
        ImageView photo = row.findViewById(R.id.bubble_image);
        TextView time = row.findViewById(R.id.bubble_time);

        int gravity = Gravity.START;
        if (kind == KIND_SELF) {
            gravity = Gravity.END;
        } else if (kind == KIND_SYS || kind == KIND_ERROR) {
            gravity = Gravity.CENTER_HORIZONTAL;
        }

        if (image != null && photo != null) {
            photo.setImageBitmap(image);
            photo.setVisibility(View.VISIBLE);
            applyLayoutGravity(photo, gravity);
            if (bubble != null) {
                bubble.setVisibility(View.GONE);
            }
        }
        if (bubble != null) {
            bubble.setText(line);
            if (kind == KIND_ERROR) {
                bubble.setBackgroundResource(R.drawable.bg_lan_bubble_error);
                bubble.setActivated(false);
            } else if (kind == KIND_SYS) {
                bubble.setBackgroundResource(0);   // 系统提示不要气泡底
                bubble.setActivated(false);
            } else {
                // 自己发的 / 对端来的共用 bg_lan_bubble_chat，靠 activated 切淡蓝 / 淡青
                bubble.setBackgroundResource(R.drawable.bg_lan_bubble_chat);
                bubble.setActivated(kind == KIND_SELF);
            }
            bubble.setTextColor(resolveColor(textColorOf(kind)));
            if (kind == KIND_SYS) {
                // 系统提示是辅助信息（启动/停止/离线），比聊天气泡小一号，别抢正文的注意力
                bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, SYS_TEXT_SP);
            }
        }
        if (time != null) {
            // 基准时间存在 tag 里，改送达状态时不用重新取一次"现在"
            String stamp = mTimeFormat.format(new Date());
            time.setTag(stamp);
            time.setText(status == null ? stamp : stamp + " · " + status);
        }
        if (tagId != null) {
            row.setTag(tagId);
        }
        // XML 里 layout_gravity 默认是 start，自己发的那条要放在右边
        applyLayoutGravity(bubble, gravity);
        applyLayoutGravity(time, gravity);

        if (tvChatHint != null) {
            tvChatHint.setVisibility(View.GONE);
        }
        llChat.addView(row);
        // 超出上限时从最旧的消息开始删除，避免长对话导致 View 持续堆积
        while (llChat.getChildCount() > MAX_CHAT_LINES) {
            llChat.removeViewAt(0);
        }
        scrollChatToBottom();
        updateClearEnabled();
        return row;
    }

    /**
     * 滚到最新一条，但不碰焦点：scrollTo() 只动滚动位置，不参与焦点查找。
     *
     * 气泡 TextView 开了 textIsSelectable（可聚焦），凡是会 requestFocus() 的滚法
     * （如 fullScroll）都会在每来一条消息、每有一次设备上下线时把焦点从输入框抢到气泡上
     * —— 正在打字时键盘自己收掉。
     */
    private void scrollChatToBottom() {
        if (svChat == null) return;
        svChat.post(() -> {
            View content = svChat.getChildAt(0);
            if (content != null) {
                svChat.scrollTo(0, content.getHeight());
            }
        });
    }

    /** 按消息 id 找到那一行的气泡；已经被"清空"或滚出上限就返回 null */
    private View findBubble(String id) {
        return (id == null || llChat == null) ? null : llChat.findViewWithTag(id);
    }

    /** 改一条已发出消息的送达状态；@return 是否找到了那一行 */
    private boolean updateBubbleStatus(String id, String status, boolean failed) {
        return updateBubbleStatus(findBubble(id), status, failed);
    }

    private boolean updateBubbleStatus(View row, String status, boolean failed) {
        if (row == null) {
            return false;
        }
        TextView time = row.findViewById(R.id.bubble_time);
        if (time == null) {
            return false;
        }
        Object base = time.getTag();
        String stamp = base instanceof String ? (String) base : mTimeFormat.format(new Date());
        time.setText(stamp + " · " + status);
        if (failed) {
            time.setTextColor(resolveColor(R.color.lan_error));
        }
        return true;
    }

    /**
     * 清空历史消息：删掉所有气泡，空态提示放回来。
     *
     * 只清本地显示，不碰 native 侧 —— 收发历史和连接没关系，清了照样能继续收发。
     */
    private void clearChat() {
        if (llChat == null || llChat.getChildCount() == 0) return;
        llChat.removeAllViews();
        if (tvChatHint != null) {
            tvChatHint.setVisibility(View.VISIBLE);
        }
        if (svChat != null) {
            svChat.post(() -> svChat.scrollTo(0, 0));
        }
        updateClearEnabled();
        Toast.makeText(getContext(), R.string.lan_msg_cleared, Toast.LENGTH_SHORT).show();
    }

    /** 没有消息时「清空」置灰，避免无效点击 */
    private void updateClearEnabled() {
        if (btnClear != null) {
            btnClear.setEnabled(llChat != null && llChat.getChildCount() > 0);
        }
    }

    /** 气泡文字色：气泡底都取浅色，所以正文统一深灰，只有报错用红、系统提示用灰 */
    private int textColorOf(int kind) {
        switch (kind) {
            case KIND_ERROR: return R.color.lan_error;
            case KIND_SYS:   return R.color.lan_text_dim;
            default:         return R.color.lan_text;
        }
    }

    private static void applyLayoutGravity(View child, int gravity) {
        if (child == null) return;
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).gravity = gravity;
            child.setLayoutParams(lp);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 对话框默认按内容收得很窄，消息列表和输入框会挤成一团：铺满宽度更好用。
        // 窗口背景设成透明，圆角卡片才不会被默认的方框底挡住四个角。
        Window w = getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    public boolean isMessengerStarted() {
        return mStarted;
    }

    /** 是不是被"退到后台超时"停掉的：若由后台超时停掉，回到前台需恢复 */
    public boolean isAutoStopped() {
        return mAutoStopped;
    }

    @Override
    public void dismiss() {
        /**
         * 关对话框只收起界面，不停监听：本机 KCP 端口继续开着，对端再发过来照样有人收。
         * 监听、发现广播和 KCP 线程继续跑，来消息时由 SelectActivity 把对话框弹回来。
         *
         * 真正收掉只剩两条路：用户点「停止」（toggleStart），或页面销毁
         * （SelectActivity.onDestroy 调 stopMessenger）。
         */
        super.dismiss();
    }
}
