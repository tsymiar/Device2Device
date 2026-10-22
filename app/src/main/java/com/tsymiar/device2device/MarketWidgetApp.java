package com.tsymiar.device2device;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * 行情小部件的进程级钩子：深浅模式切换时重画桌面上的小部件。
 *
 * 为什么需要它：小部件里的颜色（格子底色、文字色、曲线位图）都是「更新那一刻」写进
 * RemoteViews 的，桌面在配置变化时只会拿新配置重新 inflate 布局，那些写死的色值不会变 ——
 * 于是日间切夜间之后，整片马赛克 / 曲线停在旧配色上，要等下一次更新（半小时）才纠正。
 *
 * 为什么是动态注册：{@code ACTION_CONFIGURATION_CHANGED} 只投递给运行时注册的接收器，
 * manifest 里的 receiver 收不到（这是系统广播的规矩），所以在进程创建时注册一份。
 * 进程什么时候活着：桌面上挂着这类小部件时，启动器一直绑着 RemoteViewsService，
 * 进程不会退；就算当时不在，下一次小部件更新也会把颜色刷新成当前的。
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
