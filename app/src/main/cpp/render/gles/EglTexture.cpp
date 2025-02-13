//
// Created by Shenyrion on 2022/5/26.
//

#ifndef LOG_TAG
#define LOG_TAG "EglTexture"
#endif
#include <utils/logging.h>
#include "EglTexture.h"

GLuint g_Texture2D[3];
GLuint g_vertexPosBuffer;
GLuint g_texturePosBuffer;

void EglTexture::SetTextureBuffers(GLuint glProgram)
{
    GLuint vertexPosBuffer;
    glGenBuffers(1, &vertexPosBuffer);
    if (vertexPosBuffer == 0) {
        LOGE("Failed to generate vertex position buffer");
        return;
    }
    glBindBuffer(GL_ARRAY_BUFFER, vertexPosBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(g_Vertices), g_Vertices, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    g_vertexPosBuffer = vertexPosBuffer;

    GLuint texturePosBuffer;
    glGenBuffers(1, &texturePosBuffer);
    if (texturePosBuffer == 0) {
        LOGE("Failed to generate texture position buffer");
        return;
    }
    glBindBuffer(GL_ARRAY_BUFFER, texturePosBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(g_TexCoord), g_TexCoord, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    g_texturePosBuffer = texturePosBuffer;

    glUseProgram(glProgram);
    GLuint aTexture2D[3];
    glGenTextures(3, aTexture2D);
    if (aTexture2D[0] == 0 || aTexture2D[1] == 0 || aTexture2D[2] == 0) {
        LOGE("Failed to generate textures");
        return;
    }
    for (unsigned int i : aTexture2D) {
        glBindTexture(GL_TEXTURE_2D, i);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    GLuint aTextureUniform[3];
    aTextureUniform[Y] = glGetUniformLocation(glProgram, "Ytexture");
    aTextureUniform[U] = glGetUniformLocation(glProgram, "Utexture");
    aTextureUniform[V] = glGetUniformLocation(glProgram, "Vtexture");
    glUniform1i(aTextureUniform[Y], 0);
    glUniform1i(aTextureUniform[U], 1);
    glUniform1i(aTextureUniform[V], 2);
    g_Texture2D[0] = aTexture2D[0];
    g_Texture2D[1] = aTexture2D[1];
    g_Texture2D[2] = aTexture2D[2];
    glUseProgram(0);
}
