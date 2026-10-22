package com.tsymiar.device2device;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * 行情小部件的进程级钩子：深浅模式切换时重画桌面上的小部件。
 *
 * 小部件里的颜色（格子底色、文字色、曲线位图）是更新那一刻写进 RemoteViews 的，桌面换配置
 * 只会重新 inflate 布局，写死的色值不变 —— 不重画就停在旧配色上。而
 * {@code ACTION_CONFIGURATION_CHANGED} 只投递给运行时注册的接收器，所以在进程创建时注册一份。
 */
public class MarketWidgetApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                com.tsymiar.device2device.widget.MarketHeatWidgetProvider.repaintAll(context);
                com.tsymiar.device2device.widget.MarketChartWidgetProvider.repaintAll(context);
                com.tsymiar.device2device.widget.MarketWidgetProvider.repaintAll(context);
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
        // 系统广播，只本进程用得到。Android 13 起要求显式声明接收范围（RECEIVER_NOT_EXPORTED），
        // 但那两个符号要 compileSdk 33 才有，而本工程编译目标是 31、targetSdk 也是 31 ——
        // 该要求只对 targetSdk 33+ 的应用生效，所以这里先用不带 flag 的老签名。
        // 哪天 targetSdk 提到 33，记得补上 Context.RECEIVER_NOT_EXPORTED。
        registerReceiver(receiver, filter);
    }
}
