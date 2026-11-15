package com.tsymiar.device2device.view;

import android.content.Context;
import android.graphics.PixelFormat;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.service.ShareSendService;
import com.tsymiar.device2device.utils.ShareDiscovery;
import com.tsymiar.device2device.wrapper.NetworkWrapper;

import java.util.ArrayList;
import java.util.List;

/** 分享后的设备浮窗：盖在当前应用上列出发现到的设备，点一台就发（不打开应用界面） */
public final class ShareDeviceOverlay {
    private static final String TAG = "ShareDeviceOverlay";
    /** 没人点就自己收起 */
    private static final long AUTO_CLOSE_MS = 45 * 1000;

    private static ShareDeviceOverlay sInstance;

    private final Context context;
    private final List<String> paths;
    private final ShareDevicePanel panel;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiManager.MulticastLock discoverLock;
    private boolean closed;
    private boolean sending;

    /** 在主线程调用：文件已复制到缓存后弹出浮窗 */
    public static void show(Context context, List<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        if (sInstance != null) {
            sInstance.close();
        }
        sInstance = new ShareDeviceOverlay(context.getApplicationContext(), paths);
        sInstance.open();
    }

    private ShareDeviceOverlay(Context context, List<String> paths) {
        this.context = context;
        this.paths = new ArrayList<>(paths);
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (!ShareDiscovery.isRunning()) {
            ShareDiscovery.start();
        }
        this.panel = new ShareDevicePanel(context, new ShareDevicePanel.Listener() {
            @Override
            public void onDeviceSelected(String ip, String port) {
                sendTo(ip, port);
            }

            @Override
            public void onCancel() {
                close();
            }
        });
    }

    private void open() {
        acquireLock();
        panel.start();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.min((int) (300 * context.getResources().getDisplayMetrics().density),
                        context.getResources().getDisplayMetrics().widthPixels - 64),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        try {
            windowManager.addView(panel.getView(), lp);
        } catch (Exception e) {
            Log.w(TAG, "add overlay failed: " + e.getMessage());
            toast(context.getString(R.string.file_msg_overlay_permission));
            close();
            return;
        }
        handler.postDelayed(this::close, AUTO_CLOSE_MS);
    }

    private void close() {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        panel.stop();
        try {
            windowManager.removeView(panel.getView());
        } catch (Exception ignored) {
        }
        if (discoverLock != null) {
            try {
                discoverLock.release();
            } catch (Exception ignored) {
            }
            discoverLock = null;
        }
        // 浮窗消失即收掉发现，反复分享才不会堆监听线程
        ShareDiscovery.stop();
        if (sInstance == this) {
            sInstance = null;
        }
    }

    /** 选中设备：收起浮窗，交给前台服务发送（状态栏实时进度 + 取消/删除） */
    private void sendTo(String ip, String port) {
        if (sending) return;
        sending = true;
        final List<String> files = new ArrayList<>(paths);
        Log.i(TAG, "send " + files.size() + " file(s) to " + ip + ":" + port);
        close();
        int portNum;
        try {
            portNum = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return;
        }
        ShareSendService.start(context, ip, portNum, files);
    }

    private void toast(String text) {
        handler.post(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
    }

    /** 部分 ROM 不持 multicast 锁收不到 UDP 广播 */
    private void acquireLock() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            discoverLock = wm.createMulticastLock("file-msg-share");
            discoverLock.setReferenceCounted(false);
            discoverLock.acquire();
        } catch (Exception ignored) {
        }
    }
}
