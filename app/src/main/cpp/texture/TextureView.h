#ifndef DEVIDROID_TEXTUREVIEW_H
#define DEVIDROID_TEXTUREVIEW_H

#include <jni.h>
#include <android/native_window.h>

struct VideoFrame {
    uint8_t *data;
    int width;
    int height;
    float position;
    int format;
};

typedef int (*GetTextureCallback)(VideoFrame** texture, void* ctx);

namespace TextureView {

    int loadSurfaceView(JNIEnv *env, jobject texture);

    ANativeWindow *initGLSurface();

    unsigned char *readGLFile(const char *filename);
    void makeGLTexture(unsigned char *data);

    void GLRenderLoop();

    void drawRGBColor(uint32_t color);

    int drawRGBColor(size_t height, size_t width);

    void Unlock();

}

#endif //DEVIDROID_TEXTUREVIEW_H
