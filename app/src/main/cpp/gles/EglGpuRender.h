//
// Created by Shenyrion on 2022/5/2.
//

#ifndef DEVIDROID_EGLGPURENDER_H
#define DEVIDROID_EGLGPURENDER_H

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <EGL/eglext.h>
#include <android/native_window.h>

/** OpenGL stuff. */
struct EGL2 {
    /** OpenGL drawing context. Each TextureView has its own. */
    EGLContext eglContext = nullptr;
    /** OpenGL display. There's only one, the default. */
    EGLDisplay eglDisplay = nullptr;
    /** Surface to draw to. */
    EGLSurface eglSurface = nullptr;
    /** Configuration. We want OpenGL ES2 with RGBA. */
    EGLConfig eglConfig = nullptr;

    EGLint eglFormat;
    GLuint mTextureID;
    GLuint glProgram;
    GLint positionLoc;

    GLuint width;
    GLuint height;

    GLboolean quit;
    GLboolean pause;
};

struct VideoFrame {
    uint8_t *data;
    int width;
    int height;
    float position;
    int format;
};

typedef int (*GetTextureCallback)(VideoFrame** texture, void* ctx);

namespace EglGpuRender {
    ANativeWindow *OpenGLSurface();

    void CloseGLSurface();

    void RenderSurface(uint8_t *pixel, size_t len);

    void SetWindowSize(int height, int width);

    int MakeGLTexture();

    int DrawRGBTexture(const char* filename);

    void FrameRender(unsigned char* frameData, size_t);
}

#endif //DEVIDROID_EGLGPURENDER_H
