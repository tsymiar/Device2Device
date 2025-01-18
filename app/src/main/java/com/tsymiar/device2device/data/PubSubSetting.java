package com.tsymiar.device2device.data;

import androidx.annotation.NonNull;

public class PubSubSetting {
    static private final PubSubSetting setting = new PubSubSetting();

    private String addr = "";
    private int port = 0;
    private String topic = "";
    private String payload = "";

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
                '}';
    }
}
