//
// Created by Shenyrion on 2022/5/2.
//

#include "EglShader.h"

#include <cstdlib>
#ifndef LOG_TAG
#define LOG_TAG "EglShader"
#endif
#include <Utils/logging.h>

#define SR_FAIL 1;

const char g_vertexShader[] =
        {
                "precision mediump float;"
                "attribute vec4 position; "
                "attribute vec4 texCoord; "
                "varying vec4 coord; "

                "void main() "
                "{ "
                "    gl_Position = position; "
                "    coord = texCoord; "
                "} "
        };
const char* g_fragmentShader =
        {
                "precision mediump float;"
                "varying vec4 coord; "
                "uniform sampler2D Ytexture; "
                "uniform sampler2D Utexture; "
                "uniform sampler2D Vtexture; "
                "void main() "
                "{ "
                "    float r,g,b,y,u,v; "

                "    y=texture2D(Ytexture, coord.st).r; "
                "    u=texture2D(Utexture, coord.st).r; "
                "    v=texture2D(Vtexture, coord.st).r; "

                "    y=1.1643*(y-0.0625); "
                "    u=u-0.5; "
                "    v=v-0.5; "

                "    r=y+1.5958*v; "
                "    g=y-0.39173*u-0.81290*v; "
                "    b=y+2.017*u; "
                "    gl_FragColor=vec4(r,g,b,1.0); "
                "} "
        };

GLuint GetGLShader(GLenum shaderType, const char *pSource)
{
    GLuint shader = 0;
    shader = glCreateShader(shaderType);
    if (shader)
    {
        glShaderSource(shader, 1, &pSource, nullptr);
        glCompileShader(shader);
        GLint compiled = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (!compiled)
        {
            GLint infoLen = 0;
            glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
            if (infoLen)
            {
                char* buf = (char*) malloc((size_t)infoLen);
                if (buf)
                {
                    glGetShaderInfoLog(shader, infoLen, nullptr, buf);
                    LOGI("GetShader Could not compile shader %d:\n%s", shaderType, buf);
                    free(buf);
                }
                glDeleteShader(shader);
                shader = 0;
            }
        }
    }
    return shader;
}

void CheckGLError(const char *operation)
{
    for (GLint error = glGetError(); error; error = glGetError())
    {
        LOGE("CheckGLError GL Operation %s() glError (0x%x)", operation, error);
    }
}

GLuint EglShader::CreateProgram(const char *pVertexShaderSource, const char *pFragShaderSource, GLuint &vertexShaderHandle, GLuint &fragShaderHandle)
{
    GLuint program = 0;
    vertexShaderHandle = GetGLShader(GL_VERTEX_SHADER, pVertexShaderSource);
    if (!vertexShaderHandle) return program;

    fragShaderHandle = GetGLShader(GL_FRAGMENT_SHADER, pFragShaderSource);
    if (!fragShaderHandle) return program;

    program = glCreateProgram();
    if (program)
    {
        // Attaches a shader object to a program object
        glAttachShader(program, vertexShaderHandle);
        CheckGLError("glAttachShader");
        glAttachShader(program, fragShaderHandle);
        CheckGLError("glAttachShader");
        // Bind vPosition to attribute 0
        glBindAttribLocation(program, 0, "vPosition");
        glLinkProgram(program);
        GLint linkStatus = GL_FALSE;
        glGetProgramiv(program, GL_LINK_STATUS, &linkStatus);

        glDetachShader(program, vertexShaderHandle);
        glDeleteShader(vertexShaderHandle);
        vertexShaderHandle = 0;
        glDetachShader(program, fragShaderHandle);
        glDeleteShader(fragShaderHandle);
        fragShaderHandle = 0;
        if (linkStatus != GL_TRUE)
        {
            GLint bufLength = 0;
            glGetProgramiv(program, GL_INFO_LOG_LENGTH, &bufLength);
            if (bufLength)
            {
                char* buf = (char*) malloc((size_t)bufLength);
                if (buf)
                {
                    glGetProgramInfoLog(program, bufLength, nullptr, buf);
                    LOGI("CreateProgram Could not link program:\n%s", buf);
                    free(buf);
                }
            }
            glDeleteProgram(program);
            program = 0;
        }
    }
    LOGI("CreateProgram: %d", program);
    return program;
}

void EglShader::DeleteProgram(GLuint &program)
{
    LOGI("DeleteProgram");
    if (program)
    {
        glUseProgram(0);
        glDeleteProgram(program);
        program = 0;
    }
}

GLuint EglShader::GetShaderProgram()
{
    GLuint vertexShader = glCreateShader(GL_VERTEX_SHADER);
    if (!vertexShader) {
        CheckGLError("glCreateShader");
    }
    auto vsLen = static_cast<GLint>(strlen(g_vertexShader)) + 1;
    glShaderSource(vertexShader, 1, (const GLchar **) &g_vertexShader, &vsLen);
    GLint compileRet;
    glCompileShader(vertexShader);
    glGetShaderiv(vertexShader, GL_COMPILE_STATUS, &compileRet);
    if (0 == compileRet) {
        return SR_FAIL;
    }

    GLuint fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
    auto fsLen = static_cast<GLint>(strlen(g_fragmentShader));
    glShaderSource(fragmentShader, 1, (const GLchar **) &g_fragmentShader, &fsLen);
    glCompileShader(fragmentShader);
    glGetShaderiv(fragmentShader, GL_COMPILE_STATUS, &compileRet);
    if (0 == compileRet) {
        return SR_FAIL;
    }

    GLuint shaderProgram = glCreateProgram();
    glAttachShader(shaderProgram, vertexShader);
    glAttachShader(shaderProgram, fragmentShader);
    glLinkProgram(shaderProgram);
    GLint linkRet;
    glGetProgramiv(shaderProgram, GL_LINK_STATUS, &linkRet);
    if (0 == linkRet) {
        return SR_FAIL;
    }
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    LOGI("GetShaderProgram: [%u]", shaderProgram);
    return shaderProgram;
}
