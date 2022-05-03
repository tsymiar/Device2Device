package com.tsymiar.devidroid.wrapper;

import android.graphics.SurfaceTexture;

public class ViewWrapper {

    static {
        System.loadLibrary("jniComm");
    }
    public static native void updateEglRender(SurfaceTexture tex, String file);

    public static native void updateTextureFile(SurfaceTexture tex, String file);

    public static native void updateTextureView(SurfaceTexture tex, int item);
}
