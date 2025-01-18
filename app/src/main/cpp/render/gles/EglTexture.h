//
// Created by Shenyrion on 2022/5/26.
//

#ifndef DEVICE2DEVICE_EGLTEXTURE_H
#define DEVICE2DEVICE_EGLTEXTURE_H

#include "EglShader.h"

const GLfloat g_Vertices[] = {
        -1.0f,  1.0f,
        -1.0f, -1.0f,
        1.0f,  1.0f,
        1.0f,  1.0f,
        -1.0f, -1.0f,
        1.0f, -1.0f,
};

const GLfloat g_TexCoord[] = {
        0.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f,
};

#define Y 0
#define U 1
#define V 2

namespace EglTexture {
    void SetTextureBuffers(GLuint glProgram);
};

#endif //DEVICE2DEVICE_EGLTEXTURE_H
