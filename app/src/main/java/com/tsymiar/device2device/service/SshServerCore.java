package com.tsymiar.device2device.service;

import android.content.Context;
import android.util.Log;

import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ShellFactory;

import java.io.File;
import java.io.IOException;

/**
 * Apache MINA SSHD 的薄封装：只开 exec / shell，不挂 SFTP / SCP 子系统。
 *
 * Android 相关的坑在这里处理掉（官方 docs/android.md 的建议）：
 * - user.home / user.dir：Android 没有这两个系统属性，缺了会在初始化时抛
 *   IllegalArgumentException("No user home")；
 * - BouncyCastle / EdDSA 都是 sshd 的可选依赖，没引就登记成「提前禁用」，
 *   否则 sshd 初始化时去 Class.forName 它们，Android 上直接 NoClassDefFoundError 崩进程。
 */
final class SshServerCore {
    private static final String TAG = "SshServerCore";

    private static volatile boolean sAndroidHooksInstalled;

    private final Context appContext;
    private final File rootDir;
    private final int port;
    private final String user;
    private final String password;

    private SshServer server;

    SshServerCore(Context appContext, File rootDir, int port, String user, String password) {
        this.appContext = appContext.getApplicationContext();
        this.rootDir = rootDir;
        this.port = port;
        this.user = user;
        this.password = password;
    }

    static void installAndroidHooks(Context context) {
        if (sAndroidHooksInstalled) {
            return;
        }
        sAndroidHooksInstalled = true;
        File home = context.getFilesDir();
        // sshd 2.x 自己按 java.vendor 判断是不是 Android（OsUtils 没有 setAndroid() 可调），
        // 真正缺的是 user.home / user.dir：Android 上这两个系统属性为空，
        // sshd 初始化解析路径时会抛 IllegalArgumentException("No user home")，这里补上
        System.setProperty("user.home", home.getAbsolutePath());
        System.setProperty("user.dir", home.getAbsolutePath());
        try {
            SecurityUtils.setAPrioriDisabledProvider(SecurityUtils.BOUNCY_CASTLE, true);
            SecurityUtils.setAPrioriDisabledProvider(SecurityUtils.EDDSA, true);
        } catch (Throwable t) {
            Log.w(TAG, "disable optional security providers failed: " + t.getMessage());
        }
    }

    synchronized boolean start() {
        installAndroidHooks(appContext);
        if (server != null) {
            return true;
        }

        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("0.0.0.0");
        sshd.setPort(port);

        // 主机密钥首次自动生成并保存在内部存储，重装/清数据才会换，
        // 否则每次启动指纹都变，客户端会一直弹“主机密钥已变更”
        // 默认算法就是 RSA 2048，不额外 setAlgorithm，少依赖一个 API
        File keyFile = new File(appContext.getFilesDir(), "ssh_host_rsa_key");
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(keyFile.toPath()));

        sshd.setPasswordAuthenticator((username, pwd, session) ->
                user.equals(username) && password.equals(pwd));

        sshd.setShellFactory(new ShellFactory() {
            @Override
            public Command createShell(ChannelSession channel) {
                return new SshShellCommand(appContext, rootDir, null);
            }
        });
        sshd.setCommandFactory(new CommandFactory() {
            @Override
            public Command createCommand(ChannelSession channel, String command) {
                return new SshShellCommand(appContext, rootDir, command);
            }
        });

        try {
            sshd.start();
            server = sshd;
            Log.i(TAG, "sshd listening on port " + port);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "sshd start failed on port " + port + ": " + e.getMessage());
            server = null;
            return false;
        } catch (Throwable t) {
            // Android 上缺算法 / 密钥生成失败抛的是 RuntimeException 甚至 Error，
            // 这里一并兜住转成「启动失败」，绝不让异常冒到 Service 里把进程带崩
            Log.e(TAG, "sshd start error on port " + port, t);
            server = null;
            return false;
        }
    }

    synchronized void stop() {
        SshServer current = server;
        server = null;
        if (current == null) {
            return;
        }
        try {
            // true = 立即关闭已建立的连接，不等客户端自己退出
            current.stop(true);
        } catch (IOException e) {
            Log.w(TAG, "sshd stop failed: " + e.getMessage());
        }
    }

    synchronized boolean isRunning() {
        return server != null;
    }
}
