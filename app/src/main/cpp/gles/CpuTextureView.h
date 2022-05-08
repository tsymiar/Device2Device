#ifndef DEVIDROID_CPUTEXTUREVIEW_H
#define DEVIDROID_CPUTEXTUREVIEW_H

#include <jni.h>

namespace CpuTextureView {
    int setupSurfaceView(JNIEnv *env, jobject texture);

    void releaseSurfaceView(JNIEnv *env);

    void setDisplaySize(int height, int width);

    void drawRGBColor(uint32_t color);

    void drawPicture(const char* data);
}

#endif //DEVIDROID_CPUTEXTUREVIEW_H
