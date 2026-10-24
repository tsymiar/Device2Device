package com.tsymiar.device2device.entity;

/**
 * 消息类型常量：由 tools/gen_receiver_java.py 依 C++ Message.h 的 enum MASSAGER 生成，请勿手改。
 * 取值必须与 C++ 声明顺序一致（native 直接传 int）；改 C++ 后重跑脚本即可（build.sh 打包前自动跑）。
 */
public class Receiver {
    public static final int MSG_STAT = 0;
    public static final int TOAST = 1;
    public static final int TEXTURE = 2;
    public static final int SUBSCRIBER = 3;
    public static final int PUBLISHER = 4;
    public static final int FILE_PROGRESS = 5;
    public static final int MSG_HINT = 6;
    public static final int UDP_SERVER = 7;
    public static final int UDP_CLIENT = 8;
    public static final int KCP_VIEW = 9;
    public static final int KCP_HINT = 10;
    public static final int KCP_CLIENT = 11;
    public static final int LAN_PEER = 12;
    public static final int LAN_MESSAGE = 13;
    public int receiver;
    public String message;

    public Receiver() {
        message = null;
    }
}
