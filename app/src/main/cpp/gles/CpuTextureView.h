#ifndef DEVIDROID_CPUTEXTUREVIEW_H
#define DEVIDROID_CPUTEXTUREVIEW_H

#include <jni.h>

namespace CpuTextureView {
    int setupSurfaceView(JNIEnv *env, jobject texture);

    void releaseSurfaceView(JNIEnv *env);

    void setDisplaySize(int height, int width);

    void drawRGBColor(uint32_t color, const char *filename = nullptr);

    void drawSurface(uint8_t *data, size_t size = 0);
}

#endif //DEVIDROID_CPUTEXTUREVIEW_H
