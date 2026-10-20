package com.tsymiar.device2device.entity;

import androidx.annotation.NonNull;

public class PubSubSetting {
    static private final PubSubSetting setting = new PubSubSetting();

    private String addr = "";
    private int port = 0;
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
