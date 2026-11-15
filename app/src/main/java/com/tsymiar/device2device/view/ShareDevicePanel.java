package com.tsymiar.device2device.view;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.wrapper.NetworkWrapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 浮窗与选择框共用的设备面板：标题 + 状态 + 列表，自己轮询发现快照 */
public final class ShareDevicePanel {
    public interface Listener {
        void onDeviceSelected(String ip, String port);

        void onCancel();
    }

    private final Context context;
    private final Listener listener;
    private final LinearLayout root;
    private final LinearLayout peersBox;
    private final TextView status;
    private final Map<String, String[]> peers = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            refresh(NetworkWrapper.getFileDiscoverPeers());
            handler.postDelayed(this, 1000);
        }
    };

    public ShareDevicePanel(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_lan_panel);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(context);
        title.setText(R.string.file_msg_share_pick);
        title.setTextSize(16);
        title.setTextColor(ContextCompat.getColor(context, R.color.ui_text));
        root.addView(title);

        status = new TextView(context);
        status.setText(R.string.file_msg_share_waiting);
        status.setTextSize(11);
        status.setTextColor(ContextCompat.getColor(context, R.color.ui_text_dim));
        status.setPadding(0, pad / 3, 0, pad / 3);
        root.addView(status);

        peersBox = new LinearLayout(context);
        peersBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(peersBox);
        // 高度跟内容走，避免一两台设备时留大片空白
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView cancel = new TextView(context);
        cancel.setText(android.R.string.cancel);
        cancel.setTextSize(13);
        cancel.setTextColor(ContextCompat.getColor(context, R.color.sel_lan_link_text));
        cancel.setPadding(0, pad / 2, 0, 0);
        cancel.setOnClickListener(v -> listener.onCancel());
        root.addView(cancel);
    }

    public ViewGroup getView() {
        return root;
    }

    public void start() {
        handler.post(poller);
    }

    public void stop() {
        handler.removeCallbacks(poller);
    }

    /** 发现快照 "ip:port;ip:port"，"NONE" 表示一台都没有 */
    private void refresh(String snapshot) {
        Map<String, String[]> next = new LinkedHashMap<>();
        if (snapshot != null && !"NONE".equals(snapshot)) {
            for (String item : snapshot.split(";")) {
                String entry = item.trim();
                if (entry.isEmpty()) continue;
                int idx = entry.lastIndexOf(':');
                if (idx <= 0 || idx == entry.length() - 1) continue;
                next.put(entry, new String[]{entry.substring(0, idx), entry.substring(idx + 1)});
            }
        }
        boolean changed = !next.keySet().equals(peers.keySet());
        peers.clear();
        peers.putAll(next);
        status.setText(peers.isEmpty()
                ? R.string.file_msg_share_waiting : R.string.file_msg_share_tap);
        if (changed) {
            render();
        }
    }

    private void render() {
        peersBox.removeAllViews();
        float density = context.getResources().getDisplayMetrics().density;
        for (Map.Entry<String, String[]> entry : peers.entrySet()) {
            final String ip = entry.getValue()[0];
            final String port = entry.getValue()[1];
            TextView row = new TextView(context);
            row.setText(entry.getKey());
            row.setTextSize(13);
            row.setTextColor(ContextCompat.getColor(context, R.color.ui_text));
            row.setPadding((int) (10 * density), (int) (10 * density),
                    (int) (10 * density), (int) (10 * density));
            row.setBackgroundResource(R.drawable.bg_lan_field);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (6 * density);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> listener.onDeviceSelected(ip, port));
            peersBox.addView(row);
        }
    }
}
