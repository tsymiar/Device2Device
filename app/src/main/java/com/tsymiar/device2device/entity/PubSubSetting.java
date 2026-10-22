package com.tsymiar.device2device.entity;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * 发布 / 订阅窗口上填的那几项（broker 地址、端口、topic、正文、一对一目标）。
 *
 * 同时落到 SharedPreferences：两个窗口都是悬浮窗，只存内存的话进程一退就全空，
 * 发布端于是不知道该连哪个 broker。
 */
public class PubSubSetting {
    static private final PubSubSetting setting = new PubSubSetting();

    /** 与 scadup 的 Broker::setup 默认端口一致 */
    private static final int DEFAULT_PORT = 9999;

    private static final String PREF = "pubsub_prefs";
    private static final String K_ADDR = "addr";
    private static final String K_PORT = "port";
    private static final String K_TOPIC = "topic";
    private static final String K_PAYLOAD = "payload";
    private static final String K_TARGET = "target";

    private String addr = "";
    private int port = DEFAULT_PORT;
    private String topic = "";
    private String payload = "";
    /** 一对一发布的目标：对端设备的 IP 或其订阅端 id；为空表示按 topic 广播 */
    private String target = "";

    public static int getPort() {
        return setting.port;
    }

    public static String getAddr() {
        return setting.addr;
    }

    public static String getTopic() {
        return setting.topic;
    }

    public static String getPayload() {
        return setting.payload;
    }

    public static String getTarget() {
        return setting.target;
    }

    public static void setAddr(String ip) {
        setting.addr = ip;
    }

    public static void setPort(int port) {
        setting.port = port;
    }

    public static void setTopic(String topic) {
        setting.topic = topic;
    }

    public static void setPayload(String payload) {
        setting.payload = payload;
    }

    public static void setTarget(String target) {
        setting.target = (target == null) ? "" : target;
    }

    public static PubSubSetting getSetting() {
        return setting;
    }

    /** 把上次填的一组值读回来（进程重启、或只开发布窗口时也要知道 broker 在哪） */
    public static void load(Context context) {
        if (context == null) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        setting.addr = prefs.getString(K_ADDR, setting.addr == null ? "" : setting.addr);
        setting.port = prefs.getInt(K_PORT, setting.port > 0 ? setting.port : DEFAULT_PORT);
        setting.topic = prefs.getString(K_TOPIC, setting.topic == null ? "" : setting.topic);
        setting.payload = prefs.getString(K_PAYLOAD, setting.payload == null ? "" : setting.payload);
        setting.target = prefs.getString(K_TARGET, setting.target == null ? "" : setting.target);
    }

    /** 存一份：两个窗口的 Confirm 都会走到这里 */
    public static void save(Context context) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(K_ADDR, setting.addr == null ? "" : setting.addr)
                .putInt(K_PORT, setting.port)
                .putString(K_TOPIC, setting.topic == null ? "" : setting.topic)
                .putString(K_PAYLOAD, setting.payload == null ? "" : setting.payload)
                .putString(K_TARGET, setting.target == null ? "" : setting.target)
                .apply();
    }

    @NonNull
    @Override
    public String toString() {
        return "PubSubSetting {" +
                "addr='" + addr + '\'' +
                ", port=" + port +
                ", topic='" + topic + '\'' +
                ", payload='" + payload + '\'' +
                ", target='" + target + '\'' +
                '}';
    }
}
