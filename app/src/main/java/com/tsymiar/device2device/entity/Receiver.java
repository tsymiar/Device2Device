package com.tsymiar.device2device.entity;

/**
 * 消息类型：取值必须与 C++ 侧 enum MASSAGER 的声明顺序一致，
 * native 是把枚举直接转成 int 塞进 receiver 字段的（JniMethods.cpp），改一边就得改另一边。
 */
public class Receiver {
    public static final int MSG_STAT = 0;
    public static final int TOAST = 1;
    public static final int TEXTURE = 2;
    public static final int KAI_SUBSCRIBE = 3;
    public static final int KAI_PUBLISHER = 4;
    public static final int FILE_PROGRESS = 5;
    public static final int MSG_HINT = 6;
    public static final int UDP_SERVER = 7;
    public static final int UDP_CLIENT = 8;
    public static final int KCP_VIEW = 9;
    public static final int KCP_HINT = 10;
    public static final int KCP_CLIENT = 11;
    public int receiver;
    public String message;
    public Receiver() {
        message = null;
    }
}
