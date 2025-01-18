#ifndef DEVICE2DEVICE_CPURENDERVIEW_H
#define DEVICE2DEVICE_CPURENDERVIEW_H

#include <jni.h>

namespace CpuRenderView {
    int setupSurfaceView(JNIEnv *env, jobject texture);

    void releaseSurfaceView(JNIEnv *env);

    void setDisplaySize(int height, int width);

    void drawRGBColor(uint32_t color, const char *filename = nullptr);

    void drawSurface(uint8_t *data, size_t size = 0);
}

#endif //DEVICE2DEVICE_CPURENDERVIEW_H
