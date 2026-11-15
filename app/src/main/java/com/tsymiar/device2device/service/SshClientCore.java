package com.tsymiar.device2device.service;

import android.content.Context;
import android.util.Log;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SSH 客户端核心（薄封装 Apache MINA SSHD client）。
 *
 * 依赖与服务端相同，故必须走 {@link SshServerCore#installAndroidHooks} 的 Android 兼容处理，
 * 否则 client 初始化会崩。
 *
 * 连接后打开持久交互 shell（非逐条 exec）：cd / export 状态可保留，且能发送控制键；
 * 输出由后台 pump 线程回调 {@link OutputListener}。
 */
public final class SshClientCore {
    private static final String TAG = "SshClientCore";
    private static final long CONNECT_TIMEOUT_MS = 10000;
    private static final long AUTH_TIMEOUT_MS = 10000;
    private static final long SHELL_TIMEOUT_MS = 10000;

    private SshClient client;
    private ClientSession session;
    private ChannelShell shellChannel;
    private volatile OutputStream shellIn;   // 写入对端
    private InputStream shellOut;   // 读取对端
    private Thread pumpThread;
    private volatile OutputListener outputListener;
    /**
     * 单线程写入器：write 是网络 I/O，留在主线程会抛 NetworkOnMainThreadException，
     * sshd 视作会话异常断开（表现为发一条命令后连接就断）。单线程同时保证命令顺序。
     */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ssh-client-writer");
        t.setDaemon(true);
        return t;
    });

    /** 输出回调：非主线程触发，调用方自行切回 UI */
    public interface OutputListener {
        void onOutput(String text);
    }

    public void setOutputListener(OutputListener l) {
        this.outputListener = l;
    }

    /** 连接 + 密码认证 + 打开交互 shell；阻塞网络操作，须在后台线程调用 */
    public void connect(Context context, String host, int port, String user, String password)
            throws Exception {
        SshServerCore.installAndroidHooks(context.getApplicationContext());
        SshClient sshClient = SshClient.setUpDefaultClient();
        // 不弹主机密钥确认
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();
        try {
            ClientSession s = sshClient.connect(user, host, port)
                    .verify(CONNECT_TIMEOUT_MS)
                    .getSession();
            s.addPasswordIdentity(password);
            s.auth().verify(AUTH_TIMEOUT_MS);

            // 请求 PTY：由服务端回显输入，app 不再本地回显
            ChannelShell shell = s.createShellChannel();
            shell.setPtyType("xterm");
            shell.setPtyColumns(80);
            shell.setPtyLines(24);
            shell.open().verify(SHELL_TIMEOUT_MS);

            client = sshClient;
            session = s;
            shellChannel = shell;
            shellIn = shell.getInvertedIn();
            shellOut = shell.getInvertedOut();
            startPump();
        } catch (Exception e) {
            try {
                sshClient.close();
            } catch (Exception ignored) {
                // 失败时关闭，避免 NIO 线程泄漏
            }
            throw e;
        }
    }

    public boolean isConnected() {
        return session != null && session.isOpen()
                && shellChannel != null && shellChannel.isOpen();
    }

    /** 发送文本（不含换行则不会执行） */
    public void send(String text) {
        final byte[] data = text.getBytes(StandardCharsets.UTF_8);
        writer.execute(() -> doSend(data));
    }

    /** 发送原始字节（Ctrl-C / Tab 等控制键） */
    public void send(byte[] raw) {
        final byte[] data = raw.clone();
        writer.execute(() -> doSend(data));
    }

    private void doSend(byte[] data) {
        OutputStream out = shellIn;
        if (out == null) {
            Log.w(TAG, "send dropped (shellIn null), bytes=" + data.length);
            return;
        }
        try {
            out.write(data);
            out.flush();
            Log.i(TAG, "send ok: " + new String(data, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            Log.w(TAG, "send failed: " + e.getMessage());
        }
    }

    private void startPump() {
        pumpThread = new Thread(() -> {
            byte[] buf = new byte[4096];
            int n;
            try {
                while (!Thread.currentThread().isInterrupted()
                        && (n = shellOut.read(buf)) != -1) {
                    final String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    OutputListener l = outputListener;
                    if (l != null) {
                        l.onOutput(chunk);
                    }
                }
                Log.i(TAG, "ssh client pump ended: EOF(-1) from server");
            } catch (IOException e) {
                Log.i(TAG, "ssh client pump ended: IOException " + e.getMessage());
            }
        }, "ssh-client-pump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    public void disconnect() {
        Thread t = pumpThread;
        if (t != null) {
            t.interrupt();
        }
        ChannelShell ch = shellChannel;
        shellChannel = null;
        shellIn = null;
        shellOut = null;
        if (ch != null) {
            try {
                ch.close();
            } catch (Exception ignored) {
            }
        }
        ClientSession s = session;
        session = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        SshClient c = client;
        client = null;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
        Log.i(TAG, "ssh client disconnected");
    }
}
