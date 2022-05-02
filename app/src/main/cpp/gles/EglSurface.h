//
// Created by Shenyrion on 2022/5/2.
//

#ifndef DEVIDROID_EGLSURFACE_H
#define DEVIDROID_EGLSURFACE_H

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <EGL/eglext.h>
#include <android/native_window.h>

/** OpenGL stuff. */
struct EGL2 {
    /** Configuration. We want OpenGL ES2 with RGBA. */
    EGLConfig config = nullptr;
    /** OpenGL drawing context. Each TextureView has its own. */
    EGLContext context = nullptr;
    /** OpenGL display. There's only one, the default. */
    EGLDisplay display = nullptr;
    /** Surface to draw to. */
    EGLSurface surface = nullptr;
};

struct VideoFrame {
    uint8_t *data;
    int width;
    int height;
    float position;
    int format;
};

typedef int (*GetTextureCallback)(VideoFrame** texture, void* ctx);

namespace EglSurface {
    ANativeWindow *InitGLSurface();
    void CloseGLSurface();
    unsigned char *ReadGLFile(const char *filename);
    void MakeGLTexture(unsigned char *data);
    void EglRenderLoop();
}

#endif //DEVIDROID_EGLSURFACE_H
