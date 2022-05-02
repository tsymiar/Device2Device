//
// Created by Shenyrion on 2022/5/2.
//

#include "EglSurface.h"

#ifndef LOG_TAG
#define LOG_TAG "EglSurface"
#endif
#include <Utils/logging.h>
#include <cerrno>
#include "EglShader.h"

namespace {
    GLuint g_vertexShader;
    GLuint g_fragmentShader;
    GLuint g_shaderProgram;
}

EGL2 EGL2{};
FILE *g_fileDesc = nullptr;
unsigned char *g_fileContent = nullptr;
ANativeWindow *g_nativeWindow = nullptr;

GLbyte vShaderStr[] =
        "attribute vec4 vPosition;    \n"
        "attribute vec2 a_texCoord;   \n"
        "varying vec2 tc;             \n"
        "void main()                  \n"
        "{                            \n"
        "   gl_Position = vPosition;  \n"
        "   tc = a_texCoord;          \n"
        "}                            \n";

GLbyte fShaderStr[] =
        "precision mediump float;     \n"
        "uniform sampler2D tex_y;     \n"
        "uniform sampler2D tex_u;     \n"
        "uniform sampler2D tex_v;     \n"
        "varying vec2 tc;             \n"
        "void main()                  \n"
        "{                            \n"
        "  vec4 c = vec4((texture2D(tex_y, tc).r - 16./255.) * 1.164);\n"
        "  vec4 U = vec4(texture2D(tex_u, tc).r - 128./255.);\n"
        "  vec4 V = vec4(texture2D(tex_v, tc).r - 128./255.);\n"
        "  c += V * vec4(1.596, -0.813, 0, 0);\n"
        "  c += U * vec4(0, -0.392, 2.017, 0);\n"
        "  c.a = 1.0;\n"
        "  gl_FragColor = c;\n"
        "}                  \n";

unsigned char *EglSurface::ReadGLFile(const char *filename)
{
    g_fileDesc = fopen(filename, "rbe");
    if (g_fileDesc != nullptr) {
        long size = 1280 * 720 * 3 / 2;
        g_fileContent = new unsigned char[size];
        memset(g_fileContent, '\0', size);
        fread(g_fileContent, size, 1, g_fileDesc);
    } else {
        char msg[128];
        char text[] = "'%s' open failed:\n%s!";
        sprintf(msg, text, filename, strerror(errno));
        LOGE("%s", msg);
        return nullptr;
    }
    return g_fileContent;
}

ANativeWindow *EglSurface::InitGLSurface()
{
    // Display and config need to be initialized only once
    if (EGL2.display == nullptr) {
        EGL2.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        EGLBoolean status = eglInitialize(EGL2.display, nullptr, nullptr);
        if (EGL2.display == nullptr || !status) {
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
            if (!eglChooseConfig(EGL2.display, attrib, &EGL2.config, 1, &number) ||
                number != 1) {
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
        EGL2.context = eglCreateContext(EGL2.display, EGL2.config, nullptr, attrs);
        if (EGL2.context == nullptr) {
            LOGE("Failed to create OpenGL context");
            return nullptr;
        }
    }
    EGLint format;
    if (!eglGetConfigAttrib(EGL2.display, EGL2.config, EGL_NATIVE_VISUAL_ID, &format)) {
        LOGE("eglGetConfigAttrib returned error %d.", eglGetError());
        return nullptr;
    }
    ANativeWindow_setBuffersGeometry(::g_nativeWindow, 0, 0, format);
    EGL2.surface = eglCreateWindowSurface(EGL2.display, EGL2.config,
                                           ::g_nativeWindow,
                                           nullptr);
    if (EGL2.surface == nullptr) {
        LOGE("Failed to create OpenGL Window surface.");
        return nullptr;
    } else {
        return g_nativeWindow;
    }
}

void EglSurface::CloseGLSurface()
{
    EGLBoolean success = eglDestroySurface(EGL2.display, EGL2.surface);
    if (!success) {
        LOGE("eglDestroySurface failure.");
    }
    success = eglDestroyContext(EGL2.display, EGL2.context);
    if (!success) {
        LOGE("eglDestroySurface failure.");
    }
    success = eglTerminate(EGL2.display);
    if (!success) {
        LOGE("eglDestroySurface failure.");
    }
    EGL2.surface = nullptr;
    EGL2.context = nullptr;
    EGL2.display = nullptr;
}

void EglSurface::EglRenderLoop()
{
    eglMakeCurrent(EGL2.display, EGL2.surface, EGL2.surface, EGL2.context);
}

void EglSurface::MakeGLTexture(unsigned char *data)
{
    //编译着色器代码并链接到着色器程序
    g_shaderProgram = EglShader::CreateProgram((char*)vShaderStr, (char*)fShaderStr, g_vertexShader, g_fragmentShader);
    // Use the program object
    glUseProgram(g_shaderProgram);

    GLuint texYId;
    GLuint texUId;
    GLuint texVId;

    int width = 640;
    int height = 480;

    glGenTextures(1, &texYId);
    glBindTexture(GL_TEXTURE_2D, texYId);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, width, height, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE,
                 data);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenTextures(1, &texUId);
    glBindTexture(GL_TEXTURE_2D, texUId);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, width / 2, height / 2, 0, GL_LUMINANCE,
                 GL_UNSIGNED_BYTE, data + width * height);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenTextures(1, &texVId);
    glBindTexture(GL_TEXTURE_2D, texVId);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, width / 2, height / 2, 0, GL_LUMINANCE,
                 GL_UNSIGNED_BYTE, data + width * height * 5 / 4);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}
