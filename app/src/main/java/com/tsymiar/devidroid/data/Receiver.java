package com.tsymiar.devidroid.data;

public class Receiver {
    public static final int UDP_SERVER = 0;
    public static final int UDP_CLIENT = 1;
    public static final int KAI_SUBSCRIBE = 2;
    public static final int KAI_PUBLISHER = 3;
    public int receiver;
    public String message;
    public Receiver() {
        message = null;
    }
}
