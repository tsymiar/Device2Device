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
#include <bitmap/bitmap.h>

extern ANativeWindow *g_nativeWindow;

/** Classes and methods from JNI. */
namespace JNI {
    /** android.view.Surface class */
    static jclass surface_class = nullptr;
    /** android.view.Surface constructor */
    static jmethodID surface_init = nullptr;
    /** android.view.Surface.release() */
    static jmethodID surface_release = nullptr;
    /** The surface and its native window. */
    static jobject surface_view{};
}

namespace {
    /**
     * 最近一次设置的源图尺寸。
     *
     * drawSurface() 只拿到一个数据指针，而 window buffer 的尺寸由控件决定，
     * 两边不一样时得知道源图多大才缩得动，所以记在这里（setDisplaySize / drawRgba 更新）。
     */
    int s_srcWidth = 0;
    int s_srcHeight = 0;

    /** 返回 0 表示这个像素格式我们不认 */
    uint32_t bytesPerPixel(int format)
    {
        switch (format) {
            case WINDOW_FORMAT_RGB_565:
                return 2;
            case WINDOW_FORMAT_RGBA_8888:
            case WINDOW_FORMAT_RGBX_8888:
                return 4;
            default:
                return 0;
        }
    }

    /**
     * 源是 RGBA8888，按最近邻缩放到目标 buffer。
     *
     * buffer.stride 以像素计且可能比 width 大（行末有 padding），
     * 所以目标行步进要 stride*bpp，不能想当然用 width*bpp。
     */
    void blitScaled(ANativeWindow_Buffer *buf, uint32_t bpp,
                    const uint8_t *src, int srcW, int srcH)
    {
        const int dstW = buf->width;
        const int dstH = buf->height;
        const size_t dstStride = (size_t) buf->stride * bpp;
        auto *dst = static_cast<uint8_t *>(buf->bits);

        for (int y = 0; y < dstH; y++) {
            const int sy = (srcH > 0) ? (y * srcH / dstH) : 0;
            const uint8_t *srow = src + (size_t) sy * (size_t) srcW * 4;
            uint8_t *drow = dst + (size_t) y * dstStride;
            if (bpp == 4 && srcW == dstW) {
                memcpy(drow, srow, (size_t) dstW * 4);
                continue;
            }
            for (int x = 0; x < dstW; x++) {
                const int sx = (srcW > 0) ? (x * srcW / dstW) : 0;
                const uint8_t *p = srow + (size_t) sx * 4;
                if (bpp == 4) {
                    drow[x * 4 + 0] = p[0];
                    drow[x * 4 + 1] = p[1];
                    drow[x * 4 + 2] = p[2];
                    drow[x * 4 + 3] = 0xFF;   // 目标可能是 RGBX，alpha 不写死 0
                } else {                      // RGB_565
                    auto v = (uint16_t) (((p[0] >> 3) << 11) | ((p[1] >> 2) << 5) | (p[2] >> 3));
                    reinterpret_cast<uint16_t *>(drow)[x] = v;
                }
            }
        }
    }

    /**
     * 源是裸二进制（视频/测试帧，尺寸未知）：按可用字节逐行平铺，不够的补 0。
     *
     * 源数据字节数未知，按实际可用字节逐行铺，不够的补 0，不越过缓冲末尾。
     */
    void blitRaw(ANativeWindow_Buffer *buf, uint32_t bpp,
                 const uint8_t *src, size_t srcBytes)
    {
        const int dstW = buf->width;
        const int dstH = buf->height;
        const size_t dstStride = (size_t) buf->stride * bpp;
        auto *dst = static_cast<uint8_t *>(buf->bits);
        size_t used = 0;

        for (int y = 0; y < dstH; y++) {
            uint8_t *drow = dst + (size_t) y * dstStride;
            const size_t want = (size_t) dstW * bpp;
            size_t chunk = 0;
            if (used < srcBytes) {
                chunk = (srcBytes - used < want) ? (srcBytes - used) : want;
                memcpy(drow, src + used, chunk);
                used += chunk;
            }
            if (chunk < want) {
                memset(drow + chunk, 0, want - chunk);
            }
        }
    }
}

/**
 * Find the Surface class and cache it.
 *
 * class 引用只建一次并复用：每次渲染都 NewGlobalRef 一个新的，
 * 反复切换渲染方式会把 JNI 全局引用表撑满。
 */
bool lookupSurfaceClass(JNIEnv *env)
{
    if (JNI::surface_class != nullptr) {
        return true;
    }
    jclass local = env->FindClass("android/view/Surface");
    if (local == nullptr) {
        LOGE("Surface class can not be found");
        return false;
    }
    jobject global = env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    if (global == nullptr) {
        LOGE("Surface reference cast with error");
        return false;
    }
    JNI::surface_class = reinterpret_cast<jclass>(global);
    JNI::surface_init = env->GetMethodID(JNI::surface_class, "<init>",
                                         "(Landroid/graphics/SurfaceTexture;)V");
    JNI::surface_release = env->GetMethodID(JNI::surface_class, "release", "()V");
    if (JNI::surface_init == nullptr || JNI::surface_release == nullptr) {
        LOGE("Surface ctor/release method got fail");
        env->DeleteGlobalRef(JNI::surface_class);
        JNI::surface_class = nullptr;
        return false;
    }
    return true;
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
    if (env == nullptr) {
        return;
    }
    if (JNI::surface_view != nullptr) {
        env->CallVoidMethod(JNI::surface_view, JNI::surface_release);
        env->DeleteGlobalRef(JNI::surface_view);
        JNI::surface_view = nullptr;
    }
    if (g_nativeWindow != nullptr) {
        // ANativeWindow_fromSurface() 返回的窗口已经带一份引用，这里释放这一份就够：
        // 多 acquire 一份就少释放一份，窗口不真正归还，下次 lock 拿 "Native window may busy"。
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
    }
    s_srcWidth = 0;
    s_srcHeight = 0;
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
    if (env == nullptr || texture == nullptr) {
        LOGE("setupSurfaceView: env=%p, texture=%p", env, texture);
        return -1;
    }
    if (!lookupSurfaceClass(env)) {
        return -1;
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
    return 1;
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

/** 把整块 buffer 涂成一个颜色（没有图片时的纯色刷屏） */
void fillSolidColor(ANativeWindow_Buffer *buf, uint32_t bpp, uint32_t rgba)
{
    const size_t dstStride = (size_t) buf->stride * bpp;
    auto *dst = static_cast<uint8_t *>(buf->bits);
    uint8_t pixel[4];
    setRGBValue(rgba, pixel);
    for (int y = 0; y < buf->height; y++) {
        uint8_t *drow = dst + (size_t) y * dstStride;
        for (int x = 0; x < buf->width; x++) {
            if (bpp == 4) {
                drow[x * 4 + 0] = pixel[0];
                drow[x * 4 + 1] = pixel[1];
                drow[x * 4 + 2] = pixel[2];
                drow[x * 4 + 3] = 0xFF;
            } else {
                auto v = (uint16_t) (((pixel[0] >> 3) << 11) | ((pixel[1] >> 2) << 5) |
                                     (pixel[2] >> 3));
                reinterpret_cast<uint16_t *>(drow)[x] = v;
            }
        }
    }
}

/**
 * Draw by CPU.
 *
 * @param color Color to draw (ARGB).
 */
void CpuRenderView::drawRGBColor(uint32_t color, const char* filename)
{
    if (g_nativeWindow == nullptr) {
        LOGE("NativeWindow nullptr error");
        return;
    }

    unsigned char *data = nullptr;
    BITMAPPROP imgProp{ 0, 0, 0 };
    if (filename != nullptr) {
        imgProp = BitmapToRgba(filename, &data);
        if (imgProp.blSize <= 0 || data == nullptr) {
            // 解不出来就别往下画，否则贴上去的是一整块透明的黑
            LOGE("decode [%s] failed, code = %ld", filename, imgProp.blSize);
            free(data);
            Message::instance().setMessage("Not a 24/32-bit BMP file", TOAST);
            return;
        }
    }
    LOGD("imgSize = [%u]x[%u]: %p", imgProp.biWidth, imgProp.biHeight, data);

    int wantW = (filename != nullptr) ? (int) imgProp.biWidth : 1;
    int wantH = (filename != nullptr) ? (int) imgProp.biHeight : 1;
    // -*-*-*-*-*-*- CPU rendering -*-*-*-*-*-*-
    auto ret = ANativeWindow_setBuffersGeometry(g_nativeWindow, wantW, wantH,
            // AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM
                                                WINDOW_FORMAT_RGBA_8888
    );
    if (ret != 0) {
        LOGE("Failed to set buffers geometry");
        free(data);
        return;
    }

    ANativeWindow_Buffer buffer;
    // bounds 传 nullptr 锁整块：传 {0,0,1,1} 只锁住左上角 1×1，
    // 整张图就只画了一个像素上去。
    ret = ANativeWindow_lock(g_nativeWindow, &buffer, nullptr);
    if (ret != 0) {
        std::string hint = "Native window may busy";
        Message::instance().setMessage(hint, TOAST);
        LOGE("%s", hint.c_str());
        free(data);
        return;
    }

    uint32_t bpp = bytesPerPixel(buffer.format);
    if (bpp == 0) {
        LOGE("unsupported window format %d", buffer.format);
    } else if (filename == nullptr) {
        fillSolidColor(&buffer, bpp, color);
    } else {
        blitScaled(&buffer, bpp, data, (int) imgProp.biWidth, (int) imgProp.biHeight);
    }

    if (ANativeWindow_unlockAndPost(g_nativeWindow) != 0) {
        LOGE("Unable to unlock and post to native window");
    }
    LOGD("Draws %08x using Native Window", color);
    free(data);
}

void CpuRenderView::setDisplaySize(int height, int width)
{
    if (g_nativeWindow == nullptr) {
        LOGE("native window is null, display size skipped");
        Message::instance().setMessage("Window display bounds fail!", TOAST);
        return;
    }
    if (width <= 0 || height <= 0) {
        LOGE("Display size was not set [%d]x[%d]", width, height);
        Message::instance().setMessage("Window display bounds fail!", TOAST);
        return;
    }
    s_srcWidth = width;
    s_srcHeight = height;

    int32_t result = ANativeWindow_setBuffersGeometry(g_nativeWindow, width, height,
                                                      AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
    if (result < 0) {
        LOGE("Unable to set buffers geometry");
        return;
    }
}

void CpuRenderView::drawSurface(uint8_t *data, size_t size)
{
    if (g_nativeWindow == nullptr) {
        LOGE("NativeWindow nullptr error");
        return;
    }
    if (data == nullptr) {
        LOGE("drawSurface: data is null");
        return;
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(g_nativeWindow, &buffer, nullptr) < 0) {
        // 锁不上只是一次失败，窗口留着下次再试：
        // 置空之后这一路就再也画不出来了，后面每次只得到一句 "NativeWindow nullptr error"。
        Message::instance().setMessage("ERROR locking native window fail!", TEXTURE);
        return;
    }

    uint32_t bpp = bytesPerPixel(buffer.format);
    if (bpp == 0) {
        LOGE("unsupported window format %d", buffer.format);
    } else if (s_srcWidth > 0 && s_srcHeight > 0 &&
               size >= (size_t) s_srcWidth * (size_t) s_srcHeight * 4) {
        blitScaled(&buffer, bpp, data, s_srcWidth, s_srcHeight);
    } else {
        blitRaw(&buffer, bpp, data, size);
    }

    if (ANativeWindow_unlockAndPost(g_nativeWindow) < 0) {
        LOGE("Unable to unlock and post to native window");
    }
}

void CpuRenderView::drawRgba(const uint8_t *rgba, int width, int height, size_t bytes)
{
    if (rgba == nullptr || width <= 0 || height <= 0) {
        LOGE("drawRgba: bad argument [%dx%d] %p", width, height, rgba);
        return;
    }
    if (bytes < (size_t) width * (size_t) height * 4) {
        LOGE("drawRgba: buffer %zu bytes < %dx%d RGBA", bytes, width, height);
        return;
    }
    s_srcWidth = width;
    s_srcHeight = height;
    drawSurface(const_cast<uint8_t *>(rgba), bytes);
}
