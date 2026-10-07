#ifndef DEVICE2DEVICE_CPURENDERVIEW_H
#define DEVICE2DEVICE_CPURENDERVIEW_H

#include <jni.h>

namespace CpuRenderView {
    int setupSurfaceView(JNIEnv *env, jobject texture);

    void releaseSurfaceView(JNIEnv *env);

    void setDisplaySize(int height, int width);

    void drawRGBColor(uint32_t color, const char *filename = nullptr);

    void drawSurface(uint8_t *data, size_t size = 0);

    /** 直接把一整块 RGBA8888 画上去：JNI 层解完 BMP 走这条，省得再绕 drawSurface 猜尺寸 */
    void drawRgba(const uint8_t *rgba, int width, int height, size_t bytes);
}

#endif //DEVICE2DEVICE_CPURENDERVIEW_H
