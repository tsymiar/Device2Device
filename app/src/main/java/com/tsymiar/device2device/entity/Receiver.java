package com.tsymiar.device2device.entity;

/**
 * 消息类型：取值必须与 C++ 侧 enum MASSAGER 的声明顺序一致，
 * native 是把枚举直接转成 int 塞进 receiver 字段的（JniMethods.cpp），改一边就得改另一边。
 */
public class Receiver {
    public static final int MESSAGE = 0;
    public static final int TOAST = 1;
    public static final int TEXTURE = 2;
    public static final int KAI_SUBSCRIBE = 3;
    public static final int KAI_PUBLISHER = 4;
    public static final int KCP_VIEW = 5;
    public static final int FILE_PROGRESS = 6;
    public static final int MSG_HINT = 7;
    /** UDP 服务端：启动状态（第一条）与之后收到的数据（对应 MASSAGER::UDP_SERVER） */
    public static final int UDP_SERVER = 8;
    /** UDP 客户端发出去的内容（对应 MASSAGER::UDP_CLIENT） */
    public static final int UDP_CLIENT = 9;
    /** KCP 服务端收到的消息（对应 C++ 侧 MASSAGER::KCP_HINT） */
    public static final int KCP_HINT = 10;
    /** KCP 客户端的消息：启动 / 发送 / 收到的回射包（对应 C++ 侧 MASSAGER::KCP_CLIENT） */
    public static final int KCP_CLIENT = 11;
    public int receiver;
    public String message;
    public Receiver() {
        message = null;
    }
}
