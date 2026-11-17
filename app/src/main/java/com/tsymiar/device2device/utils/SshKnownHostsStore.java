package com.tsymiar.device2device.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.PublicKey;

/**
 * 轻量 known_hosts：首次连接记住主机密钥指纹，之后不一致就拒绝（防中间人）。
 * 用 SharedPreferences 而不是标准 known_hosts 文件，省去路径权限与行解析问题。
 */
public final class SshKnownHostsStore {
    private static final String PREFS = "ssh_known_hosts";

    public enum Result { NEW, MATCH, MISMATCH }

    private SshKnownHostsStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String host, int port) {
        return host + ":" + port;
    }

    public static String fingerprint(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP);
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static Result check(Context ctx, String host, int port, String fp) {
        String saved = stored(ctx, host, port);
        if (saved == null || saved.isEmpty()) {
            prefs(ctx).edit().putString(key(host, port), fp).apply();
            return Result.NEW;
        }
        return saved.equals(fp) ? Result.MATCH : Result.MISMATCH;
    }

    public static String stored(Context ctx, String host, int port) {
        return prefs(ctx).getString(key(host, port), null);
    }

    /** 用户确认后忘记该主机：下次连接重新信任 */
    public static void forget(Context ctx, String host, int port) {
        prefs(ctx).edit().remove(key(host, port)).apply();
    }
}
