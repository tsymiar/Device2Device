//
// Created by Shenyrion on 2022/5/2.
//

#ifndef DEVICE2DEVICE_EGLSHADER_H
#define DEVICE2DEVICE_EGLSHADER_H

#include <GLES2/gl2.h>

/**
 * 这几个是跨文件共享的 GL 资源句柄，必须是"一份定义 + extern 声明"。
 *
 * 必须是一份定义 + extern 声明：写成 static 的话每个 include 的 .cpp 各拿到一份副本，
 * 一边生成的纹理 ID 另一边读不到，就变成 glBindTexture(GL_TEXTURE_2D, 0)。
 */
extern GLuint g_Texture2D[3];
extern GLuint g_vertexPosBuffer;
extern GLuint g_texturePosBuffer;

namespace EglShader {
    GLuint CreateProgram(const char *pVertexShaderSource, const char *pFragShaderSource, GLuint &vertexShaderHandle, GLuint &fragShaderHandle);
    void DeleteProgram(GLuint &program);
    GLuint GetShaderProgram();
}

#endif //DEVICE2DEVICE_EGLSHADER_H
