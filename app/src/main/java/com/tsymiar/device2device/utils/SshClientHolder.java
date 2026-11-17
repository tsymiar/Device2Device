package com.tsymiar.device2device.utils;

import com.tsymiar.device2device.service.SshClientCore;

/**
 * SSH 客户端连接的进程级持有者：连接不随 Activity 销毁而断开，
 * Activity 重建（旋转、从通知返回）时从此处恢复输出与状态。
 */
public final class SshClientHolder {
    private static final SshClientHolder INSTANCE = new SshClientHolder();

    private static final int MAX_CACHE = 200_000;
    private static final int KEEP_CACHE = 120_000;

    private final SshClientCore core = new SshClientCore();
    private final StringBuilder cache = new StringBuilder();
    private SshClientCore.OutputListener listener;
    private String sessionId = "";
    private String sessionName = "";

    private SshClientHolder() {
        // 输出先入缓存，再转给当前 UI 监听；后台期间的输出回到前台后也能看到
        core.setOutputListener(text -> {
            synchronized (this) {
                cache.append(text);
                if (cache.length() > MAX_CACHE) {
                    cache.delete(0, cache.length() - KEEP_CACHE);
                }
            }
            SshClientCore.OutputListener l = listener;
            if (l != null) {
                l.onOutput(text);
            }
        });
    }

    public static SshClientHolder get() {
        return INSTANCE;
    }

    public SshClientCore core() {
        return core;
    }

    public synchronized String cachedOutput() {
        return cache.toString();
    }

    public synchronized void clearOutput() {
        cache.setLength(0);
    }

    public synchronized void setListener(SshClientCore.OutputListener l) {
        listener = l;
    }

    public synchronized void setSession(String id, String name) {
        sessionId = id == null ? "" : id;
        sessionName = name == null ? "" : name;
    }

    public synchronized String getSessionId() {
        return sessionId;
    }

    public synchronized String getSessionName() {
        return sessionName;
    }
}
