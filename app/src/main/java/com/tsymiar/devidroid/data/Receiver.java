package com.tsymiar.devidroid.data;

public class Receiver {
    public static final int MESSAGE = 0;
    public static final int ERROR = 1;
    public static final int UDP_SERVER = 2;
    public static final int UDP_CLIENT = 3;
    public static final int KAI_SUBSCRIBE = 4;
    public static final int KAI_PUBLISHER = 5;
    public int receiver;
    public String message;
    public Receiver() {
        message = null;
    }
}
