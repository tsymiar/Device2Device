package com.tsymiar.devidroid.wrapper;

public class FileWrapper {
    static {
        System.loadLibrary("jniComm");
    }

    public static native int convertAudioFiles(String from, String save);
}
