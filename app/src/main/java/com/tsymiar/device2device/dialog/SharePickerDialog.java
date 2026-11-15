package com.tsymiar.device2device.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.service.ShareSendService;
import com.tsymiar.device2device.utils.ShareDiscovery;
import com.tsymiar.device2device.utils.Utils;
import com.tsymiar.device2device.view.ShareDevicePanel;

import java.util.ArrayList;
import java.util.List;

/**
 * 没有浮窗权限时的兜底：透明 Activity 上弹一个设备选择框，只列发现到的设备。
 */
public class SharePickerDialog extends Dialog {
    private final List<String> filePaths;
    private final ShareDevicePanel panel;
    private final Runnable onClosed;
    private WifiManager.MulticastLock discoverLock;

    private SharePickerDialog(@NonNull Context context, @NonNull List<String> paths,
                              @Nullable Runnable onClosed) {
        super(context);
        this.filePaths = new ArrayList<>(paths);
        this.onClosed = onClosed;
        this.panel = new ShareDevicePanel(context, new ShareDevicePanel.Listener() {
            @Override
            public void onDeviceSelected(String ip, String port) {
                sendTo(ip, port);
            }

            @Override
            public void onCancel() {
                dismiss();
            }
        });
    }

    /** 复制分享的 URI 到缓存后弹框；无可用文件时只提示 */
    public static void show(@NonNull Activity activity, @NonNull List<Uri> uris) {
        show(activity, uris, null);
    }

    public static void show(@NonNull Activity activity, @NonNull List<Uri> uris,
                            @Nullable Runnable onClosed) {
        final Context app = activity.getApplicationContext();
        new Thread(() -> {
            final List<String> paths = new ArrayList<>();
            for (Uri uri : uris) {
                String path = Utils.copyUriToCache(app, uri);
                if (path != null) paths.add(path);
            }
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (paths.isEmpty()) {
                    Toast.makeText(app, R.string.file_msg_share_unreadable,
                            Toast.LENGTH_SHORT).show();
                    if (onClosed != null) onClosed.run();
                    return;
                }
                if (!ShareDiscovery.isRunning()) {
                    ShareDiscovery.start();
                }
                new SharePickerDialog(activity, paths, onClosed).show();
            });
        }, "share-copy").start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(panel.getView());
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        acquireLock();
        panel.start();
    }

    /** 选中设备：收起选择框，交给前台服务发送 */
    private void sendTo(String ip, String port) {
        dismiss();
        try {
            ShareSendService.start(getContext().getApplicationContext(), ip,
                    Integer.parseInt(port), filePaths);
        } catch (NumberFormatException ignored) {
        }
    }

    @Override
    public void dismiss() {
        panel.stop();
        if (discoverLock != null) {
            try {
                discoverLock.release();
            } catch (Exception ignored) {
            }
            discoverLock = null;
        }
        // 框一消失就收掉发现，避免监听线程随着反复分享累积
        ShareDiscovery.stop();
        super.dismiss();
        if (onClosed != null) {
            onClosed.run();
        }
    }

    /** 部分 ROM 不持 multicast 锁收不到 UDP 广播 */
    private void acquireLock() {
        try {
            WifiManager wm = (WifiManager) getContext().getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            discoverLock = wm.createMulticastLock("file-msg-share");
            discoverLock.setReferenceCounted(false);
            discoverLock.acquire();
        } catch (Exception ignored) {
        }
    }
}
