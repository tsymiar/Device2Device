//
// Created by Shenyrion on 2022/5/2.
//

#include "EglGpuRender.h"

#ifndef LOG_TAG
#define LOG_TAG "EglGpuRender"
#endif
#include <utils/logging.h>
#include <cerrno>
#include <utils/statics.h>
#include <utils/FileUtils.h>
#include <unistd.h>
#include <message/Message.h>
#include "EglShader.h"
#include "convert/Yuv2Rgb.h"
#include <bitmap/bitmap.h>
#include <new>

#define BYTES_PER_FLOAT 4
#define POSITION_COMPONENT_COUNT 2
#define TEXTURE_COORDINATES_COMPONENT_COUNT 2
#define STRIDE_NUMBER ((POSITION_COMPONENT_COUNT + TEXTURE_COORDINATES_COMPONENT_COUNT)*BYTES_PER_FLOAT)

namespace {
    GLuint g_vertexShader;
    GLuint g_fragmentShader;
}

EGL2 EGL2{};
ANativeWindow *g_nativeWindow = nullptr;

GLbyte vShaderStr[] = "attribute vec4 a_Position;                          \n"
                      "attribute vec2 a_TextureCoordinates;                \n"
                      "varying vec2 v_TextureCoordinates;                  \n"
                      "void main()                                         \n"
                      "{                                                   \n"
                      "    v_TextureCoordinates = a_TextureCoordinates;    \n"
                      "    gl_Position = a_Position;                       \n"
                      "}                                                   \n";

GLbyte fShaderStr[] =
        "precision mediump float;                                          \n"
        "uniform sampler2D u_TextureUnit;                                  \n"
        "varying vec2 v_TextureCoordinates;                                \n"
        "void main()                                                       \n"
        "{                                                                 \n"
        "    gl_FragColor = texture2D(u_TextureUnit, v_TextureCoordinates);\n"
        "}                                                                 \n";

ANativeWindow *EglGpuRender::OpenGLSurface()
{
    // Display and config need to be initialized only once
    if (EGL2.eglDisplay == nullptr) {
        EGL2.eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        EGLBoolean status = eglInitialize(EGL2.eglDisplay, nullptr, nullptr);
        if (EGL2.eglDisplay == nullptr || !status) {
            LOGE("Failed to initialize OpenGL display");
            return nullptr;
        }
        {
            // We want to use OpenGL ES2 with RGBA
            EGLint attrib[] = {
                    EGL_BUFFER_SIZE, 32,
                    EGL_ALPHA_SIZE, 8,
                    EGL_BLUE_SIZE, 8,
                    EGL_GREEN_SIZE, 8,
                    EGL_RED_SIZE, 8,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                    EGL_NONE
            };
            int number;
            if (!eglChooseConfig(EGL2.eglDisplay, attrib, &EGL2.eglConfig, 1, &number) ||
                number != 1) {
                LOGE("No OpenGL display config chosen");
                return nullptr;
            }
        }
    }
    if (EGL2.eglContext == nullptr) {
        EGLint attrs[] =
                {
                        EGL_CONTEXT_CLIENT_VERSION, 2,
                        EGL_NONE
                };
        EGL2.eglContext = eglCreateContext(EGL2.eglDisplay, EGL2.eglConfig, nullptr, attrs);
        if (EGL2.eglContext == nullptr) {
            LOGE("Failed to create OpenGL context");
            return nullptr;
        }
    }
    if (EGL2.eglSurface == nullptr) {
        EGLint format;
        if (!eglGetConfigAttrib(EGL2.eglDisplay, EGL2.eglConfig, EGL_NATIVE_VISUAL_ID, &format)) {
            LOGE("eglGetConfigAttrib returned error %d.", eglGetError());
            return nullptr;
        }
        if (::g_nativeWindow == nullptr) {
            // setupSurfaceView 没成功（surface 刚被释放 / 纹理还没就绪）时这里是 null，
            // 往下走会让 ANativeWindow_setBuffersGeometry 拿到 nullptr 直接崩。
            LOGE("native window is null, call setupSurfaceView first");
            Message::instance().setMessage("ERROR native window is null!", TEXTURE);
            return nullptr;
        }
        ANativeWindow_setBuffersGeometry(::g_nativeWindow, 0, 0, format);
        if (EGL2.eglConfig == nullptr) {
            LOGE("OpenGL config is null");
            return nullptr;
        }
        EGL2.eglSurface = eglCreateWindowSurface(EGL2.eglDisplay, EGL2.eglConfig,
                                                 ::g_nativeWindow,
                                                 nullptr);
        if (EGL2.eglSurface == nullptr) {
            Message::instance().setMessage("ERROR creating OpenGL Window surface!", TEXTURE);
            return nullptr;
        }
    }
    if (!eglMakeCurrent(EGL2.eglDisplay, EGL2.eglSurface, EGL2.eglSurface, EGL2.eglContext)) {
        LOGE("attach eglContext fail!");
        return nullptr;
    }
    return ::g_nativeWindow;
}

void EglGpuRender::CloseGLSurface()
{
    if (EGL2.eglDisplay == nullptr && EGL2.eglSurface == nullptr && EGL2.eglContext == nullptr) {
        // 已经关过了（Disconnect / 上一次渲染结束时都会调一次）：
        // 再往下走就是 eglDestroySurface(nullptr, nullptr)，部分实现上直接崩
        return;
    }
    // 释放 OpenGL 纹理资源（修复纹理泄漏）
    if (EGL2.mTextureID != 0) {
        glDeleteTextures(1, &EGL2.mTextureID);
        EGL2.mTextureID = 0;
    }
    if (g_Texture2D[0] != 0 || g_Texture2D[1] != 0 || g_Texture2D[2] != 0) {
        glDeleteTextures(3, g_Texture2D);
        g_Texture2D[0] = g_Texture2D[1] = g_Texture2D[2] = 0;
    }
    if (g_vertexPosBuffer != 0) {
        glDeleteBuffers(1, &g_vertexPosBuffer);
        g_vertexPosBuffer = 0;
    }
    if (g_texturePosBuffer != 0) {
        glDeleteBuffers(1, &g_texturePosBuffer);
        g_texturePosBuffer = 0;
    }

    EglShader::DeleteProgram(EGL2.glProgram);
    EGLBoolean success = eglReleaseThread();
    if (!success) {
        LOGE("eglReleaseThread failure.");
    }
    success = eglDestroySurface(EGL2.eglDisplay, EGL2.eglSurface);
    if (!success) {
        LOGE("eglDestroySurface failure.");
    }
    success = eglDestroyContext(EGL2.eglDisplay, EGL2.eglContext);
    if (!success) {
        LOGE("eglDestroyContext failure.");
    }
    success = eglTerminate(EGL2.eglDisplay);
    if (!success) {
        LOGE("eglTerminate failure.");
    }
    EGL2.eglSurface = nullptr;
    EGL2.eglContext = nullptr;
    EGL2.eglDisplay = nullptr;
}

void EglGpuRender::SetWindowSize(int height, int width) {
    EGL2.width = width;
    EGL2.height = height;
}

int EglGpuRender::MakeGLTexture()
{
    if (EGL2.eglSurface == nullptr) {
        LOGE("surface is nullptr");
        return -1;
    }
    //编译着色器代码并链接到着色器程序
    EGL2.glProgram = EglShader::CreateProgram((char*)vShaderStr, (char*)fShaderStr, g_vertexShader, g_fragmentShader);
    if (EGL2.glProgram == 0) {
        LOGE("CreateProgram failed");
        return -2;
    }
    // Store the program object

    // Get the attribute locations
    // 名字要对上 shader 里的 a_Position：拿到 -1 之后
    // glVertexAttribPointer(-1, ...) 只会留一条 GL_INVALID_ENUM，画面永远是空的
    EGL2.positionLoc = glGetAttribLocation(EGL2.glProgram , "a_Position");

    glGenTextures(1, &EGL2.mTextureID);
    glBindTexture(GL_TEXTURE_2D, EGL2.mTextureID);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glPixelStorei(GL_UNPACK_ALIGNMENT, GL_ONE);
    // GLES2 里没有 glEnable(GL_TEXTURE_2D)，这一句只会留下一条 GL_INVALID_ENUM

    // Use the program object
    glUseProgram(EGL2.glProgram);
    // Clear the color buffer
    glClear(GL_COLOR_BUFFER_BIT);

    return 0;
}

void setUniforms(int uTextureUnitLocation, int textureId) {
    // Set the active texture unit to texture unit 0.
    glActiveTexture(GL_TEXTURE0);

    // Bind the texture to this unit.
    glBindTexture(GL_TEXTURE_2D, textureId);

    // Tell the texture uniform sampler to use this texture in the shader by
    // telling it to read from texture unit 0.
    // active 的单元与写进 uniform 的单元必须是同一个，否则采样器读不到绑上去的纹理
    glUniform1i(uTextureUnitLocation, 0);
}

void pixelRender(unsigned char* pixel, size_t, int width, int height)
{
    if (pixel == nullptr || width <= 0 || height <= 0) {
        LOGE("pixelRender: bad argument [%dx%d] %p", width, height, pixel);
        return;
    }
    glViewport(0, 0, width, height);
    // RGBA format needed: pixel
    glTexImage2D(GL_TEXTURE_2D,
                 0, GL_RGBA,
                 width, height,
                 0, GL_RGBA,
                 GL_UNSIGNED_BYTE, pixel);
    // Retrieve uniform locations for the shader program.
    GLint uTextureUnitLocation = glGetUniformLocation(EGL2.glProgram,
                                                      "u_TextureUnit");
    setUniforms(uTextureUnitLocation, EGL2.mTextureID);

    // Retrieve attribute locations for the shader program.
    GLint aPositionLocation = glGetAttribLocation(EGL2.glProgram,
                                                  "a_Position");
    GLint aTextureCoordinatesLocation = glGetAttribLocation(
            EGL2.glProgram, "a_TextureCoordinates");

    // Order of coordinates: X, Y, S, T
    // 铺满整屏的四个角：左下 / 右下 / 左上 / 右上
    GLfloat vVertices[] = { -1.0f, -1.0f, 0.0f, 1.0f,
                             1.0f, -1.0f, 1.0f, 1.0f,
                            -1.0f,  1.0f, 0.0f, 0.0f,
                             1.0f,  1.0f, 1.0f, 0.0f };

    glVertexAttribPointer(aPositionLocation, POSITION_COMPONENT_COUNT,
                          GL_FLOAT, false, STRIDE_NUMBER,
                          vVertices);
    glEnableVertexAttribArray(aPositionLocation);

    glVertexAttribPointer(aTextureCoordinatesLocation, TEXTURE_COORDINATES_COMPONENT_COUNT,
                          GL_FLOAT, false, STRIDE_NUMBER,
                          &vVertices[POSITION_COMPONENT_COUNT]);
    glEnableVertexAttribArray(aTextureCoordinatesLocation);
    // 没有绑 ELEMENT_ARRAY_BUFFER，索引绘制没有数据来源，这里用 4 个顶点的 strip 直接画
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    eglSwapBuffers(EGL2.eglDisplay, EGL2.eglSurface);
}

void EglGpuRender::RenderSurface(uint8_t *pixel, size_t len)
{
    if (EGL2.quit) {
        glDeleteTextures(1, &EGL2.mTextureID);
        EGL2.mTextureID = 0;
        EglShader::DeleteProgram(EGL2.glProgram);
        EGL2.glProgram = 0;
        return;
    }

    if (EGL2.pause) {
        return;
    }
    if (pixel == nullptr || EGL2.width <= 0 || EGL2.height <= 0) {
        LOGE("RenderSurface: bad argument [%ux%u] %p", EGL2.width, EGL2.height, pixel);
        return;
    }
    const size_t frameSize = (size_t) EGL2.width * EGL2.height * 3 / 2;
    if (len < frameSize) {
        LOGW("RenderSurface: incomplete frame (%zu/%zu)", len, frameSize);
        return;
    }
    // 转换结果是 RGBA，输出缓冲要 w*h*4（len 是 YUV 的 1.5 倍，比它小）
    const size_t outSize = (size_t) EGL2.width * EGL2.height * 4;
    auto *data = new(std::nothrow) unsigned char[outSize];
    if (data == nullptr) {
        LOGE("RenderSurface: malloc %zu failed", outSize);
        return;
    }
    Yuv2Rgb::convertYUV420SPToARGB8888(reinterpret_cast<char *>(pixel),
                                       (int) EGL2.height,
                                       (int) EGL2.width,
                                       data);
    pixelRender(data, outSize, (int) EGL2.width, (int) EGL2.height);
    delete[] data;
}

int EglGpuRender::DrawRGBTexture(const char* filename)
{
    // -*-*-*-*-*-*- OpenGL rendering -*-*-*-*-*-*-
    //
    // 这一路是"把一张图片贴成纹理"：解码成 RGBA → GL 纹理 → 全屏 quad。
    // cpp 这边只有 bitmap.c 里的 BMP 解码器，jpg/png 由 Java 侧先转成 BMP 再传进来。
    if (EGL2.eglSurface == nullptr) {
        LOGE("surface is nullptr");
        return -1;
    }
    /* As we have only one surface on this thread, eglMakeCurrent can be called in initialization
     * but if you would want to draw multiple surfaces on the same thread, you need to change
     * current context and the easiest way to keep track of the current surface is to change it on
     * each draw so that's what is shown here. Each thread has its own current context and one
     * context cannot be current on multiple threads at the same time. */
    if (!eglMakeCurrent(EGL2.eglDisplay, EGL2.eglSurface, EGL2.eglSurface, EGL2.eglContext)) {
        LOGE("Failed to attach context");
        return -2;
    }

    unsigned char* rgba = nullptr;
    BITMAPPROP prop = BitmapToRgba(filename, &rgba);
    if (prop.blSize <= 0 || rgba == nullptr) {
        LOGE("decode [%s] failed, code = %ld", filename, prop.blSize);
        free(rgba);
        Message::instance().setMessage("Not a 24/32-bit BMP file", TOAST);
        return -5;
    }
    const int width = (int) prop.biWidth;
    const int height = (int) prop.biHeight;

    int ret = MakeGLTexture();
    if (ret != 0) {
        LOGE("MakeGLTexture failed: %d", ret);
        free(rgba);
        return -6;
    }

    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    pixelRender(rgba, (size_t) prop.blSize, width, height);

    free(rgba);
    // program 和纹理是每次现建的，用完就还回去：
    // 不回收的话反复 Re-fresh 会一路涨到 GL 报 OUT_OF_MEMORY。
    EglShader::DeleteProgram(EGL2.glProgram);
    if (EGL2.mTextureID != 0) {
        glDeleteTextures(1, &EGL2.mTextureID);
        EGL2.mTextureID = 0;
    }
    LOGI("DrawRGBTexture [%s] %dx%d done", filename, width, height);
    return 0;
}

#include "EglTexture.h"

void EglGpuRender::FrameRender(unsigned char* frameData, size_t size)
{
    if (frameData == nullptr || EGL2.width <= 0 || EGL2.height <= 0) {
        return;
    }
    if (EGL2.glProgram == 0) {
        // 没有 program 就没有可绘制的管线，glUseProgram(0) 等于什么都没画
        LOGE("FrameRender: no shader program");
        return;
    }
    const size_t ySize = (size_t) EGL2.width * EGL2.height;
    const size_t chromaSize = ySize / 4;
    const size_t frameSize = ySize + chromaSize * 2;
    if (size < frameSize) {
        // 回调里要读满 w*h*1.5，切片不够就得跳过这一帧，否则越过缓冲末尾
        LOGW("incomplete YUV frame (%zu/%zu), skip", size, frameSize);
        return;
    }
    glViewport(0, 0, EGL2.width, EGL2.height);
    glClearColor(0.8f, 0.8f, 1.0f, 1.0);
    glClear(GL_COLOR_BUFFER_BIT);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, g_Texture2D[Y]);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, EGL2.width, EGL2.height, 0, GL_LUMINANCE,
                 GL_UNSIGNED_BYTE, frameData);

    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, g_Texture2D[U]);
    // I420 排布是 Y(w*h) + U(w*h/4) + V(w*h/4)，三个平面的偏移按这个来
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, EGL2.width / 2, EGL2.height / 2, 0, GL_LUMINANCE,
                 GL_UNSIGNED_BYTE, frameData + ySize);

    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, g_Texture2D[V]);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, EGL2.width / 2, EGL2.height / 2, 0, GL_LUMINANCE,
                 GL_UNSIGNED_BYTE, frameData + ySize + chromaSize);

    glUseProgram(EGL2.glProgram);
    glBindBuffer(GL_ARRAY_BUFFER, g_vertexPosBuffer);
    GLint posLoc = glGetAttribLocation(EGL2.glProgram, "position");
    glEnableVertexAttribArray(posLoc);
    glVertexAttribPointer(posLoc, 2, GL_FLOAT, GL_FALSE, 0, 0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    glBindBuffer(GL_ARRAY_BUFFER, g_texturePosBuffer);
    GLint texcoordLoc = glGetAttribLocation(EGL2.glProgram, "texCoord");
    glEnableVertexAttribArray(texcoordLoc);
    glVertexAttribPointer(texcoordLoc, 2, GL_FLOAT, GL_FALSE, 0, 0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    glDrawArrays(GL_TRIANGLES, 0, 6);
    glDisableVertexAttribArray(posLoc);
    glDisableVertexAttribArray(texcoordLoc);

    glUseProgram(0);
    // 画完要 swap，buffer 才会提交到屏幕上
    eglSwapBuffers(EGL2.eglDisplay, EGL2.eglSurface);
}
