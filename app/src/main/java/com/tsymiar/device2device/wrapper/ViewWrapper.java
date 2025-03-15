package com.tsymiar.device2device.wrapper;

import android.graphics.SurfaceTexture;
import android.view.Surface;

public class ViewWrapper {

    static {
        System.loadLibrary("jniComm");
    }

    public static native void unloadSurfaceView();

    public static native void setRenderSize(int height, int width);

    public static native void setLocalFile(String filename);

    public static native int updateEglTexture(SurfaceTexture tex);

    public static native int updateEglSurface(SurfaceTexture tex);

    public static native int updateCpuTexture(SurfaceTexture tex, int item);

    public static native int updateCpuSurface(SurfaceTexture tex);

    public native void nativeRender(Surface surface, SurfaceTexture tex, String filePath);

}
