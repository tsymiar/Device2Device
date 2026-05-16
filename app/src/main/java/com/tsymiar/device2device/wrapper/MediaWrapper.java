package com.tsymiar.device2device.wrapper;

public class MediaWrapper {
    static {
        System.loadLibrary("jniComm");
    }

    // 音频转换
    public static native int convertAudioFiles(String from, String save);
}
