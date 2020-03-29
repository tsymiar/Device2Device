#ifndef DEVIDROID_TEXTUREVIEW_H
#define DEVIDROID_TEXTUREVIEW_H

#include <jni.h>
#include <android/native_window.h>

namespace TextureView {

    int loadSurfaceView(JNIEnv *env, jobject texture);

    ANativeWindow *initOpenGL(const char* filename);

    void drawRGBColor(uint32_t color);

    int drawRGBColor(size_t height, size_t width);

}

#endif //DEVIDROID_TEXTUREVIEW_H
