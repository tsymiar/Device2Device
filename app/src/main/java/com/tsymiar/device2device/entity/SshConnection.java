package com.tsymiar.device2device.entity;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * 一条 SSH 连接配置（对应 JuiceSSH 的 Connection）。
 * 存 SharedPreferences 时统一转成 JSON，字段缺失走默认值，避免旧数据/手工篡改导致崩溃。
 */
public class SshConnection {
    public static final int AUTH_PASSWORD = 0;
    public static final int AUTH_KEY = 1;

    public String id = "";
    /** 会话显示名，为空时按 user@host:port 生成 */
    public String name = "";
    /** 分组名，空串表示未分组 */
    public String group = "";
    public String host = "";
    public int port = 22;
    public String user = "";
    public int authType = AUTH_PASSWORD;
    public String password = "";
    /** 私钥文件名（位于 app 私有 ssh_keys 目录），不是完整路径 */
    public String keyFile = "";
    /** 私钥口令，可为空 */
    public String keyPassphrase = "";

    public static SshConnection create() {
        SshConnection c = new SshConnection();
        c.id = UUID.randomUUID().toString();
        return c;
    }

    public String displayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        String u = user == null ? "" : user;
        String h = host == null ? "" : host;
        return (u.isEmpty() ? "" : u + "@") + h + ":" + port;
    }

    public String endpoint() {
        return (host == null ? "" : host) + ":" + port;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("group", group);
        o.put("host", host);
        o.put("port", port);
        o.put("user", user);
        o.put("auth", authType);
        o.put("pass", password);
        o.put("key", keyFile);
        o.put("keypass", keyPassphrase);
        return o;
    }

    public static SshConnection fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        SshConnection c = new SshConnection();
        c.id = o.optString("id", UUID.randomUUID().toString());
        c.name = o.optString("name", "");
        c.group = o.optString("group", "");
        c.host = o.optString("host", "");
        c.port = o.optInt("port", 22);
        c.user = o.optString("user", "");
        c.authType = o.optInt("auth", AUTH_PASSWORD);
        c.password = o.optString("pass", "");
        c.keyFile = o.optString("key", "");
        c.keyPassphrase = o.optString("keypass", "");
        return c;
    }
}
