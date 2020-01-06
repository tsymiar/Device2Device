#include "TextureView.h"

#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>

#ifndef LOG_TAG
#define LOG_TAG "SurfaceTexture"
#endif

#include <common/logger.h>

/** Classes and methods from JNI. */
namespace JNI {
    /** android.view.Surface class */
    static jclass surface_class;
    /** android.view.Surface constructor */
    static jmethodID surface_init;
    /** android.view.Surface.release() */
    static jmethodID surface_release;
}

/** OpenGL stuff. */
namespace EGL2 {
    /** OpenGL display. There's only one, the default. */
    static EGLDisplay display = nullptr;
    /** Configuration. We want OpenGL ES2 with RGBA. */
    static EGLConfig config = nullptr;
    /** OpenGL drawing context. Each TextureView has its own. */
    static EGLContext context = nullptr;
    /** Surface to draw to. */
    static EGLSurface surface = nullptr;
}

/** The surface and its native window. */
jobject g_surfaceView = {};
ANativeWindow *g_nativeWindow = nullptr;

/**
 * Initialize window from the surface.
 *
 * @param env JNI environment.
 */
int initWindow(JNIEnv *env)
{
    if (::g_surfaceView == NULL) {
        LOGE("No surface");
        return -1;
    }

    ::g_nativeWindow = ANativeWindow_fromSurface(env, ::g_surfaceView);
    if (::g_nativeWindow == NULL) {
        LOGE("Failed to obtain window");
        return -1;
    }
    return 0;
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
    ::g_surfaceView = env->NewGlobalRef(surface);
}

/**
 * Release surface.
 *
 * @param env JNI environment.
 */
void releaseSurface(JNIEnv *env)
{
    env->CallVoidMethod(::g_surfaceView, JNI::surface_release);
    env->DeleteGlobalRef(::g_surfaceView);
    ::g_surfaceView = nullptr;
}

int TextureView::loadSurfaceView(JNIEnv *env)
{
    jobject surface = env->FindClass("android/view/Surface");
    if (surface == nullptr) {
        LOGE("Surface class can not be found");
        return 0;
    } else {
        jobject global = env->NewGlobalRef(surface);
        JNI::surface_class = static_cast<jclass>(global);
        if(JNI::surface_class == nullptr) {
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
    return env->GetVersion();
}

/**
 * Draw.
 *
 * @param color Color to draw (ARGB).
 */
void TextureView::drawRGBColor(uint32_t color)
{
    if (EGL2::surface == nullptr) {
        // -*-*-*-*-*-*- CPU rendering -*-*-*-*-*-*-

        // For our example, scale the surface to 1×1 pixel and fill it with a color
        auto ret = ANativeWindow_setBuffersGeometry(::g_nativeWindow, 1, 1,
                                                    AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
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

        uint32_t *buffer = static_cast<uint32_t *>(surface.bits);
        // Value in color is ARGB but the surface expects RGBA
        buffer[0] = color >> 16;
        buffer[1] = color >> 8;
        buffer[2] = color >> 0;
        buffer[3] = color >> 24;

        ret = ANativeWindow_unlockAndPost(g_nativeWindow);
        if (ret != 0) {
            LOGE("Failed to post");
            return;
        }
        LOGD("Drawn %08x using Native Window", color);
        return;
    }

    // -*-*-*-*-*-*- OpenGL rendering -*-*-*-*-*-*-

    /* As we have only one surface on this thread, eglMakeCurrent can be called in initialization
     * but if you would want to draw multiple surfaces on the same thread, you need to change
     * current context and the easiest way to keep track of the current surface is to change it on
     * each draw so that's what is shown here. Each thread has its own current context and one
     * context cannot be current on multiple threads at the same time. */
    if (!eglMakeCurrent(EGL2::display, EGL2::surface, EGL2::surface, EGL2::context)) {
        LOGE("Failed to attach context");
        return;
    }
    /* If you move eglMakeCurrent(EGL::context) to initialization, eglMakeCurrent(EGL_NO_CONTEXT)
     * should go to de-initialization. Neither eglDestroyContext nor eglTerminate disconnects the
     * surface, only marks it for deletion when it's disconnected. */
    //auto contextCurrentGuard = guard([=]{ eglMakeCurrent(EGL::display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); });

    // Note the dot after divide, the division has to be floating-point
    glClearColor(
            (color & 0x00ff0000) / 16777216.f,
            (color & 0x0000ff00) / 65536.f,
            (color & 0x000000ff) / 256.f,
            (color & 0xff000000) / 4294967296.f);
    glClear(GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(EGL2::display, EGL2::surface);
    LOGD("Drawn %08x using OpenGL", color);
}

ANativeWindow *TextureView::updateTextureWindow(JNIEnv *env, jobject texture, jint selection)
{
    if (::g_nativeWindow == nullptr && selection == 0)
        // Shortcut, don't log
        return nullptr;

    // Tear down
    if (::g_nativeWindow != nullptr) {
        drawRGBColor(0x0); // Clear the surface

        if (EGL2::surface != nullptr)
            eglDestroySurface(EGL2::display, EGL2::surface);
        EGL2::surface = nullptr;

        if (EGL2::context != nullptr)
            eglDestroyContext(EGL2::display, EGL2::context);
        EGL2::context = nullptr;

        ANativeWindow_release(::g_nativeWindow);
        ::g_nativeWindow = nullptr;
    }

    if (::g_surfaceView != nullptr)
        /* Releasing the surface is extremely important. You can't initialize OpenGL on the same
         * surface which was used for CPU rendering as there is no way how to de-initialize CPU
         * rendering on a surface (OpenGL can be disconnected with eglMakeCurrent(EGL_NO_CONTEXT)).
         * So each time you want to switch, you need to create a new surface for the surface
         * texture, but to be able to do so, you need to release the original surface first. */
        releaseSurface(env);

    if (selection == 0) {
        // No implementation selected
        LOGE("De-initialized");
        return nullptr;
    }

    createSurface(env, texture);
    if (initWindow(env) != 0)
        return nullptr;

    if (selection == 1 /* CPU rendering */) {
        LOGD("CPU rendering initialized");
    } else if (selection == 2 /* OpenGL */) {
        // Display and config need to be initialized only once
        if (EGL2::display == nullptr) {
            EGL2::display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
            eglInitialize(EGL2::display, nullptr, nullptr);
            if (EGL2::display == nullptr) {
                LOGE("Failed to initialize OpenGL display");
                return nullptr;
            }

            {
                // We want to use OpenGL ES2 with RGBA
                EGLint attrs[] = {
                        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                        EGL_RED_SIZE, 8,
                        EGL_GREEN_SIZE, 8,
                        EGL_BLUE_SIZE, 8,
                        EGL_ALPHA_SIZE, 8,
                        EGL_NONE
                };
                int num;
                if (!eglChooseConfig(EGL2::display, attrs, &EGL2::config, 1, &num) || num != 1) {
                    LOGE("No OpenGL display config chosen");
                    return nullptr;
                }
            }
        }

        {
            EGLint attrs[] =
                    {
                            EGL_CONTEXT_CLIENT_VERSION, 2,
                            EGL_NONE
                    };
            EGL2::context = eglCreateContext(EGL2::display, EGL2::config, nullptr, attrs);
            if (EGL2::context == nullptr) {
                LOGE("Failed to create OpenGL context");
                return nullptr;
            }
        }

        EGL2::surface = eglCreateWindowSurface(EGL2::display, EGL2::config, ::g_nativeWindow,
                                               nullptr);
        if (EGL2::surface == nullptr) {
            LOGE("Failed to create OpenGL surface");
            return nullptr;
        }
        LOGD("OpenGL initialized");
    }
    if (::g_surfaceView == nullptr) {
        LOGE("No engine initialized");
        return nullptr;
    }
    return ::g_nativeWindow;
}
