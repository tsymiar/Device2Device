package com.tsymiar.device2device.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.tsymiar.device2device.entity.SshConnection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** SSH 会话列表持久化：全部配置放在一个 JSON 数组里，读写原子化。 */
public final class SshSessionStore {
    private static final String PREFS = "ssh_sessions";
    private static final String KEY_LIST = "list";
    private static final String KEY_LAST = "last_id";

    private SshSessionStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<SshConnection> all(Context ctx) {
        List<SshConnection> result = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_LIST, null);
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                SshConnection c = SshConnection.fromJson(arr.optJSONObject(i));
                if (c != null && !c.id.isEmpty()) {
                    result.add(c);
                }
            }
        } catch (JSONException ignored) {
            // 数据坏了就当空列表，不让整个 SSH 功能打不开
        }
        return result;
    }

    public static SshConnection get(Context ctx, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (SshConnection c : all(ctx)) {
            if (id.equals(c.id)) {
                return c;
            }
        }
        return null;
    }

    /** 新建或按 id 覆盖 */
    public static void save(Context ctx, SshConnection conn) {
        if (conn == null) {
            return;
        }
        List<SshConnection> list = all(ctx);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (conn.id.equals(list.get(i).id)) {
                list.set(i, conn);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(conn);
        }
        writeList(ctx, list);
    }

    public static void remove(Context ctx, String id) {
        List<SshConnection> list = all(ctx);
        boolean changed = list.removeIf(c -> id != null && id.equals(c.id));
        if (changed) {
            writeList(ctx, list);
        }
    }

    public static String lastId(Context ctx) {
        return prefs(ctx).getString(KEY_LAST, "");
    }

    public static void setLastId(Context ctx, String id) {
        prefs(ctx).edit().putString(KEY_LAST, id == null ? "" : id).apply();
    }

    private static void writeList(Context ctx, List<SshConnection> list) {
        JSONArray arr = new JSONArray();
        for (SshConnection c : list) {
            try {
                arr.put(c.toJson());
            } catch (JSONException ignored) {
                // 单条异常跳过，不影响其他会话
            }
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply();
    }
}
