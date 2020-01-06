package com.tsymiar.devidroid.wrapper;

import android.graphics.SurfaceTexture;

public class ViewWrapper {

    static {
        System.loadLibrary("jniComm");
    }

    public static native void updateSurfaceView(SurfaceTexture tex, int i);
}
