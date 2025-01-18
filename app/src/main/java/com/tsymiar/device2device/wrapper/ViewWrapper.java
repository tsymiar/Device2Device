package com.tsymiar.device2device.wrapper;

import android.graphics.SurfaceTexture;

public class ViewWrapper {

    static {
        System.loadLibrary("jniComm");
    }

    public static native void unloadSurfaceView();

    public static native void setRenderSize(int height, int width);

    public static native void setLocalFile(String filename);

    public static native void updateEglTexture(SurfaceTexture tex);

    public static native void updateEglSurface(SurfaceTexture tex);

    public static native void updateCpuTexture(SurfaceTexture tex, int item);

    public static native void updateCpuSurface(SurfaceTexture tex);

}
