package com.tsymiar.device2device.activity;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.dialog.SharePickerDialog;
import com.tsymiar.device2device.utils.Utils;
import com.tsymiar.device2device.view.ShareDeviceOverlay;

import java.util.ArrayList;
import java.util.List;

/** 分享入口（无界面）：只弹设备浮窗；没有浮窗权限时退回设备选择框 */
public class ShareOverlayActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        List<Uri> uris = collectUris(getIntent());
        if (uris.isEmpty()) {
            finish();
            return;
        }
        boolean canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
        if (!canOverlay) {
            // 没有浮窗权限：透明选择框兜底，关掉即结束本页
            SharePickerDialog.show(this, uris, this::finish);
            return;
        }
        final Context app = getApplicationContext();
        new Thread(() -> {
            final List<String> paths = new ArrayList<>();
            for (Uri uri : uris) {
                String path = Utils.copyUriToCache(app, uri);
                if (path != null) paths.add(path);
            }
            runOnUiThread(() -> {
                if (paths.isEmpty()) {
                    Toast.makeText(app, R.string.file_msg_share_unreadable,
                            Toast.LENGTH_SHORT).show();
                } else {
                    ShareDeviceOverlay.show(app, paths);
                }
                finish();
            });
        }, "share-copy").start();
    }

    private static List<Uri> collectUris(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        if (intent == null) return uris;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return uris;
        }
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }
        if (uris.isEmpty()) {
            Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (single != null) uris.add(single);
        }
        return uris;
    }
}
