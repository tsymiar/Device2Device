package com.tsymiar.device2device.wrapper;

public class FileWrapper {
    static {
        System.loadLibrary("jniComm");
    }

    public static native int convertAudioFiles(String from, String save);
}
