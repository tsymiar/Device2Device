package com.tsymiar.device2device.service;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.os.StatFs;
import android.util.Log;

import com.tsymiar.device2device.utils.Utils;

import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.channel.PtyMode;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * SSH shell / exec 通道实现：交互 shell 由 {@code start(env)} 起的线程循环读命令，
 * 单条命令复用 {@code execute()}。
 *
 * 账户间以 {@code rootDir/users/<user>} 为家目录与访问边界（cd 越界被拦）；
 * 支持 ; && || 链式、>< 重定向、通配符，未识别命令转交 /system/bin/sh -c。
 *
 * shell 状态（cwd + env）按 SSH 会话持久化，exec 模式下的 cd / export 也能跨命令保留。
 */
class SshShellCommand implements Command, Runnable {
    private static final String TAG = "SshShellCommand";

    /** execute() 返回此值表示 exit / 断开，外层循环应结束 */
    private static final int EXIT_SHELL = -1000;
    private static final int MAX_CAT_BYTES = 256 * 1024;
    private static final int EXTERNAL_TIMEOUT_SECONDS = 10;
    private static final String HOST = Build.DEVICE;

    private static final AttributeRepository.AttributeKey<ShellState> ATTR_STATE =
            new AttributeRepository.AttributeKey<ShellState>() {};

    private final Context appContext;
    /** 全局根目录，仅 df 统计用 */
    private final File rootDir;
    /** 家目录 = 命令访问边界 */
    private final File userHome;
    private final String userName;
    /** null = 交互式 shell，非 null = exec 单条命令 */
    private final String singleCommand;

    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback callback;

    private Thread thread;
    private volatile boolean stopped;
    /** 会话级 cwd + env，从会话属性取，避免每次 exec 重置 */
    private ShellState state;
    /** Android 上 sshd 的 PTY 回显不生效，真实终端改由服务端回显 */
    private boolean hasPty;
    /** 真实终端需逐字符回显；内置客户端(D2DAPP)自带输入框，不需要 */
    private boolean echoInput;

    /** 会话级工作状态：cwd + env */
    static final class ShellState {
        final File userHome;
        final String userName;
        File cwd;
        final Map<String, String> env = new HashMap<>();

        ShellState(File userHome, String userName) {
            this.userHome = userHome;
            this.userName = userName;
            this.cwd = userHome;
            env.put("USER", userName);
            env.put("HOME", userHome.getAbsolutePath());
            env.put("SHELL", "/system/bin/sh");
            // Termux 风格的应用内前缀：用户可以往 $PREFIX/bin 放自己的可执行文件/脚本，
            // 外部命令会继承这里的 PATH 和 PREFIX，不需要 root 也不用装 Termux。
            File prefix = new File(userHome, "usr");
            File bin = new File(prefix, "bin");
            File lib = new File(prefix, "lib");
            File tmp = new File(prefix, "tmp");
            if (!bin.exists()) bin.mkdirs();
            if (!lib.exists()) lib.mkdirs();
            if (!tmp.exists()) tmp.mkdirs();
            env.put("PREFIX", prefix.getAbsolutePath());
            env.put("PATH", bin.getAbsolutePath() + ":/system/bin:/system/xbin:/sbin");
            env.put("TMPDIR", tmp.getAbsolutePath());
            env.put("LANG", "C.UTF-8");
            env.put("PWD", userHome.getAbsolutePath());
            if (!env.containsKey("TERM")) {
                env.put("TERM", "xterm");
            }
        }
    }

    SshShellCommand(Context appContext, File rootDir, String userName, String singleCommand) {
        this.appContext = appContext.getApplicationContext();
        this.rootDir = rootDir;
        this.userName = (userName == null || userName.isEmpty()) ? "shell" : userName;
        this.userHome = new File(rootDir, "users" + File.separator + this.userName);
        if (!userHome.exists()) {
            userHome.mkdirs();
        }
        this.singleCommand = singleCommand;
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
        ShellState st = session.getSession().getAttribute(ATTR_STATE);
        if (st == null) {
            st = new ShellState(userHome, userName);
            session.getSession().setAttribute(ATTR_STATE, st);
        }
        this.state = st;
        // 真实终端会请求 PTY 并依赖回显，但 Android 上 PTY 回显不生效 → 服务端逐字符回显；
        // 内置客户端带 D2DAPP=1，自带输入框，不回显以免重复。
        boolean pty = false;
        boolean isApp = false;
        if (env != null) {
            Map<PtyMode, Integer> modes = env.getPtyModes();
            pty = modes != null && !modes.isEmpty();
            if (env.getEnv() != null) {
                isApp = "1".equals(env.getEnv().get("D2DAPP"));
            }
        }
        this.hasPty = pty;
        this.echoInput = pty && !isApp;
        // 客户端环境变量（如 TERM）合入，不覆盖预设
        if (env != null && env.getEnv() != null) {
            for (Map.Entry<String, String> e : env.getEnv().entrySet()) {
                if (!st.env.containsKey(e.getKey())) {
                    st.env.put(e.getKey(), e.getValue());
                }
            }
        }
        thread = new Thread(this, "ssh-shell-" + userName);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void destroy(ChannelSession channel) throws Exception {
        Log.i(TAG, "shell destroy() called");
        stopped = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public void run() {
        int exitCode = 0;
        PrintStream o = null;
        PrintStream e = null;
        try {
            if (singleCommand != null) {
                // exec 面向本应用客户端，纯 \n 即可
                o = new PrintStream(new BufferedOutputStream(out, 8192), true, "UTF-8");
                e = (err != null && err != out)
                        ? new PrintStream(new BufferedOutputStream(err, 8192), true, "UTF-8") : o;
            } else {
                // 真实终端必须 CRLF，否则只下移不回车
                o = new PrintStream(new BufferedOutputStream(new CrlfFilterOutputStream(out), 8192),
                        true, "UTF-8");
                e = (err != null && err != out)
                        ? new PrintStream(
                        new BufferedOutputStream(new CrlfFilterOutputStream(err), 8192),
                        true, "UTF-8") : o;
            }
            if (singleCommand != null) {
                exitCode = execute(singleCommand, o, e);
                if (exitCode == EXIT_SHELL) {
                    exitCode = 0;
                }
            } else {
                o.println("Device2Device shell —— 已登录为 " + state.userName
                        + "，家目录 " + state.userHome.getAbsolutePath());
                o.println("内置命令集，输入 help 查看；支持 ; && || 串联、> >> < 重定向、* ? 通配");
                o.println("应用内环境 PREFIX=" + state.env.get("PREFIX")
                        + "，$PREFIX/bin 中的程序可直接运行（pkg 查看）");
                o.print(prompt());
                o.flush();
                Log.i(TAG, "shell loop start, hasPty=" + hasPty + ", echoInput=" + echoInput);
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while (!stopped && (line = readCommand(reader, o, echoInput)) != null) {
                    Log.i(TAG, "shell recv: [" + line + "]");
                    int code;
                    try {
                        code = execute(line, o, e);
                    } catch (Throwable t) {
                        // 单条命令异常不能关掉 shell，否则后续全报"未连接"
                        o.println("命令执行出错: " + t.getMessage());
                        code = 1;
                    }
                    if (code == EXIT_SHELL) {
                        break;
                    }
                    o.print(prompt());
                    o.flush();
                }
                Log.i(TAG, stopped ? "shell loop exit: destroyed" : "shell loop exit: input EOF");
                o.println("bye");
            }
        } catch (Exception ex) {
            // 断开时读输入必抛异常，属正常结束
            Log.w(TAG, "shell ended: " + ex.getMessage());
        } finally {
            if (o != null) {
                o.flush();
            }
            if (e != null && e != o) {
                e.flush();
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
        return state.userName + "@" + HOST + ":" + relPath(state.cwd) + "$ ";
    }

    /** 绝对路径压成家目录相对简写（~、~/xxx） */
    private String relPath(File f) {
        String home = state.userHome.getAbsolutePath();
        String abs = f.getAbsolutePath();
        if (abs.equals(home)) {
            return "~";
        }
        if (abs.startsWith(home + File.separator)) {
            return "~" + abs.substring(home.length());
        }
        return abs;
    }

    /**
     * 逐字符读一行：必须用 read()，Android 上 BufferedReader.readLine() 会在第一条命令后
     * 误判 EOF 并关闭 shell。行结束兼容真实终端的 \r(\r\n) 与内置客户端的 \n。
     *
     * <p>echoChars 为 true 时服务端回显并处理退格 / Ctrl-C；返回 null 表示对端断开。
     */
    private String readCommand(BufferedReader reader, PrintStream o, boolean echoChars)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while (true) {
            c = reader.read();
            if (c == -1) {
                return sb.length() == 0 ? null : sb.toString();
            }
            if (c == '\n') {
                break;
            }
            if (c == '\r') {
                // 不能阻塞预读：否则要按两次回车；ready() 非阻塞，\r\n 连到时顺带吞掉 \n
                try {
                    if (reader.ready()) {
                        reader.mark(1);
                        int n = reader.read();
                        if (n != '\n') {
                            reader.reset();
                        }
                    }
                } catch (IOException ignored) {
                }
                break;
            }
            if (c == 0x03) { // Ctrl-C：放弃当前行
                if (echoChars) {
                    o.print("^C\r\n");
                    o.flush();
                }
                return "";
            }
            if (c == 0x7f || c == 0x08) { // DEL / Backspace
                if (sb.length() > 0) {
                    sb.setLength(sb.length() - 1);
                    if (echoChars) {
                        o.print("\b \b");
                        o.flush();
                    }
                }
                continue;
            }
            sb.append((char) c);
            if (echoChars) {
                o.print((char) c);
                o.flush();
            }
        }
        if (echoChars) {
            o.print("\r\n");
            o.flush();
        }
        return sb.toString();
    }

    /** 执行一整行（可含 ; 串联），返回最后一条的退出码 */
    private int execute(String line, PrintStream o, PrintStream e) {
        if (line == null) {
            return 0;
        }
        List<String> tokens = tokenize(line);
        if (tokens.isEmpty()) {
            return 0;
        }
        // 按 ; 拆成独立语句，互不阻断
        List<List<String>> statements = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String t : tokens) {
            if (t.equals(";")) {
                if (!cur.isEmpty()) {
                    statements.add(cur);
                    cur = new ArrayList<>();
                }
            } else {
                cur.add(t);
            }
        }
        if (!cur.isEmpty()) {
            statements.add(cur);
        }
        int last = 0;
        for (List<String> stmt : statements) {
            last = runStatement(stmt, o, e);
        }
        return last;
    }

    /** 执行一条语句，支持 && / || 短路 */
    private int runStatement(List<String> stmtTokens, PrintStream o, PrintStream e) {
        List<List<String>> segments = new ArrayList<>();
        List<String> ops = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String t : stmtTokens) {
            if (t.equals("&&") || t.equals("||")) {
                if (!cur.isEmpty()) {
                    segments.add(cur);
                    cur = new ArrayList<>();
                }
                ops.add(t);
            } else {
                cur.add(t);
            }
        }
        if (!cur.isEmpty()) {
            segments.add(cur);
        }
        int code = 0;
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                String op = ops.get(i - 1);
                if (op.equals("&&") && code != 0) {
                    break;
                }
                if (op.equals("||") && code == 0) {
                    break;
                }
            }
            code = runCommand(segments.get(i), o, e);
        }
        return code;
    }

    /** 执行单条命令：处理 > >> < 重定向与通配后分发 */
    private int runCommand(List<String> segTokens, PrintStream o, PrintStream e) {
        List<String> args = new ArrayList<>();
        String redirOut = null;
        boolean append = false;
        String redirIn = null;
        for (int i = 0; i < segTokens.size(); i++) {
            String t = segTokens.get(i);
            if (t.equals(">")) {
                if (i + 1 < segTokens.size()) {
                    redirOut = segTokens.get(++i);
                }
                append = false;
            } else if (t.equals(">>")) {
                if (i + 1 < segTokens.size()) {
                    redirOut = segTokens.get(++i);
                }
                append = true;
            } else if (t.equals("<")) {
                if (i + 1 < segTokens.size()) {
                    redirIn = segTokens.get(++i);
                }
            } else {
                // 先展开 $VAR，再对文件参数做通配
                String expanded = expand(t);
                args.addAll(glob(expanded));
            }
        }
        if (args.isEmpty()) {
            return 0;
        }
        String name = args.get(0);

        // 非内置命令整段交给 /system/bin/sh -c（自带重定向/通配/管道）
        if (!isBuiltin(name)) {
            String raw = joinRaw(segTokens);
            return external(raw, o, e);
        }

        // 内置命令：应用重定向
        InputStream effectiveIn = in;
        OutputStream effectiveOut = o;
        boolean closeIn = false;
        boolean closeOut = false;
        try {
            if (redirIn != null) {
                File f = resolve(redirIn);
                if (f == null || !f.isFile()) {
                    o.println("redirect: 无法打开输入文件: " + redirIn);
                    return 1;
                }
                effectiveIn = new FileInputStream(f);
                closeIn = true;
            }
            if (redirOut != null) {
                File f = resolve(redirOut);
                if (f == null) {
                    o.println("redirect: 路径越出家目录: " + redirOut);
                    return 1;
                }
                File parent = f.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    o.println("redirect: 无法创建父目录: " + redirOut);
                    return 1;
                }
                effectiveOut = new FileOutputStream(f, append);
                closeOut = true;
            }
            PrintStream so = (effectiveOut == o) ? o
                    : new PrintStream(new BufferedOutputStream(effectiveOut, 4096), false, "UTF-8");
            PrintStream se = e;
            int code = dispatch(args.toArray(new String[0]), effectiveIn, so, se);
            so.flush();
            return code;
        } catch (IOException ex) {
            o.println("redirect: " + ex.getMessage());
            return 1;
        } finally {
            if (closeIn) {
                try {
                    effectiveIn.close();
                } catch (IOException ignored) {
                }
            }
            if (closeOut) {
                try {
                    effectiveOut.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private int dispatch(String[] args, InputStream in, PrintStream o, PrintStream e) {
        String cmd = args[0];
        switch (cmd) {
            case "exit":
            case "logout":
                return EXIT_SHELL;
            case "help":
                help(o);
                return 0;
            case "pwd":
                o.println(state.cwd.getAbsolutePath());
                return 0;
            case "ls":
                return ls(args, o);
            case "cd":
                return cd(args, o);
            case "cat":
                return cat(args, o);
            case "echo":
                o.println(expand(joinArgs(args, 1)));
                return 0;
            case "env":
                return envCmd(o);
            case "export":
                return exportCmd(args, o);
            case "whoami":
                o.println(state.userName);
                return 0;
            case "id":
                o.println("uid=" + Process.myUid() + " pid=" + Process.myPid()
                        + " user=" + state.userName
                        + " context=u:r:untrusted_app:s0 (Android 应用沙箱)");
                return 0;
            case "uname":
                o.println("Android " + Build.VERSION.RELEASE + " api=" + Build.VERSION.SDK_INT
                        + " " + Build.DEVICE + " " + Build.SUPPORTED_ABIS[0]);
                return 0;
            case "date":
                o.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(new Date()));
                return 0;
            case "df":
                return df(o);
            case "ip":
                o.println(Utils.getLocalWifiIp(appContext));
                return 0;
            case "hostname":
                o.println(HOST);
                return 0;
            case "mkdir":
                return mkdir(args, o);
            case "rm":
                return rm(args, o);
            case "touch":
                return touch(args, o);
            case "cp":
                return cp(args, o);
            case "mv":
                return mv(args, o);
            case "head":
                return headTail(args, o, true);
            case "tail":
                return headTail(args, o, false);
            case "wc":
                return wc(args, o);
            case "grep":
                return grep(args, o);
            case "sort":
                return sortCmd(args, in, o);
            case "uniq":
                return uniqCmd(args, in, o);
            case "find":
                return findCmd(args, o);
            case "du":
                return duCmd(args, o);
            case "stat":
                return statCmd(args, o);
            case "prefix":
                o.println(state.env.get("PREFIX"));
                return 0;
            case "pkg":
                return prefixPkg(args, o);
            case "which":
                return whichCmd(args, o);
            case "termux":
                return termuxBridge(args, o);
            case "clear":
                for (int i = 0; i < 40; i++) {
                    o.println();
                }
                return 0;
            default:
                return external(joinRaw(Arrays.asList(args)), o, e);
        }
    }

    /** 列出应用内 $PREFIX/bin 里可用的程序（Termux 的 pkg list-installed 的轻量版） */
    private int prefixPkg(String[] args, PrintStream o) {
        File bin = new File(state.env.get("PREFIX"), "bin");
        File[] files = bin.listFiles();
        if (files == null || files.length == 0) {
            o.println("$PREFIX/bin 为空。可以把可执行文件放进去：");
            o.println("  " + bin.getAbsolutePath());
            o.println("例如：cp /sdcard/Download/busybox $PREFIX/bin/ && chmod 755 $PREFIX/bin/busybox");
            return 0;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        o.println("应用内 $PREFIX/bin (" + files.length + ") :");
        for (File f : files) {
            o.println("  " + f.getName() + (f.canExecute() ? "" : "  (不可执行)"));
        }
        return 0;
    }

    /** 在 PATH 中查找可执行文件，行为对齐 which */
    private int whichCmd(String[] args, PrintStream o) {
        if (args.length < 2) {
            o.println("用法: which <命令> [命令...]");
            return 2;
        }
        int missing = 0;
        String[] dirs = state.env.get("PATH").split(File.pathSeparator);
        for (int i = 1; i < args.length; i++) {
            String name = args[i];
            File found = null;
            if (name.contains("/")) {
                File f = new File(name);
                if (f.isFile() && f.canExecute()) found = f;
            } else {
                for (String dir : dirs) {
                    File f = new File(dir, name);
                    if (f.isFile() && f.canExecute()) {
                        found = f;
                        break;
                    }
                }
            }
            if (found == null) {
                missing++;
            } else {
                o.println(found.getAbsolutePath());
            }
        }
        return missing == 0 ? 0 : 1;
    }

    /**
     * Termux 桥接：
     * - termux          说明应用内环境
     * - termux open     若设备装了 Termux，则拉起它（只是打开 App，不接管 SSH 会话）
     */
    private int termuxBridge(String[] args, PrintStream o) {
        if (args.length > 1 && "open".equalsIgnoreCase(args[1])) {
            try {
                Intent intent = appContext.getPackageManager()
                        .getLaunchIntentForPackage("com.termux");
                if (intent == null) {
                    o.println("未安装 Termux。本 SSH 服务已内置应用内 $PREFIX 环境，无需 Termux。");
                    return 1;
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);
                o.println("已请求启动 Termux（会话仍在 SSH 内）。");
                return 0;
            } catch (ActivityNotFoundException ex) {
                o.println("Termux 启动失败: " + ex.getMessage());
                return 1;
            }
        }
        o.println("应用内模拟环境：$PREFIX=" + state.env.get("PREFIX"));
        o.println("  PATH = " + state.env.get("PATH"));
        o.println("  把可执行文件放到 $PREFIX/bin 后即可直接按名字运行；pkg 可列出已放入的程序。");
        o.println("  termux open 可在设备装有 Termux 时拉起它（不接管当前 SSH 会话）。");
        return 0;
    }

    private void help(PrintStream o) {
        o.println("内置命令（支持 ; && || 串联，> >> < 重定向，* ? 通配）：");
        o.println("  help                 显示本帮助");
        o.println("  pwd                  当前目录绝对路径");
        o.println("  ls [-l] [path]       列出目录");
        o.println("  cd [path]            切换目录，越出家目录会被拒绝");
        o.println("  cat <file>           输出文本文件内容（最多 256KB）");
        o.println("  echo <text>          回显，$VAR / ${VAR} 会展开");
        o.println("  env                  列出当前环境变量");
        o.println("  export NAME=VALUE    设置环境变量");
        o.println("  whoami / id          当前登录账户与进程身份");
        o.println("  uname / date         设备系统信息 / 当前时间");
        o.println("  hostname             设备名");
        o.println("  df / du              存储剩余 / 目录占用");
        o.println("  ip                   当前 Wi-Fi 局域网 IP");
        o.println("  prefix / pkg         应用内 $PREFIX 路径 / 列出 $PREFIX/bin 中的程序");
        o.println("  which <cmd>          在 PATH（含 $PREFIX/bin）中查找可执行文件");
        o.println("  termux [open]        应用内环境说明 / 拉起设备上的 Termux App");
        o.println("  mkdir [-p] / rm [-r] / touch / cp [-r] / mv   文件操作");
        o.println("  head [-n N] / tail [-n N] / wc / grep / sort / uniq   文本处理");
        o.println("  find [dir] -name PAT 按名查找");
        o.println("  stat <file>          文件大小 / 时间");
        o.println("  clear                清屏");
        o.println("  exit | logout        断开连接");
        o.println("其它命令（含管道 | ）转交 /system/bin/sh -c，Android 沙箱常常直接拒绝执行。");
    }

    private int ls(String[] args, PrintStream o) {
        boolean longFmt = false;
        String target = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-l")) {
                longFmt = true;
            } else {
                target = args[i];
            }
        }
        File f = target != null ? resolve(target) : state.cwd;
        if (f == null) {
            o.println("ls: 路径越出家目录");
            return 1;
        }
        if (!f.exists()) {
            o.println("ls: " + (target != null ? target : f.getName()) + ": 不存在");
            return 1;
        }
        File[] files = f.isDirectory() ? f.listFiles() : new File[]{f};
        if (files == null) {
            o.println("ls: 无法读取目录");
            return 1;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (File file : files) {
            if (longFmt) {
                String type = file.isDirectory() ? "d" : "-";
                String size = file.isDirectory() ? "<dir>" : String.valueOf(file.length());
                o.println(type + " " + pad(size, 10) + " "
                        + fmt.format(new Date(file.lastModified())) + " " + file.getName());
            } else {
                o.print(file.getName() + "  ");
            }
        }
        if (!longFmt) {
            o.println();
        }
        return 0;
    }

    private int cd(String[] args, PrintStream o) {
        File target = args.length > 1 ? resolve(args[1]) : state.userHome;
        if (target == null) {
            o.println("cd: 路径越出家目录");
            return 1;
        }
        if (!target.exists() || !target.isDirectory()) {
            o.println("cd: 目录不存在: " + args[1]);
            return 1;
        }
        state.cwd = target;
        state.env.put("PWD", state.cwd.getAbsolutePath());
        return 0;
    }

    private int cat(String[] args, PrintStream o) {
        if (args.length < 2) {
            o.println("cat: 需要指定文件");
            return 1;
        }
        File target = resolve(args[1]);
        if (target == null) {
            o.println("cat: 路径越出家目录");
            return 1;
        }
        if (!target.isFile()) {
            o.println("cat: 不是文件: " + args[1]);
            return 1;
        }
        try {
            FileInputStream fis = new FileInputStream(target);
            try {
                byte[] buf = new byte[8192];
                int total = 0;
                int n;
                while ((n = fis.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    total += n;
                    if (total >= MAX_CAT_BYTES) {
                        o.println("\n(cat: 超过 256KB，已截断)");
                        break;
                    }
                }
                o.flush();
            } finally {
                fis.close();
            }
        } catch (IOException ex) {
            o.println("cat: 读取失败: " + ex.getMessage());
            return 1;
        }
        return 0;
    }

    private int envCmd(PrintStream o) {
        Map<String, String> sorted = new TreeMap<>(state.env);
        for (Map.Entry<String, String> en : sorted.entrySet()) {
            o.println(en.getKey() + "=" + en.getValue());
        }
        return 0;
    }

    private int exportCmd(String[] args, PrintStream o) {
        if (args.length < 2) {
            o.println("export: 用法 export NAME=VALUE");
            return 1;
        }
        String kv = args[1];
        int idx = kv.indexOf('=');
        if (idx <= 0) {
            o.println("export: 用法 export NAME=VALUE");
            return 1;
        }
        state.env.put(kv.substring(0, idx), kv.substring(idx + 1));
        return 0;
    }

    private int mkdir(String[] args, PrintStream o) {
        boolean parents = false;
        String target = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-p")) {
                parents = true;
            } else {
                target = args[i];
            }
        }
        if (target == null) {
            o.println("mkdir: 需要目录名");
            return 1;
        }
        File dir = resolve(target);
        if (dir == null) {
            o.println("mkdir: 路径越出家目录");
            return 1;
        }
        if (dir.exists()) {
            o.println("mkdir: " + target + ": 已存在");
            return 1;
        }
        boolean ok = parents ? dir.mkdirs() : dir.mkdir();
        if (!ok) {
            o.println("mkdir: 创建失败: " + target);
            return 1;
        }
        return 0;
    }

    private int rm(String[] args, PrintStream o) {
        boolean recursive = false;
        String target = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-r") || args[i].equals("-rf") || args[i].equals("-fr")) {
                recursive = true;
            } else {
                target = args[i];
            }
        }
        if (target == null) {
            o.println("rm: 需要路径");
            return 1;
        }
        File f = resolve(target);
        if (f == null) {
            o.println("rm: 路径越出家目录");
            return 1;
        }
        if (!f.exists()) {
            o.println("rm: " + target + ": 不存在");
            return 1;
        }
        if (f.isDirectory() && !recursive) {
            o.println("rm: " + target + ": 是目录（用 -r 递归删除）");
            return 1;
        }
        if (!deleteRecursive(f)) {
            o.println("rm: 删除失败: " + target);
            return 1;
        }
        return 0;
    }

    private int touch(String[] args, PrintStream o) {
        if (args.length < 2) {
            o.println("touch: 需要文件名");
            return 1;
        }
        File f = resolve(args[1]);
        if (f == null) {
            o.println("touch: 路径越出家目录");
            return 1;
        }
        try {
            if (f.exists()) {
                f.setLastModified(System.currentTimeMillis());
            } else {
                File parent = f.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    o.println("touch: 无法创建父目录: " + args[1]);
                    return 1;
                }
                new FileOutputStream(f).close();
            }
            return 0;
        } catch (IOException ex) {
            o.println("touch: 创建失败: " + ex.getMessage());
            return 1;
        }
    }

    private int cp(String[] args, PrintStream o) {
        boolean recursive = false;
        String src = null;
        String dst = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-r") || args[i].equals("-rf") || args[i].equals("-fr")) {
                recursive = true;
            } else if (src == null) {
                src = args[i];
            } else {
                dst = args[i];
            }
        }
        if (src == null || dst == null) {
            o.println("cp: 用法 cp [-r] <src> <dst>");
            return 1;
        }
        File s = resolve(src);
        File d = resolve(dst);
        if (s == null || d == null) {
            o.println("cp: 路径越出家目录");
            return 1;
        }
        if (!s.exists()) {
            o.println("cp: " + src + ": 不存在");
            return 1;
        }
        if (s.isDirectory() && !recursive) {
            o.println("cp: " + src + ": 是目录（用 -r 递归复制）");
            return 1;
        }
        if (!copyRecursive(s, d)) {
            o.println("cp: 复制失败: " + src);
            return 1;
        }
        return 0;
    }

    private int mv(String[] args, PrintStream o) {
        if (args.length < 3) {
            o.println("mv: 用法 mv <src> <dst>");
            return 1;
        }
        File s = resolve(args[1]);
        File d = resolve(args[2]);
        if (s == null || d == null) {
            o.println("mv: 路径越出家目录");
            return 1;
        }
        if (!s.exists()) {
            o.println("mv: " + args[1] + ": 不存在");
            return 1;
        }
        if (s.renameTo(d)) {
            return 0;
        }
        if (copyRecursive(s, d) && deleteRecursive(s)) {
            return 0;
        }
        o.println("mv: 移动失败: " + args[1]);
        return 1;
    }

    private int headTail(String[] args, PrintStream o, boolean head) {
        int n = 10;
        String file = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("-n")) {
                String num = args[i].length() > 2 ? args[i].substring(2)
                        : (i + 1 < args.length ? args[++i] : "");
                try {
                    n = Integer.parseInt(num.trim());
                } catch (NumberFormatException ignored) {
                    n = 10;
                }
            } else {
                file = args[i];
            }
        }
        if (file == null) {
            o.println((head ? "head" : "tail") + ": 需要文件名");
            return 1;
        }
        File f = resolve(file);
        if (f == null) {
            o.println((head ? "head" : "tail") + ": 路径越出家目录");
            return 1;
        }
        if (!f.isFile()) {
            o.println((head ? "head" : "tail") + ": 不是文件: " + file);
            return 1;
        }
        try {
            List<String> lines = readLines(f);
            int size = lines.size();
            int from = head ? 0 : Math.max(0, size - n);
            int to = head ? Math.min(size, n) : size;
            for (int i = from; i < to; i++) {
                o.println(lines.get(i));
            }
            return 0;
        } catch (IOException ex) {
            o.println((head ? "head" : "tail") + ": 读取失败: " + ex.getMessage());
            return 1;
        }
    }

    private int wc(String[] args, PrintStream o) {
        if (args.length < 2) {
            o.println("wc: 需要文件名");
            return 1;
        }
        File f = resolve(args[1]);
        if (f == null) {
            o.println("wc: 路径越出家目录");
            return 1;
        }
        if (!f.isFile()) {
            o.println("wc: 不是文件: " + args[1]);
            return 1;
        }
        try {
            List<String> lines = readLines(f);
            int words = 0;
            long bytes = f.length();
            for (String l : lines) {
                for (String w : l.split("\\s+")) {
                    if (!w.isEmpty()) {
                        words++;
                    }
                }
            }
            o.println(pad(String.valueOf(lines.size()), 6)
                    + " " + pad(String.valueOf(words), 6)
                    + " " + pad(String.valueOf(bytes), 8) + " " + args[1]);
            return 0;
        } catch (IOException ex) {
            o.println("wc: 读取失败: " + ex.getMessage());
            return 1;
        }
    }

    private int grep(String[] args, PrintStream o) {
        if (args.length < 3) {
            o.println("grep: 用法 grep <pattern> <file>");
            return 1;
        }
        String pattern = args[1];
        File f = resolve(args[2]);
        if (f == null) {
            o.println("grep: 路径越出家目录");
            return 1;
        }
        if (!f.isFile()) {
            o.println("grep: 不是文件: " + args[2]);
            return 1;
        }
        try {
            for (String l : readLines(f)) {
                if (l.contains(pattern)) {
                    o.println(l);
                }
            }
            return 0;
        } catch (IOException ex) {
            o.println("grep: 读取失败: " + ex.getMessage());
            return 1;
        }
    }

    private int sortCmd(String[] args, InputStream in, PrintStream o) {
        List<String> lines = readInputOrFile(args, 1, in);
        if (lines == null) {
            o.println("sort: 需要文件名");
            return 1;
        }
        Collections.sort(lines);
        for (String l : lines) {
            o.println(l);
        }
        return 0;
    }

    private int uniqCmd(String[] args, InputStream in, PrintStream o) {
        List<String> lines = readInputOrFile(args, 1, in);
        if (lines == null) {
            o.println("uniq: 需要文件名");
            return 1;
        }
        String prev = null;
        for (String l : lines) {
            if (prev == null || !prev.equals(l)) {
                o.println(l);
                prev = l;
            }
        }
        return 0;
    }

    private int findCmd(String[] args, PrintStream o) {
        String dir = ".";
        String pattern = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-name") && i + 1 < args.length) {
                pattern = args[++i];
            } else if (!args[i].startsWith("-")) {
                dir = args[i];
            }
        }
        File base = resolve(dir);
        if (base == null || !base.isDirectory()) {
            o.println("find: 目录无效: " + dir);
            return 1;
        }
        findRecursive(base, pattern, o);
        return 0;
    }

    private void findRecursive(File dir, String pattern, PrintStream o) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (pattern == null || matchGlob(f.getName(), pattern)) {
                o.println(f.getAbsolutePath());
            }
            if (f.isDirectory()) {
                findRecursive(f, pattern, o);
            }
        }
    }

    private int duCmd(String[] args, PrintStream o) {
        File base = state.cwd;
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("-")) {
                File r = resolve(args[i]);
                if (r != null) {
                    base = r;
                }
            }
        }
        if (base == null || !base.exists()) {
            o.println("du: 路径无效");
            return 1;
        }
        long total = duRecursive(base);
        o.println(human(total) + "\t" + relPath(base));
        return 0;
    }

    private long duRecursive(File f) {
        if (f.isFile()) {
            return f.length();
        }
        long sum = 0;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                sum += duRecursive(c);
            }
        }
        return sum;
    }

    private int statCmd(String[] args, PrintStream o) {
        if (args.length < 2) {
            o.println("stat: 需要文件名");
            return 1;
        }
        File f = resolve(args[1]);
        if (f == null) {
            o.println("stat: 路径越出家目录");
            return 1;
        }
        if (!f.exists()) {
            o.println("stat: 不存在: " + args[1]);
            return 1;
        }
        o.println("  File: " + relPath(f));
        o.println("  Size: " + f.length() + " bytes");
        o.println("  Type: " + (f.isDirectory() ? "directory" : "regular file"));
        o.println("  MTime: "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(f.lastModified())));
        return 0;
    }

    /** sort/uniq：有文件读文件，否则读 stdin（sort < file） */
    private List<String> readInputOrFile(String[] args, int startIdx, InputStream in) {
        for (int i = startIdx; i < args.length; i++) {
            if (!args[i].startsWith("-")) {
                File f = resolve(args[i]);
                if (f != null && f.isFile()) {
                    try {
                        return readLines(f);
                    } catch (IOException ex) {
                        return null;
                    }
                }
            }
        }
        try {
            return readLines(in);
        } catch (IOException ex) {
            return null;
        }
    }

    /** 把 $VAR / ${VAR} 展开成环境变量值 */
    private String expand(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '$' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '{') {
                    int end = s.indexOf('}', i + 2);
                    if (end > i + 2) {
                        sb.append(state.env.getOrDefault(s.substring(i + 2, end), ""));
                        i = end + 1;
                        continue;
                    }
                } else if (Character.isLetterOrDigit(n) || n == '_') {
                    int j = i + 1;
                    while (j < s.length()
                            && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                        j++;
                    }
                    sb.append(state.env.getOrDefault(s.substring(i + 1, j), ""));
                    i = j;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private int df(PrintStream o) {
        printStat(o, "内部存储", rootDir);
        File data = new File("/data");
        if (data.exists()) {
            printStat(o, "/data", data);
        }
        return 0;
    }

    private void printStat(PrintStream o, String label, File path) {
        try {
            StatFs stat = new StatFs(path.getAbsolutePath());
            long total = stat.getBlockCountLong() * stat.getBlockSizeLong();
            long avail = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
            o.println(label + ": 可用 " + human(avail) + " / 共 " + human(total));
        } catch (Exception ex) {
            o.println(label + ": 无法读取 (" + ex.getMessage() + ")");
        }
    }

    /** 非内置命令交给 /system/bin/sh -c，被沙箱拦下则提示 */
    private int external(String raw, PrintStream o, PrintStream e) {
        try {
            // 把会话环境（PATH/PREFIX/HOME/TMPDIR...）传给子进程，
            // 这样 $PREFIX/bin 里的程序可以直接按名字调用，行为接近 Termux
            String[] envp = new String[state.env.size() + 1];
            int idx = 0;
            for (Map.Entry<String, String> kv : state.env.entrySet()) {
                envp[idx++] = kv.getKey() + "=" + kv.getValue();
            }
            envp[idx] = "ANDROID_ROOT=/system";
            final java.lang.Process process = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/sh", "-c", raw}, envp, state.cwd);
            Thread pump = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(new InputStreamReader(
                                process.getInputStream(), StandardCharsets.UTF_8));
                        String l;
                        while ((l = br.readLine()) != null) {
                            o.println(l);
                        }
                    } catch (IOException ignored) {
                    }
                }
            }, "ssh-cmd-pump");
            pump.setDaemon(true);
            pump.start();
            boolean finished = process.waitFor(EXTERNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                o.println("(命令超过 " + EXTERNAL_TIMEOUT_SECONDS + " 秒，已终止)");
                return 124;
            }
            pump.join(2000);
            return process.exitValue();
        } catch (Exception ex) {
            o.println(raw.trim().split("\\s+")[0]
                    + ": 无法执行（Android 应用沙箱限制），输入 help 查看内置命令");
            return 127;
        }
    }

    /** 解析到 userHome 之下，越界返回 null */
    private File resolve(String path) {
        File base = path.startsWith("/") ? new File(state.userHome, path)
                : (path.startsWith("~") ? new File(state.userHome, path.substring(1))
                : new File(state.cwd, path));
        try {
            File canonical = base.getCanonicalFile();
            File home = state.userHome.getCanonicalFile();
            if (canonical.equals(home)) {
                return canonical;
            }
            String homePath = home.getAbsolutePath();
            String targetPath = canonical.getAbsolutePath();
            if (!targetPath.startsWith(homePath + File.separator)) {
                return null;
            }
            return canonical;
        } catch (IOException ex) {
            return null;
        }
    }

    /** 拆 token：运算符各自成 token，引号内原样保留 */
    private List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ' ' || c == '\t') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else if (c == ';') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add(";");
            } else if (c == '|') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add("|");
            } else if (c == '>') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                if (i + 1 < s.length() && s.charAt(i + 1) == '>') {
                    out.add(">>");
                    i++;
                } else {
                    out.add(">");
                }
            } else if (c == '<') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add("<");
            } else if (c == '&') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add("&&");
                i++;
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private boolean isBuiltin(String name) {
        switch (name) {
            case "exit":
            case "logout":
            case "help":
            case "pwd":
            case "ls":
            case "cd":
            case "cat":
            case "echo":
            case "env":
            case "export":
            case "whoami":
            case "id":
            case "uname":
            case "date":
            case "df":
            case "ip":
            case "hostname":
            case "mkdir":
            case "rm":
            case "touch":
            case "cp":
            case "mv":
            case "head":
            case "tail":
            case "wc":
            case "grep":
            case "sort":
            case "uniq":
            case "find":
            case "du":
            case "stat":
            case "prefix":
            case "pkg":
            case "which":
            case "termux":
            case "clear":
                return true;
            default:
                return false;
        }
    }

    /** token 拼回命令行原文（供 sh -c） */
    private String joinRaw(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    private String joinArgs(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }

    /** 通配展开（* ? [..]），无匹配原样返回 */
    private List<String> glob(String token) {
        if (token.indexOf('*') < 0 && token.indexOf('?') < 0 && token.indexOf('[') < 0) {
            return Collections.singletonList(token);
        }
        int slash = token.lastIndexOf('/');
        File base;
        String namePattern;
        if (slash >= 0) {
            base = resolve(token.substring(0, slash + 1));
            namePattern = token.substring(slash + 1);
        } else {
            base = state.cwd;
            namePattern = token;
        }
        if (base == null || !base.isDirectory()) {
            return Collections.singletonList(token);
        }
        File[] matches = base.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return matchGlob(name, namePattern);
            }
        });
        if (matches == null || matches.length == 0) {
            return Collections.singletonList(token);
        }
        List<String> res = new ArrayList<>();
        for (File f : matches) {
            res.add(slash >= 0 ? f.getAbsolutePath() : f.getName());
        }
        Collections.sort(res);
        return res;
    }

    private boolean matchGlob(String name, String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else if (c == '[') {
                int end = pattern.indexOf(']', i);
                if (end > i) {
                    regex.append(pattern.substring(i, end + 1));
                    i = end;
                } else {
                    regex.append("\\[");
                }
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return name.matches(regex.toString());
    }

    private static List<String> readLines(File f) throws IOException {
        return readLines(new FileInputStream(f));
    }

    private static List<String> readLines(InputStream is) throws IOException {
        List<String> lines = new ArrayList<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        try {
            String l;
            while ((l = br.readLine()) != null) {
                lines.add(l);
            }
        } finally {
            br.close();
        }
        return lines;
    }

    private static boolean copyRecursive(File src, File dst) {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                return false;
            }
            File[] children = src.listFiles();
            if (children == null) {
                return false;
            }
            for (File c : children) {
                if (!copyRecursive(c, new File(dst, c.getName()))) {
                    return false;
                }
            }
            return true;
        } else {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            try {
                FileInputStream in = new FileInputStream(src);
                FileOutputStream out = new FileOutputStream(dst);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    in.close();
                    out.close();
                }
                return true;
            } catch (IOException ex) {
                return false;
            }
        }
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (!deleteRecursive(c)) {
                        return false;
                    }
                }
            }
        }
        return f.delete();
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

    /**
     * 单独出现的 \n 转成 \r\n（已有 \r\n 不重复加）：真实终端需 CRLF 才会回车到行首。
     */
    private static class CrlfFilterOutputStream extends FilterOutputStream {
        private boolean lastWasCr;

        CrlfFilterOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            if (b == '\n' && !lastWasCr) {
                super.write('\r');
                super.write('\n');
            } else {
                super.write(b);
            }
            lastWasCr = (b == '\r');
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) {
                write(buf[off + i] & 0xff);
            }
        }
    }
}
