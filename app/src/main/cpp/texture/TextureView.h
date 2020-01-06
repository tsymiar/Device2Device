#ifndef DEVIDROID_TEXTUREVIEW_H
#define DEVIDROID_TEXTUREVIEW_H

#include <jni.h>
#include <android/native_window.h>

namespace TextureView {

    int loadSurfaceView(JNIEnv *env);

    ANativeWindow *updateTextureWindow(JNIEnv *env, jobject texture, jint selection);

    void drawRGBColor(uint32_t color);

}

#endif //DEVIDROID_TEXTUREVIEW_H
