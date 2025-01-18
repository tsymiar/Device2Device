#include "CpuRenderView.h"

#include <cerrno>
#include <cstring>
#include <cstdlib>

#include <android/native_window.h>
#include <android/native_window_jni.h>

#ifndef LOG_TAG
#define LOG_TAG "CpuTextureView"
#endif
#include <utils/logging.h>
#include <message/Message.h>
#include <utils/statics.h>
#include <binfile/bitmap.h>

extern ANativeWindow *g_nativeWindow;

/** Classes and methods from JNI. */
namespace JNI {
    /** android.view.Surface class */
    static jclass surface_class;
    /** android.view.Surface constructor */
    static jmethodID surface_init;
    /** android.view.Surface.release() */
    static jmethodID surface_release;
    /** The surface and its native window. */
    static jobject surface_view{};
}

/**
 * Create surface for surface texture.
 *
 * @param env JNI environment.
 * @param texture Surface texture to create surface for.
 */
void createSurface(JNIEnv *env, jobject texture)
{
    jvalue params[1];
    params[0].l = texture;
    auto surface = env->NewObjectA(JNI::surface_class, JNI::surface_init, params);
    if (surface == nullptr) {
        LOGE("Failed to construct surface");
        return;
    }
    JNI::surface_view = env->NewGlobalRef(surface);
}

/**
 * Release surface.
 *
 * @param env JNI environment.
 */
void CpuRenderView::releaseSurfaceView(JNIEnv *env)
{
    if (JNI::surface_view != nullptr) {
        env->CallVoidMethod(JNI::surface_view, JNI::surface_release);
        env->DeleteGlobalRef(JNI::surface_view);
        JNI::surface_view = nullptr;
    }
    if (g_nativeWindow != nullptr) {
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
    }
}

void rebuildTexture(JNIEnv *env, jobject texture)
{
    /* Releasing the surface is extremely important. You can't initialize OpenGL on the same
     * surface which was used for CPU rendering as there is no way how to de-initialize CPU
     * rendering on a surface (OpenGL can be disconnected with eglMakeCurrent(EGL_NO_CONTEXT)).
     * So each time you want to switch, you need to create a new surface for the surface
     * texture, but to be able to do so, you need to release the original surface first. */
    CpuRenderView::releaseSurfaceView(env);
    createSurface(env, texture);
}

int CpuRenderView::setupSurfaceView(JNIEnv *env, jobject texture)
{
    jobject surface = env->FindClass("android/view/Surface");
    if (surface == nullptr) {
        LOGE("Surface class can not be found");
        return 0;
    } else {
        jobject global = env->NewGlobalRef(surface);
        JNI::surface_class = reinterpret_cast<jclass>(global);
        if (JNI::surface_class == nullptr) {
            LOGE("Surface reference cast with error");
            return 0;
        }
    }

    JNI::surface_init = env->GetMethodID(JNI::surface_class, "<init>",
                                         "(Landroid/graphics/SurfaceTexture;)V");
    if (JNI::surface_init == nullptr) {
        LOGE("SurfaceTexture constructor got fail");
        return 0;
    }

    JNI::surface_release = env->GetMethodID(JNI::surface_class, "release", "()V");
    if (JNI::surface_release == nullptr) {
        LOGE("Surface.release() method got fail");
        return 0;
    }
    rebuildTexture(env, texture);
    if (JNI::surface_view == nullptr) {
        LOGE("No surface");
        return -1;
    }
    g_nativeWindow = ANativeWindow_fromSurface(env, JNI::surface_view);
    if (g_nativeWindow == nullptr) {
        LOGE("Failed to obtain window");
        return -1;
    }

    ANativeWindow_acquire(g_nativeWindow);

    return env->GetVersion();
}

void setRGBValue(uint32_t rgba, void* bits) {
    // Locked bounds can be larger than requested, we should check them
    auto *dest = static_cast<uint8_t *>(bits);
    // Value in color is ARGB but the surface expects RGBA
    dest[0] = (rgba >> 16) & 0xFF;
    dest[1] = (rgba >> 8) & 0xFF;
    dest[2] = (rgba >> 0) & 0xFF;
    dest[3] = (rgba >> 24) & 0xFF;
}

void setFrameBuffer(ANativeWindow_Buffer *buf, uint8_t *src) {
    // src is either null: to blank the screen
    //     or holding exact pixels with the same fmt [stride is the SAME]
    auto *dst = reinterpret_cast<uint8_t *> (buf->bits);
    uint32_t bpp;
    switch (buf->format) {
        case WINDOW_FORMAT_RGB_565:
            bpp = 2;
            break;
        case WINDOW_FORMAT_RGBA_8888:
        case WINDOW_FORMAT_RGBX_8888:
            bpp = 4;
            break;
        default:
            return;
    }
    uint32_t stride, width;
    stride = buf->stride * bpp;
    width = buf->width * bpp;
    if (src) {
        for (auto height = 0; height < buf->height; ++height) {
            memcpy(dst, src, width);
            dst += stride, src += width;
        }
    } else {
        for (auto height = 0; height < buf->height; ++height) {
            memset(dst, 0, width);
            dst += stride;
        }
    }
}

/**
 * Draw by CPU.
 *
 * @param color Color to draw (ARGB).
 */
void CpuRenderView::drawRGBColor(uint32_t color, const char *filename) {
    if (g_nativeWindow == nullptr) {
        LOGE("NativeWindow nullptr error");
        return;
    }

    BITMAPPROP imgProp = {1,1,0};
    unsigned char *data = nullptr;
    if (filename != nullptr && (imgProp = BitmapToRgba(filename, &data)).blSize <= 0) {
        LOGE("bitmap content invalid");
        return;
    }
    LOGD("imgSize = [%d]x[%d]: %p", imgProp.biWidth, imgProp.biHeight, data);
    // -*-*-*-*-*-*- CPU rendering -*-*-*-*-*-*-
    // For our example, scale the surface to 1×1 pixel and fill it with a color
    auto ret = ANativeWindow_setBuffersGeometry(g_nativeWindow, imgProp.biWidth, imgProp.biHeight,
                                                // AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM
                                                WINDOW_FORMAT_RGBA_8888
    );
    if (ret != 0) {
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
        LOGE("Failed to set buffers geometry");
        return;
    }

    ANativeWindow_Buffer buffer;
    ARect bounds{0, 0, 1, 1};
    ret = ANativeWindow_lock(g_nativeWindow, &buffer, &bounds);
    if (ret != 0) {
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
        std::string hint = "Native window may busy";
        Message::instance().setMessage(hint, TOAST);
        LOGE("%s", hint.c_str());
        return;
    }

    if (filename == nullptr) {
        setRGBValue(color, buffer.bits);
    } else {
        setFrameBuffer(&buffer, data);
    }

    if (ANativeWindow_unlockAndPost(g_nativeWindow) != 0) {
        LOGE("Unable to unlock and post to native window");
    }
    LOGD("Draws %08x using Native Window", color);
    if (data != nullptr) {
        free(data);
    }
}

void CpuRenderView::setDisplaySize(int height, int width)
{
    if (width < 0 || height < 0) {
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
        LOGE("Display size was not set");
        Message::instance().setMessage("Window display bounds fail!", TOAST);
        return;
    }

    int32_t result = ANativeWindow_setBuffersGeometry(g_nativeWindow, width, height,
                                                      AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
    if (result < 0) {
        LOGE("Unable to set buffers geometry");
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
        return;
    }

    ANativeWindow_acquire(g_nativeWindow);
}

void CpuRenderView::drawSurface(uint8_t *data, size_t size)
{
    if (g_nativeWindow == nullptr) {
        LOGE("NativeWindow nullptr error");
        return;
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(g_nativeWindow, &buffer, nullptr) < 0) {
        Message::instance().setMessage("ERROR locking native window fail!", LOG_VIEW);
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
        return;
    }

    auto *pixes = (uint8_t *) data;
    auto *line = (uint8_t *) buffer.bits;
    if (size > 0) {
        memcpy(line, data, size);
    } else {
        for (int y = 0; y < buffer.height; y++) {
            for (int x = 0; x < buffer.width; x++) {
                line[x] = pixes[buffer.height * y + x]; // fixme crash
            }
            line += buffer.stride;
        }
    }

    if (ANativeWindow_unlockAndPost(g_nativeWindow) < 0) {
        LOGE("Unable to unlock and post to native window");
    }
}
