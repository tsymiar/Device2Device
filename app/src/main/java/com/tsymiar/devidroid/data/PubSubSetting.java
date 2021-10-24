package com.tsymiar.devidroid.data;

public class PubSubSetting {
    static private final PubSubSetting setting = new PubSubSetting();

    private String addr = "";
    private int port = 0;
    private String topic = "";

    public static int getPort() {
        return setting.port;
    }

    public static String getAddr() {
        return setting.addr;
    }

    public static String getTopic() {
        return setting.topic;
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

    public static PubSubSetting getSetting() {
        return setting;
    }

    @Override
    public String toString() {
        return "PubSubSetting {" +
                "ip='" + setting.addr + '\'' +
                ", port=" + setting.port +
                ", topic='" + setting.topic + '\'' +
                '}';
    }
}
