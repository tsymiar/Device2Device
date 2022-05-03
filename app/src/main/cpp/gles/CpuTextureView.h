#ifndef DEVIDROID_CPUTEXTUREVIEW_H
#define DEVIDROID_CPUTEXTUREVIEW_H

#include <jni.h>

namespace CpuTextureView {
    int loadSurfaceView(JNIEnv *env, jobject texture);
    void drawRGBColor(uint32_t color);
    void releaseSurfaceView(JNIEnv *env);
}

#endif //DEVIDROID_CPUTEXTUREVIEW_H
