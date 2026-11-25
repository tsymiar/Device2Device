package com.tsymiar.device2device.service;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.tsymiar.device2device.utils.SshKnownHostsStore;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.EnumSet;
import java.util.Set;
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
 *
 * 认证支持密码与私钥两种（与 JuiceSSH 的 password / identity 对应）；
 * 主机密钥采用首次信任 + 后续比对，不再无条件接受。
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

    private volatile String serverFingerprint = "";
    private volatile String expectedFingerprint = "";
    private volatile boolean hostKeyChanged;

    /** 单线程写入器：write 是网络 I/O，留在主线程会抛 NetworkOnMainThreadException；单线程同时保证命令顺序。 */
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

    /** 认证参数：密码或私钥二选一（都填则先私钥后密码） */
    public static final class Auth {
        final String password;
        final String keyPath;
        final String keyPassphrase;

        private Auth(String password, String keyPath, String keyPassphrase) {
            this.password = password;
            this.keyPath = keyPath;
            this.keyPassphrase = keyPassphrase;
        }

        public static Auth password(String password) {
            return new Auth(password, null, null);
        }

        public static Auth key(String keyPath, String passphrase, String fallbackPassword) {
            return new Auth(fallbackPassword, keyPath, passphrase);
        }
    }

    /** 主机密钥与上次记录不一致：极可能是中间人攻击或服务器重装 */
    public static final class HostKeyChangedException extends Exception {
        public final String expected;
        public final String actual;

        HostKeyChangedException(String expected, String actual) {
            super("服务器主机密钥已变更");
            this.expected = expected;
            this.actual = actual;
        }
    }

    /** 兼容旧调用：密码认证 */
    public void connect(Context context, String host, int port, String user, String password)
            throws Exception {
        connect(context, host, port, user, Auth.password(password));
    }

    /** 连接 + 认证 + 打开交互 shell；阻塞网络操作，须在后台线程调用 */
    public void connect(Context context, String host, int port, String user, Auth auth)
            throws Exception {
        SshServerCore.installAndroidHooks(context.getApplicationContext());
        SshClient sshClient = SshClient.setUpDefaultClient();

        serverFingerprint = "";
        expectedFingerprint = "";
        hostKeyChanged = false;
        // 首次连接记住指纹，之后不一致直接拒绝
        sshClient.setServerKeyVerifier((session, remote, serverKey) -> {
            String fp = SshKnownHostsStore.fingerprint(serverKey);
            serverFingerprint = fp;
            SshKnownHostsStore.Result r = SshKnownHostsStore.check(context, host, port, fp);
            if (r == SshKnownHostsStore.Result.MISMATCH) {
                expectedFingerprint = SshKnownHostsStore.stored(context, host, port);
                hostKeyChanged = true;
                Log.w(TAG, "host key mismatch for " + host + ":" + port
                        + " expected=" + expectedFingerprint + " actual=" + fp);
                return false;
            }
            return true;
        });
        sshClient.start();
        try {
            ClientSession s = sshClient.connect(user, host, port)
                    .verify(CONNECT_TIMEOUT_MS)
                    .getSession();

            if (!TextUtils.isEmpty(auth.keyPath)) {
                FileKeyPairProvider provider = new FileKeyPairProvider(Paths.get(auth.keyPath));
                if (!TextUtils.isEmpty(auth.keyPassphrase)) {
                    provider.setPasswordFinder(FilePasswordProvider.of(auth.keyPassphrase));
                }
                int count = 0;
                for (KeyPair kp : provider.loadKeys(s)) {
                    s.addPublicKeyIdentity(kp);
                    count++;
                }
                if (count == 0) {
                    throw new IOException("私钥解析失败：仅支持 PEM / PKCS8 / OpenSSH 格式");
                }
            }
            if (!TextUtils.isEmpty(auth.password)) {
                s.addPasswordIdentity(auth.password);
            }
            s.auth().verify(AUTH_TIMEOUT_MS);

            // 请求 PTY：由服务端回显输入，app 不再本地回显。宽一点减少长行折行。
            ChannelShell shell = s.createShellChannel();
            shell.setPtyType("xterm-256color");
            shell.setPtyColumns(120);
            shell.setPtyLines(40);
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
            if (hostKeyChanged) {
                throw new HostKeyChangedException(expectedFingerprint, serverFingerprint);
            }
            throw e;
        }
    }

    public boolean isConnected() {
        return session != null && session.isOpen()
                && shellChannel != null && shellChannel.isOpen();
    }

    /** 最近一次连接的主机密钥指纹 */
    public String getServerFingerprint() {
        return serverFingerprint;
    }

    /**
     * 在同一 session 上单独开 exec channel 执行命令（须后台线程调用）。
     * 不用 executeRemoteCommand()：它引用的 java.rmi.RemoteException 在 Android 上不存在。
     */
    public String execRemote(String command) throws Exception {
        ClientSession s = session;
        if (s == null || !s.isOpen()) {
            throw new IOException("not connected");
        }
        ChannelExec channel = null;
        try {
            channel = s.createExecChannel(command);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setOut(stdout);
            channel.setErr(stderr);
            if (!channel.open().await(10, TimeUnit.SECONDS)) {
                throw new java.net.SocketTimeoutException("open exec channel timeout: " + command);
            }
            Set<ClientChannelEvent> events = channel.waitFor(
                    EnumSet.of(ClientChannelEvent.CLOSED, ClientChannelEvent.EXIT_STATUS),
                    TimeUnit.SECONDS.toMillis(15));
            if (events != null && events.contains(ClientChannelEvent.TIMEOUT)) {
                throw new java.net.SocketTimeoutException("remote command timeout: " + command);
            }
            Integer status = channel.getExitStatus();
            if (status != null && status != 0) {
                String err = stderr.toString(StandardCharsets.UTF_8.name()).trim();
                throw new IOException("remote command failed (" + status + ")"
                        + (err.isEmpty() ? "" : ": " + err));
            }
            return stdout.toString(StandardCharsets.UTF_8.name());
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 发送文本（不含换行则不会执行） */
    public void send(String text) {
        final byte[] data = text.getBytes(StandardCharsets.UTF_8);
        writer.execute(() -> doSend(data));
    }

    /** 发送原始字节（Ctrl-C / Tab / 方向键等控制键） */
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
        } catch (IOException e) {
            Log.w(TAG, "send failed: " + e.getMessage());
        }
    }

    private void startPump() {
        pumpThread = new Thread(() -> {
            byte[] buf = new byte[4096];
            byte[] pending = new byte[0]; // 上次未解码完的不完整多字节序列
            // 增量解码：UTF-8 多字节字符可能被分包切断，逐包 new String 会乱码
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            int n;
            try {
                while (!Thread.currentThread().isInterrupted()
                        && (n = shellOut.read(buf)) != -1) {
                    byte[] data;
                    if (pending.length == 0) {
                        data = new byte[n];
                        System.arraycopy(buf, 0, data, 0, n);
                    } else {
                        data = new byte[pending.length + n];
                        System.arraycopy(pending, 0, data, 0, pending.length);
                        System.arraycopy(buf, 0, data, pending.length, n);
                    }
                    ByteBuffer in = ByteBuffer.wrap(data);
                    CharBuffer out = CharBuffer.allocate(data.length * 2 + 4);
                    decoder.decode(in, out, false);
                    out.flip();
                    String chunk = out.toString();
                    pending = new byte[in.remaining()];
                    in.get(pending);

                    OutputListener l = outputListener;
                    if (l != null && !chunk.isEmpty()) {
                        l.onOutput(chunk);
                    }
                }
                // EOF：以 endOfInput=true 结束解码，不能直接 flush（状态非法会抛异常）
                CharBuffer tail = CharBuffer.allocate(16);
                decoder.decode(ByteBuffer.allocate(0), tail, true);
                tail.flip();
                if (tail.hasRemaining()) {
                    OutputListener l = outputListener;
                    if (l != null) {
                        l.onOutput(tail.toString());
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
