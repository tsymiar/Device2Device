package com.tsymiar.device2device.entity;

public class Receiver {
    public static final int MESSAGE = 0;
    public static final int TOAST = 1;
    public static final int LOG_VIEW = 2;
    public static final int UDP_SERVER = 3;
    public static final int UDP_CLIENT = 4;
    public static final int KAI_SUBSCRIBE = 5;
    public static final int KAI_PUBLISHER = 6;
    public static final int UPDATE_VIEW = 7;
    public int receiver;
    public String message;
    public Receiver() {
        message = null;
    }
}
