package com.tsymiar.device2device.utils;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/** 通知渠道创建的统一入口，避免每个 Service / Activity 各写一遍版本判断。 */
public final class NotificationHelper {

    private NotificationHelper() { }

    public static void ensureChannel(Context context, String channelId, CharSequence name,
                                     int importance, String description, boolean showBadge) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(channelId, name, importance);
        if (description != null) channel.setDescription(description);
        channel.setShowBadge(showBadge);
        nm.createNotificationChannel(channel);
    }
}
