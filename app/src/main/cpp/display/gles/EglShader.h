//
// Created by Shenyrion on 2022/5/2.
//

#ifndef DEVICE2DEVICE_EGLSHADER_H
#define DEVICE2DEVICE_EGLSHADER_H

#include <GLES2/gl2.h>

static GLuint g_Texture2D[3];
static GLuint g_vertexPosBuffer;
static GLuint g_texturePosBuffer;

namespace EglShader {
    GLuint CreateProgram(const char *pVertexShaderSource, const char *pFragShaderSource, GLuint &vertexShaderHandle, GLuint &fragShaderHandle);
    void DeleteProgram(GLuint &program);
    GLuint GetShaderProgram();
}

#endif //DEVICE2DEVICE_EGLSHADER_H
