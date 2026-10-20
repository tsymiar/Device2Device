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

    /**
     * 起视频播放：native 侧用 AMediaExtractor + AMediaCodec 硬解，解码器把帧直出到
     * 这个 Surface（TextureView 的）。播放跑在 native 后台线程上，调完立即返回。
     *
     * @return 0 起播成功，负数失败（-1 正在播 / -3 拿不到 window / -7 没有视频轨 …）
     */
    public static native int startVideoRender(Surface surface, String filePath);

    /** 停掉视频播放并收掉解码器与 window */
    public static native void stopVideoRender();

}
