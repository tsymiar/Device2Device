package com.tsymiar.device2device.utils;

import com.tsymiar.device2device.wrapper.NetworkWrapper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 分享浮窗的发现开关：起停放同一条串行队列，避免 stop 追上新浮窗的 start */
public final class ShareDiscovery {
    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor();

    private ShareDiscovery() {
    }

    public static void start() {
        QUEUE.execute(NetworkWrapper::startFileDiscovery);
    }

    public static void stop() {
        QUEUE.execute(NetworkWrapper::stopFileDiscovery);
    }

    public static boolean isRunning() {
        return NetworkWrapper.isFileDiscoveryRunning();
    }
}
