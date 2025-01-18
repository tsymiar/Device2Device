//
// Created by Shenyrion on 2022/5/2.
//

#ifndef DEVICE2DEVICE_EGLSHADER_H
#define DEVICE2DEVICE_EGLSHADER_H

#include <GLES2/gl2.h>

namespace EglShader {
    GLuint CreateProgram(const char *pVertexShaderSource, const char *pFragShaderSource, GLuint &vertexShaderHandle, GLuint &fragShaderHandle);
    void DeleteProgram(GLuint &program);
    GLuint GetShaderProgram();
}

#endif //DEVICE2DEVICE_EGLSHADER_H
