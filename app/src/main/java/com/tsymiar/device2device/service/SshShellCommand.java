package com.tsymiar.device2device.service;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.StatFs;
import android.util.Log;

import com.tsymiar.device2device.utils.Utils;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * SSH 的 shell / exec 通道实现。
 *
 * 交互 shell（ssh user@ip）走 {@code start(env)} 起的线程循环读命令；
 * 单条命令（ssh user@ip "ls -l"）走同一个 execute()，跑完直接退出。
 *
 * 命令优先走内置实现（基于 java.io.File，根目录锁在 rootDir 内，cd 越界会被拦），
 * 未识别的命令再尝试 /system/bin/sh -c；Android 应用沙箱里 exec 经常被拒，
 * 失败时给出提示而不是抛异常把通道弄挂。
 */
class SshShellCommand implements Command, Runnable {
    private static final String TAG = "SshShellCommand";

    /** execute() 返回这个值表示用户敲了 exit / 断开，外层循环该收尾了 */
    private static final int EXIT_SHELL = -1000;
    private static final int MAX_CAT_BYTES = 256 * 1024;
    private static final int EXTERNAL_TIMEOUT_SECONDS = 10;

    private final Context appContext;
    private final File rootDir;
    /** null 表示交互式 shell，非 null 表示 exec 单条命令 */
    private final String singleCommand;

    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback callback;

    private Thread thread;
    private volatile boolean stopped;
    private volatile File cwd;

    SshShellCommand(Context appContext, File rootDir, String singleCommand) {
        this.appContext = appContext.getApplicationContext();
        this.rootDir = rootDir;
        this.singleCommand = singleCommand;
        this.cwd = rootDir;
    }

    @Override
    public void setInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
        this.err = err;
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.callback = callback;
    }

    @Override
    public void start(ChannelSession session, Environment env) throws IOException {
        thread = new Thread(this, "ssh-shell");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void destroy(ChannelSession channel) throws Exception {
        stopped = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public void run() {
        int exitCode = 0;
        PrintStream ps = null;
        try {
            ps = new PrintStream(new BufferedOutputStream(out, 8192), true, "UTF-8");
            if (singleCommand != null) {
                exitCode = execute(singleCommand, ps);
                if (exitCode == EXIT_SHELL) {
                    exitCode = 0;
                }
            } else {
                ps.println("Device2Device shell —— 内置命令集，输入 help 查看");
                ps.print(prompt());
                ps.flush();
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while (!stopped && (line = reader.readLine()) != null) {
                    int code = execute(line.trim(), ps);
                    if (code == EXIT_SHELL) {
                        break;
                    }
                    ps.print(prompt());
                    ps.flush();
                }
                ps.println("bye");
            }
        } catch (Exception e) {
            // 客户端断开时读输入必然抛异常，属于正常结束，不打 error
            Log.w(TAG, "shell ended: " + e.getMessage());
        } finally {
            if (ps != null) {
                ps.flush();
            }
            flushQuietly(out);
            flushQuietly(err);
            ExitCallback cb = callback;
            if (cb != null) {
                cb.onExit(exitCode);
            }
        }
    }

    private String prompt() {
        String path = cwd.getAbsolutePath();
        String root = rootDir.getAbsolutePath();
        if (path.startsWith(root)) {
            // 提示符里只显示相对根目录的部分，别把一长串 /storage/emulated/... 铺满屏幕
            path = "~" + path.substring(root.length());
        }
        return path.isEmpty() ? "$ " : path + " $ ";
    }

    private int execute(String line, PrintStream ps) {
        if (line == null || line.isEmpty()) {
            return 0;
        }
        String[] args = line.split("\\s+");
        String cmd = args[0];
        switch (cmd) {
            case "exit":
            case "logout":
                return EXIT_SHELL;
            case "help":
                help(ps);
                return 0;
            case "pwd":
                ps.println(cwd.getAbsolutePath());
                return 0;
            case "ls":
                return ls(args, ps);
            case "cd":
                return cd(args, ps);
            case "cat":
                return cat(args, ps);
            case "echo":
                ps.println(line.substring(cmd.length()).trim());
                return 0;
            case "whoami":
                ps.println("shell");
                return 0;
            case "id":
                ps.println("uid=" + Process.myUid() + " pid=" + Process.myPid()
                        + " context=u:r:untrusted_app:s0 (Android 应用沙箱)");
                return 0;
            case "uname":
                ps.println("Android " + Build.VERSION.RELEASE + " api=" + Build.VERSION.SDK_INT
                        + " " + Build.DEVICE + " " + Build.SUPPORTED_ABIS[0]);
                return 0;
            case "date":
                ps.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(new Date()));
                return 0;
            case "df":
                return df(ps);
            case "ip":
                ps.println(Utils.getLocalWifiIp(appContext));
                return 0;
            default:
                return external(line, ps);
        }
    }

    private void help(PrintStream ps) {
        ps.println("内置命令：");
        ps.println("  help                 显示本帮助");
        ps.println("  pwd                  当前目录");
        ps.println("  ls [dir]             列出目录内容（d=目录 -=文件，带大小与时间）");
        ps.println("  cd [dir]             切换目录，越出根目录会被拒绝");
        ps.println("  cat <file>           输出文本文件内容（最多 256KB）");
        ps.println("  echo <text>          回显一行");
        ps.println("  whoami / id          当前身份（就是本应用进程）");
        ps.println("  uname / date         设备与系统信息 / 当前时间");
        ps.println("  df                   内部存储与共享存储剩余空间");
        ps.println("  ip                   当前 Wi-Fi 局域网 IP");
        ps.println("  exit | logout        断开连接");
        ps.println("其它命令会转交给 /system/bin/sh -c，Android 沙箱常常直接拒绝执行。");
    }

    private int ls(String[] args, PrintStream ps) {
        File target = args.length > 1 ? resolve(args[1]) : cwd;
        if (target == null) {
            ps.println("ls: 路径越出根目录");
            return 1;
        }
        if (!target.exists()) {
            ps.println("ls: " + target.getName() + ": 不存在");
            return 1;
        }
        File[] files = target.isDirectory() ? target.listFiles() : new File[]{target};
        if (files == null) {
            ps.println("ls: 无法读取目录");
            return 1;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (File f : files) {
            String type = f.isDirectory() ? "d" : "-";
            String size = f.isDirectory() ? "<dir>" : String.valueOf(f.length());
            ps.println(type + " " + pad(size, 10) + " "
                    + fmt.format(new Date(f.lastModified())) + " " + f.getName());
        }
        return 0;
    }

    private int cd(String[] args, PrintStream ps) {
        File target = args.length > 1 ? resolve(args[1]) : rootDir;
        if (target == null) {
            ps.println("cd: 路径越出根目录");
            return 1;
        }
        if (!target.exists() || !target.isDirectory()) {
            ps.println("cd: 目录不存在: " + args[1]);
            return 1;
        }
        cwd = target;
        return 0;
    }

    private int cat(String[] args, PrintStream ps) {
        if (args.length < 2) {
            ps.println("cat: 需要指定文件");
            return 1;
        }
        File target = resolve(args[1]);
        if (target == null) {
            ps.println("cat: 路径越出根目录");
            return 1;
        }
        if (!target.isFile()) {
            ps.println("cat: 不是文件: " + args[1]);
            return 1;
        }
        try {
            FileInputStream fis = new FileInputStream(target);
            try {
                byte[] buf = new byte[8192];
                int total = 0;
                int n;
                while ((n = fis.read(buf)) > 0) {
                    ps.write(buf, 0, n);
                    total += n;
                    if (total >= MAX_CAT_BYTES) {
                        ps.println("\n(cat: 超过 256KB，已截断)");
                        break;
                    }
                }
                ps.flush();
            } finally {
                fis.close();
            }
        } catch (IOException e) {
            ps.println("cat: 读取失败: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    private int df(PrintStream ps) {
        printStat(ps, "内部存储", rootDir);
        File data = new File("/data");
        if (data.exists()) {
            printStat(ps, "/data", data);
        }
        return 0;
    }

    private void printStat(PrintStream ps, String label, File path) {
        try {
            StatFs stat = new StatFs(path.getAbsolutePath());
            long total = stat.getBlockCountLong() * stat.getBlockSizeLong();
            long avail = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
            ps.println(label + ": 可用 " + human(avail) + " / 共 " + human(total));
        } catch (Exception e) {
            ps.println(label + ": 无法读取 (" + e.getMessage() + ")");
        }
    }

    /** 内置命令之外的命令：交给 /system/bin/sh -c，被沙箱拦下就提示 */
    private int external(String line, PrintStream ps) {
        try {
            // 显式写全 java.lang.Process：本类 import 的是 android.os.Process
            final java.lang.Process process =
                    Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", line}, null, cwd);
            Thread pump = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(new InputStreamReader(
                                process.getInputStream(), StandardCharsets.UTF_8));
                        String l;
                        PrintStream p = ps;
                        while ((l = br.readLine()) != null) {
                            p.println(l);
                        }
                    } catch (IOException ignored) {
                        // 进程被销毁时流会断开，正常
                    }
                }
            }, "ssh-cmd-pump");
            pump.setDaemon(true);
            pump.start();
            boolean finished = process.waitFor(EXTERNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                ps.println("(命令超过 " + EXTERNAL_TIMEOUT_SECONDS + " 秒，已终止)");
                return 124;
            }
            pump.join(2000);
            return process.exitValue();
        } catch (Exception e) {
            ps.println(line.split("\\s+")[0]
                    + ": 无法执行（Android 应用沙箱限制），输入 help 查看内置命令");
            return 127;
        }
    }

    /** 把路径解析到 rootDir 之下：绝对路径、~、.. 都按 rootDir 归一化，越界返回 null */
    private File resolve(String path) {
        File base = path.startsWith("/") ? new File(rootDir, path) : new File(cwd, path);
        try {
            File canonical = base.getCanonicalFile();
            File root = rootDir.getCanonicalFile();
            if (canonical.equals(root)) {
                return canonical;
            }
            String rootPath = root.getAbsolutePath();
            String targetPath = canonical.getAbsolutePath();
            if (!targetPath.startsWith(rootPath + File.separator)) {
                return null;
            }
            return canonical;
        } catch (IOException e) {
            return null;
        }
    }

    private static String pad(String text, int width) {
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() < width) {
            sb.insert(0, ' ');
        }
        return sb.toString();
    }

    private static String human(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int i = 0;
        while (value >= 1024 && i < units.length - 1) {
            value /= 1024;
            i++;
        }
        return String.format(Locale.US, "%.1f%s", value, units[i]);
    }

    private static void flushQuietly(OutputStream stream) {
        try {
            if (stream != null) {
                stream.flush();
            }
        } catch (IOException ignored) {
        }
    }
}
