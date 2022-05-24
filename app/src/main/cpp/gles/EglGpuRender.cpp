//
// Created by Shenyrion on 2022/5/2.
//

#include "EglGpuRender.h"

#ifndef LOG_TAG
#define LOG_TAG "EglGpuRender"
#endif
#include <Utils/logging.h>
#include <cerrno>
#include <utils/statics.h>
#include <files/FileUtils.h>
#include <unistd.h>
#include <message/Message.h>
#include "EglShader.h"
#include "decode/Yuv2Rgb.h"

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
        ANativeWindow_setBuffersGeometry(::g_nativeWindow, 0, 0, format);
        if (EGL2.eglConfig == nullptr) {
            LOGE("OpenGL config is null");
            return nullptr;
        }
        EGL2.eglSurface = eglCreateWindowSurface(EGL2.eglDisplay, EGL2.eglConfig,
                                                 ::g_nativeWindow,
                                                 nullptr);
        if (EGL2.eglSurface == nullptr) {
            Message::instance().setMessage("ERROR creating OpenGL Window surface!", LOG_VIEW);
            return nullptr;
        }
    }
    return ::g_nativeWindow;
}

void EglGpuRender::CloseGLSurface()
{
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
        LOGE("eglDestroySurface failure.");
    }
    success = eglTerminate(EGL2.eglDisplay);
    if (!success) {
        LOGE("eglDestroySurface failure.");
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
    if (!eglMakeCurrent(EGL2.eglDisplay, EGL2.eglSurface, EGL2.eglSurface, EGL2.eglContext)) {
        LOGE("Failed to attach eglContext!");
        return -2;
    }
    //编译着色器代码并链接到着色器程序
    EGL2.glProgram = EglShader::CreateProgram((char*)vShaderStr, (char*)fShaderStr, g_vertexShader, g_fragmentShader);
    // Store the program object

    // Get the attribute locations
    EGL2.positionLoc = glGetAttribLocation(EGL2.glProgram , "v_position");

    glGenTextures(1, &EGL2.mTextureID);
    glBindTexture(GL_TEXTURE_2D, EGL2.mTextureID);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glPixelStorei(GL_UNPACK_ALIGNMENT, GL_ONE);
    glEnable(GL_TEXTURE_2D);

    // Set the viewport
    // glViewport(0, 0, EGL2.width, EGL2.height);
    // Use the program object
    glUseProgram(EGL2.glProgram);
    // Clear the color buffer
    glClear(GL_COLOR_BUFFER_BIT);

    return 0;
}

void setUniforms(int uTextureUnitLocation, int textureId) {
    // Pass the matrix into the shader program.
    //glUniformMatrix4fv(uMatrixLocation, 1, false, matrix);

    // Set the active texture unit to texture unit 0.
    glActiveTexture(GL_TEXTURE2);

    // Bind the texture to this unit.
    glBindTexture(GL_TEXTURE_2D, textureId);

    // Tell the texture uniform sampler to use this texture in the shader by
    // telling it to read from texture unit 0.
    glUniform1i(uTextureUnitLocation, 0);
}

void pixelRender(unsigned char* pixel, size_t)
{
    // RGB format needed: pixel
    glTexImage2D(GL_TEXTURE_2D,
                 0, GL_RGB,
                 EGL2.width, EGL2.height,
                 0, GL_RGB,
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

    GLfloat vVertices[] = { 0.0f, 0.5f, 0.0f, -0.5f, -0.5f, 0.0f, 0.5f, -0.5f, 0.0f };
    // Order of coordinates: X, Y, S, T
    // Triangle Fan
    GLfloat VERTEX_DATA[] = { 0.0f, 0.0f, 0.5f, 0.5f,
                              -1.0f, -1.0f, 0.0f, 1.0f,
                              1.0f, -1.0f, 1.0f, 1.0f,
                              1.0f, 1.0f, 1.0f, 0.0f,
                              -1.0f, 1.0f, 0.0f, 0.0f,
                              -1.0f, -1.0f, 0.0f, 1.0f };

    glVertexAttribPointer(aPositionLocation, POSITION_COMPONENT_COUNT,
                          GL_FLOAT, false, STRIDE_NUMBER,
                          VERTEX_DATA);
    glEnableVertexAttribArray(aPositionLocation);

    glVertexAttribPointer(aTextureCoordinatesLocation, POSITION_COMPONENT_COUNT,
                          GL_FLOAT, false, STRIDE_NUMBER,
                          &VERTEX_DATA[POSITION_COMPONENT_COUNT]);
    glEnableVertexAttribArray(aTextureCoordinatesLocation);
    // glDrawArrays(GL_TRIANGLE_FAN, 0, 6);
    glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, nullptr);

    eglSwapBuffers(EGL2.eglDisplay, EGL2.eglSurface);
}

void EglGpuRender::RenderSurface(uint8_t *pixel, size_t len)
{
    if (EGL2.quit) {
        glDisable(GL_TEXTURE_2D);
        glDeleteTextures(1, &EGL2.mTextureID);
        glDeleteProgram(EGL2.glProgram);
        return;
    }

    if (EGL2.pause) {
        return;
    }

    auto *data = new unsigned char[len];
    Yuv2Rgb::convertYUV420SPToARGB8888(reinterpret_cast<char *>(pixel),
                                       (int)(EGL2.height * 2),
                                       (int)(EGL2.width / 4),
                                       data);
    pixelRender(data, len);
    // Statics::printBuffer((char*)data, len);
    delete[] data;
    usleep(1000);
}

/**
 * 定点着色器gl,sl
 */
#define GET_STR(x) #x

static const char *vertexShader = GET_STR(
        attribute
        vec4 aPosition; //顶点坐标
        attribute
        vec2 aTexCoord; //材质顶点坐标
        varying
        vec2 vTexCoord; //输出的纹理坐标
        void main()
        {
            vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
            gl_Position = aPosition;
        }
);

//片元着色器,软解码和部分x86硬解码
static const char *fragYUV420P = GET_STR(
        precision
        mediump float;      //精度
        varying
        vec2 vTexCoord;     //顶点着色器传递的坐标
        uniform
        sampler2D yTexture; //输入的材质（不透明灰度，单像素）
        uniform
        sampler2D uTexture;
        uniform
        sampler2D vTexture;
        void main()
        {
            vec3 yuv;
            vec3 rgb;
            yuv.r = texture2D(yTexture, vTexCoord).r;
            yuv.g = texture2D(uTexture, vTexCoord).r - 0.5;
            yuv.b = texture2D(vTexture, vTexCoord).r - 0.5;
            rgb = mat3(1.0, 1.0, 1.0,
                       0.0, -0.39465, 2.03211,
                       1.13983, -0.58060, 0.0) * yuv;
            //输出像素颜色
            gl_FragColor = vec4(rgb, 1.0);
        }
);

GLint compileShader(const char *code, GLenum type)
{
    GLint shader = glCreateShader(type);
    if (shader == 0) {
        LOGE("glCreateShader %d failed", type);
        return 0;
    }
    //加载shader
    glShaderSource(static_cast<GLuint>(shader),
                   1, // shader数量
                   &code,
                   nullptr // 代码长度
    );

    //编译shader
    glCompileShader(static_cast<GLuint>(shader));

    //获取编译情况
    GLint status;
    glGetShaderiv(static_cast<GLuint>(shader), GL_COMPILE_STATUS, &status);
    if (status == 0) {
        LOGE("glCompileShader %d failed", type);
        return 0;
    }
    LOGI("glCompileShader %d success", type);
    return shader;
}

/**
 * Draw by OpenGL.
 *
 * @param color Color to draw (ARGB).
 */
int EglGpuRender::DrawRGBTexture(const char* filename)
{
    // -*-*-*-*-*-*- OpenGL rendering -*-*-*-*-*-*-
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
    // Note the dot after divide, the division has to be floating-point
    glClearColor(
            0xff000000 / 4294967296.f,
            0x00ff0000 / 16777216.f,
            0x0000ff00 / 65536.f,
            0x000000ff / 256.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glFinish();
    /* If you move eglMakeCurrent(EGL::context) to initialization, eglMakeCurrent(EGL_NO_CONTEXT)
     * should go to de-initialization. Neither eglDestroyContext nor eglTerminate disconnects the
     * surface, only marks it for deletion when it's disconnected. */
    // auto contextCurrentGuard = guard([=]{ eglMakeCurrent(EGL2.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); });

    //定点shader初始化
    GLuint vuShader = static_cast<GLuint>(compileShader(vertexShader, GL_VERTEX_SHADER));
    //片元yuv420 shader初始化
    GLuint fuShader = static_cast<GLuint>(compileShader(fragYUV420P, GL_FRAGMENT_SHADER));
    //创建渲染程序
    GLuint program = glCreateProgram();
    if (program == 0) {
        LOGE("glCreateProgram failed!");
        return -3;
    }
    //渲染程序中加入着色器代码
    glAttachShader(program, vuShader);
    glAttachShader(program, fuShader);
    //链接程序
    glLinkProgram(program);
    GLint status = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &status);
    if (status != GL_TRUE) {
        LOGE("glLinkProgram failed!");
        return -4;
    }
    glUseProgram(program);
    LOGI("glLinkProgram success!");
    // 加入三维顶点数据 两个三角形组成正方形
    // 定点坐标系描述了OpenGL的绘制范围，以绘制中心为原点,定点坐标系就是OpenGL的绘制区间
    // 在2D图形下，左边界为到x -1，右边界到x 1，上边界到y 1, 下边界到y -1
    // 3D下同样道理。
    static float vers[] = {
            1.0f, -1.0f, 0.0f,  //右下
            -1.0f, -1.0f, 0.0f,  //左下
            1.0f,  1.0f, 0.0f,  //右上
            -1.0f,  1.0f, 0.0f,  //左上
    };
    GLuint attr = (GLuint) glGetAttribLocation(program, "aPosition");
    glEnableVertexAttribArray(attr);
    // 传递顶点
    // 取3个数据,跳转12个字节位(3个数据)再取另外3个数据，这是实现块状数据存储的关键
    // 很多函数里都有这个参数，通常写作int stride
    glVertexAttribPointer(attr, 3, GL_FLOAT, GL_FALSE, 12, vers);
    // 加入纹理坐标数据
    // 纹理坐标的坐标系以纹理左下角为坐标原点，向右为x正轴方向，向上为y轴正轴方向。总长度是1。
    // 纹理图片的四个角的坐标分别是：(0,0)、(1,0)、(0,1)、(1,1)，分别对应左下、右下、左上、右上四个顶点。
    static float txts[] = {
            1.0f, 0.0f, //右下
            0.0f, 0.0f, //左下
            1.0f, 1.0f, //右上
            0.0, 1.0    //左上
    };
    GLuint atex = (GLuint) glGetAttribLocation(program, "aTexCoord");
    glEnableVertexAttribArray(atex);
    glVertexAttribPointer(atex, 2, GL_FLOAT, GL_FALSE, 8, txts);
    LOGI("glVertexAttribPointer success");
    //材质纹理初始化
    //纹理第1层
    glUniform1i(glGetUniformLocation(program, "yTexture"), 0);
    //纹理第2层
    glUniform1i(glGetUniformLocation(program, "uTexture"), 1);
    //纹理第3层
    glUniform1i(glGetUniformLocation(program, "vTexture"), 2);
    //创建OpenGL纹理
    GLuint texts[3] = {0};
    //在纹理资源使用完毕后(一般是程序退出或场景转换时)，一定要删除纹理对象，释放资源。
    //glDeleteTextures(Count:Integer;TexObj:Pointer);
    glGenTextures(3, texts);
    //绑定纹理1。
    glBindTexture(GL_TEXTURE_2D, texts[0]);
    /**
     * 设置缩小滤镜
     * @param 1、第一个参数表明是针对何种纹理进行设置
     * @param 2、第二个参数表示要设置放大滤镜还是缩小滤镜
     *
     * 在纹理映射的过程中，如果图元的大小不等于纹理的大小，OpenGL便会对纹理进行缩放以适应图元的尺寸。
     * 可以通过设置纹理滤镜来决定OpenGL对某个纹理采用的放大、缩小的算法。
     *
     * @param 3、第三个参数表示使用的滤镜
     *
     * 参数可选项如下：
     * GL_NEAREST     取最邻近像素
     * GL_LINEAR      线性内部插值
     * GL_NEAREST_MIPMAP_NEAREST    最近多贴图等级的最邻近像素
     * GL_NEAREST_MIPMAP_LINEAR     在最近多贴图等级的内部线性插值
     * GL_LINEAR_MIPMAP_NEAREST     在最近多贴图等级的外部线性插值
     * GL_LINEAR_MIPMAP_LINEAR      在最近多贴图等级的外部和内部线性插值
     *
     * 多贴图纹理(Mip Mapping)为一个纹理对象生成不同尺寸的图像。在需要时，根据绘制图形的大小来决定采用的纹理
     * 等级或者在不同的纹理等级之间进行线性内插。使用多贴图纹理的好处在于消除纹理躁动。这种情况在所绘制的景物
     * 离观察者较远时常常发生(如图6.6-1和6.6-2)。由于多贴图纹理现在的渲染速度已经很快，以至于和普通纹理没有
     * 什么区别，我们现在一般都使用多贴图纹理。
     *
     */
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    //设置放大滤镜
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    //glTexImage2D函数将Pixels数组中的像素值传给当前绑定的纹理对象，于是便创建了纹理，Pixels是最后一个参数
    //该函数的功能是，根据指定的参数，生成一个2D纹理（Texture）
    glTexImage2D(
            //纹理的类型
            GL_TEXTURE_2D,
            //纹理的等级 0默认 级的分辨率最大
            0,
            //gpu内部格式 亮度，灰度图
            GL_LUMINANCE,
            //纹理图像的宽度和高度 拉升到全屏
            EGL2.width, EGL2.height,
            //边框大小
            0,
            //像素数据的格式 亮度，灰度图 要与上面一致
            GL_LUMINANCE,
            //像素值的数据类型
            GL_UNSIGNED_BYTE,
            //纹理的数据(像素数据)
            NULL
    );
    LOGI("EGL2 size [%d, %d]", EGL2.height, EGL2.width);
    //绑定纹理2。
    glBindTexture(GL_TEXTURE_2D, texts[1]);
    //调用glTexParameter设置纹理滤镜
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    //glTexImage2D函数将Pixels数组中的像素值传给当前绑定的纹理对象，于是便创建了纹理，Pixels是最后一个参数
    glTexImage2D(
            //纹理的类型
            GL_TEXTURE_2D,
            //纹理的等级 0默认 级的分辨率最大
            0,
            //gpu内部格式 亮度，灰度图
            GL_LUMINANCE,
            //纹理图像的宽度和高度 拉升到全屏
            EGL2.width, EGL2.height,
            //边框大小
            0,
            //像素数据的格式 亮度，灰度图 要与上面一致
            GL_LUMINANCE,
            //像素值的数据类型
            GL_UNSIGNED_BYTE,
            //纹理的数据(像素数据)
            NULL
    );
    //绑定纹理3。
    glBindTexture(GL_TEXTURE_2D, texts[2]);
    //缩小的过滤器
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    //glTexImage2D函数将Pixels数组中的像素值传给当前绑定的纹理对象，于是便创建了纹理，Pixels是最后一个参数
    glTexImage2D(
            //纹理的类型
            GL_TEXTURE_2D,
            //纹理的等级 0默认 级的分辨率最大
            0,
            //gpu内部格式 亮度，灰度图
            GL_LUMINANCE,
            //纹理图像的宽度和高度 拉升到全屏
            EGL2.width, EGL2.height,
            //边框大小
            0,
            //像素数据的格式 亮度，灰度图 要与上面一致
            GL_LUMINANCE,
            //像素值的数据类型
            GL_UNSIGNED_BYTE,
            //纹理的数据(像素数据)
            NULL
    );
    LOGI("glTexImage2D success");
    /**************************纹理设置********************************************/
    unsigned char *pixel[3] = {nullptr};
    long fullSize = EGL2.width * EGL2.height;

    pixel[0] = new unsigned char[fullSize];
    pixel[1] = new unsigned char[fullSize / 4];
    pixel[2] = new unsigned char[fullSize / 4];
    memset(*pixel, '\0', fullSize);
    memset(*(pixel + 1) , '\0', fullSize / 4);
    memset(*(pixel + 2), '\0', fullSize / 4);

    FILE *file = fopen(filename, "rbe");
    if (file == nullptr) {
        LOGE("Get file content fail, file = nullptr");
        return -5;
    }
    fseek(file, 0, SEEK_END); //定位到文件末
    long fileSize = ftell(file);
    fseek(file, 0, SEEK_SET);

    long pictureSize = fileSize / fullSize;
    if (pictureSize < 1) pictureSize = 1;
    auto* rgba = new unsigned char[pictureSize];
    memset(rgba, '\0', pictureSize);
    fread(rgba, pictureSize, 1, file);

    for (long i = 0; i < pictureSize; i++) {
        if (i % 4 == 0) {
            *(pixel[2] + i / 4) = rgba[i];
        }
        if (i % 2 == 0) {
            *(pixel[1] + i / 2) = rgba[i];
        }
        *(pixel[0] + i) = rgba[i];
//        if (*pixel + i == nullptr) {
//            continue;
//        }
        memset(pixel[0], rgba[i], fullSize);
        memset(pixel[1], rgba[i], fullSize / 4);
        memset(pixel[2], rgba[i], fullSize / 4);
        //420 yy uu vv
        if (feof(file) == 0) {
            fread(pixel[0], 1, fullSize, file);
            fread(pixel[1], 1, fullSize / 4, file);
            fread(pixel[2], 1, fullSize / 4, file);
        } else {
            break;
        }

        //激活第1层纹理,绑定到创建的OpenGL纹理
        glActiveTexture(GL_TEXTURE0);
        // glBindTexture可以创建或使用一个已命名的纹理
        // 将target设置为GL_TEXTURE_1D、GL_TEXTURE_2D、GL_TEXTURE_3D或GL_TEXTURE_CUBE_MAP
        // 并将texture设置为要绑定的新纹理的名称，即可将纹理名绑定至当前活动纹理单元目标
        // 当一个纹理与目标绑定时，该目标之前的绑定关系将自动被打破
        glBindTexture(GL_TEXTURE_2D, texts[0]);
        //替换纹理内容，将data指针（buf[0]）指向的图片的部分作为2D纹理，在程序中只要不断改变data指向的图片就能自动更新纹理
        /**
         *
            @brief：提供修改图像的功能。因为修改一个纹理比重新创建一个纹理开销小很多。
            对于一些视频捕捉程序可以先将视频图像存储在更大的初始图像中(图像大小需要是2^n,OpenGL2.0后没有这个限制)
            创建一个渲染用的纹理,然后反复调用glTexSubImage2D(修改的图像区域不用是2^n)函数从视频图像区域读取到渲染纹理图像中,渲染用的纹理图像只需要创建一次即可。
            @func：glTexSubImage2D (GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const GLvoid *pixels)
            @param target:必须是glCopyTexImage2D中对应的target可用值
            @param level:mipmap等级
            @param xoffset,yoffset是要修改的图像左上角偏移,
            @param width,height是要修改的图像宽高像素修改的范围在原图之外并不受影响
            @param format,type:表示图像的数据格式和类型
            @param pixels:子图像的纹理数据
         */
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, EGL2.width, EGL2.height, GL_LUMINANCE, GL_UNSIGNED_BYTE,
                        pixel[0]);
        //激活第2层纹理
        glActiveTexture(GL_TEXTURE0 + 1);
        glBindTexture(GL_TEXTURE_2D, texts[1]);
        //替换纹理内容
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, EGL2.width / 2, EGL2.height / 2, GL_LUMINANCE,
                        GL_UNSIGNED_BYTE, pixel[1]);

        //激活第3层纹理
        glActiveTexture(GL_TEXTURE0 + 2);
        glBindTexture(GL_TEXTURE_2D, texts[2]);
        //替换纹理内容
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, EGL2.width / 2, EGL2.height / 2, GL_LUMINANCE,
                        GL_UNSIGNED_BYTE, pixel[2]);
        //三维绘制
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        EGLBoolean stat = eglSwapBuffers(EGL2.eglDisplay, EGL2.eglSurface);
        if (stat == EGL_FALSE) {
            LOGE("Draws %08x status EGL_FALSE", *pixel[i]);
        } else {
            LOGD("Draws %08x, remain = %d, using OpenGL [%d, %d]", pixel[i], pictureSize - i, EGL2.height, EGL2.width);
        }
        usleep(100);
    }
    fclose(file);
    delete[] rgba;
    delete *pixel;
    return 0;
}
