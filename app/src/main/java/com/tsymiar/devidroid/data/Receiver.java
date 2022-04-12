package com.tsymiar.devidroid.data;

public class Receiver {
    public static final int MESSAGE = 0;
    public static final int UDP_SERVER = 1;
    public static final int UDP_CLIENT = 2;
    public static final int KAI_SUBSCRIBE = 3;
    public static final int KAI_PUBLISHER = 4;
    public int receiver;
    public String message;
    public Receiver() {
        message = null;
    }
}
