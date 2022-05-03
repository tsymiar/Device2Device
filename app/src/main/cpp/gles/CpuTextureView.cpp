#include "CpuTextureView.h"

#include <cerrno>
#include <cstring>
#include <cstdlib>

#include <android/native_window.h>
#include <android/native_window_jni.h>

#ifndef LOG_TAG
#define LOG_TAG "CpuTextureView"
#endif
#include <Utils/logging.h>

extern FILE *g_fileDesc;
extern unsigned char *g_fileContent;
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
void CpuTextureView::releaseSurfaceView(JNIEnv *env)
{
    if (g_fileDesc != nullptr) {
        fclose(g_fileDesc);
        g_fileDesc = nullptr;
    }
    if (g_fileContent != nullptr) {
        delete[] g_fileContent;
        g_fileContent = nullptr;
    }
    if (JNI::surface_view != nullptr) {
        env->CallVoidMethod(JNI::surface_view, JNI::surface_release);
        env->DeleteGlobalRef(JNI::surface_view);
        JNI::surface_view = nullptr;
    }
}

void rebuildTexture(JNIEnv *env, jobject texture)
{
    if (::g_nativeWindow != nullptr) {
        ANativeWindow_release(::g_nativeWindow);
        ::g_nativeWindow = nullptr;
    }
    /* Releasing the surface is extremely important. You can't initialize OpenGL on the same
     * surface which was used for CPU rendering as there is no way how to de-initialize CPU
     * rendering on a surface (OpenGL can be disconnected with eglMakeCurrent(EGL_NO_CONTEXT)).
     * So each time you want to switch, you need to create a new surface for the surface
     * texture, but to be able to do so, you need to release the original surface first. */
    if (JNI::surface_view != nullptr) {
        CpuTextureView::releaseSurfaceView(env);
    }
    createSurface(env, texture);
}

int CpuTextureView::loadSurfaceView(JNIEnv *env, jobject texture)
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
    ::g_nativeWindow = ANativeWindow_fromSurface(env, JNI::surface_view);
    if (::g_nativeWindow == nullptr) {
        LOGE("Failed to obtain window");
        return -1;
    }
    return env->GetVersion();
}

/**
 * Draw by CPU.
 *
 * @param color Color to draw (ARGB).
 */
void CpuTextureView::drawRGBColor(uint32_t argb)
{
    if (g_nativeWindow == nullptr) {
        LOGE("g_nativeWindow nullptr error");
        return;
    }
    const int hOut = 1;
    const int wOut = 1;
    // -*-*-*-*-*-*- CPU rendering -*-*-*-*-*-*-
    // For our example, scale the surface to 1×1 pixel and fill it with a color
    auto ret = ANativeWindow_setBuffersGeometry(::g_nativeWindow, wOut, hOut,
                                                AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM
            // WINDOW_FORMAT_RGBA_8888
    );
    if (ret != 0) {
        LOGE("Failed to set buffers geometry");
        return;
    }

    ANativeWindow_Buffer surface;
    ARect bounds{0, 0, 1, 1};
    ret = ANativeWindow_lock(g_nativeWindow, &surface, &bounds);
    if (ret != 0) {
        LOGE("Failed to lock");
        return;
    }
    // TODO Locked bounds can be larger than requested, we should check them
    auto *dest = static_cast<uint32_t *>(surface.bits);
    // Value in color is ARGB but the surface expects RGBA
    dest[0] = argb >> 16;
    dest[1] = argb >> 8;
    dest[2] = argb >> 0;
    dest[3] = argb >> 24;

    if (ANativeWindow_unlockAndPost(g_nativeWindow) != 0) {
        LOGE("Failed to post window");
        return;
    }
    LOGD("Draws %08x using Native Window", argb);
}
