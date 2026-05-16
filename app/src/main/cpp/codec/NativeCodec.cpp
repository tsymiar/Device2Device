//
// Created by Shenyrion on 2025/3/10.
//

#include <android/native_window.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <asm-generic/fcntl.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <jni.h>
#include <android/native_window_jni.h>
#include <chrono>
#include <queue>

// 同步相关变量
std::queue<int64_t> frameTimestamps; // 存储帧时间戳（微秒）
bool decodingCompleted = false;
int64_t startTimeUs = 0;

// EGL相关变量
EGLDisplay eglDisplay;
EGLSurface eglSurface;

// 媒体相关变量
ANativeWindow* nativeWindow;
AMediaExtractor* mediaExtractor;
AMediaCodec* mediaCodec;

// 全局变量声明
extern JavaVM* javaVM;
jobject surfaceTextureObj;
jclass surfaceTextureClass;

// 初始化媒体解码器
bool initDecoder(const char* filePath) {
    mediaExtractor = AMediaExtractor_new();
    int fd = open(filePath, O_RDONLY);
    AMediaExtractor_setDataSourceFd(mediaExtractor, fd, 0, lseek(fd, 0, SEEK_END));
    close(fd);

    for (size_t i = 0; i < AMediaExtractor_getTrackCount(mediaExtractor); i++) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(mediaExtractor, i);
        const char* mime;
        AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime);

        if (strncmp(mime, "video/", 6) == 0) {
            AMediaExtractor_selectTrack(mediaExtractor, i);
            mediaCodec = AMediaCodec_createDecoderByType(mime);
            AMediaCodec_configure(mediaCodec, format, nativeWindow, NULL, 0);
            AMediaCodec_start(mediaCodec);
            AMediaFormat_delete(format);
            return true;
        }
        AMediaFormat_delete(format);
    }
    return false;
}

void* decodingThread(void* arg) {
    AMediaCodecBufferInfo info;
    ssize_t outIndex;

    while (!decodingCompleted) {
        // 从解码器获取输出缓冲区
        outIndex = AMediaCodec_dequeueOutputBuffer(mediaCodec, &info, 0);
        if (outIndex >= 0) {
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                decodingCompleted = true;
            }

            // 记录有效帧的时间戳
            if (info.size > 0 && info.presentationTimeUs > 0) {
                frameTimestamps.push(info.presentationTimeUs);
            }

            // 渲染后必须释放缓冲区
            AMediaCodec_releaseOutputBuffer(mediaCodec, outIndex, info.size != 0);
        } else if (outIndex == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            // 需要重新获取输出缓冲区（API <21）
        }
    }
    return nullptr;
}

void renderFrame() {
    // 获取当前时间（微秒）
    auto now = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::system_clock::now().time_since_epoch()
    ).count();

    if (startTimeUs == 0 && !frameTimestamps.empty()) {
        startTimeUs = now - frameTimestamps.front();
    }

    if (!frameTimestamps.empty()) {
        int64_t pts = frameTimestamps.front();
        int64_t targetTime = startTimeUs + pts;

        // 时间同步控制
        if (now < targetTime) {
            // 计算需要休眠的时间（微秒转毫秒）
            int64_t sleepTime = (targetTime - now) / 1000;
            if (sleepTime > 0) {
                usleep(sleepTime * 1000);
            }
        } else if (now - targetTime > 100000) { // 超过100ms延迟
            frameTimestamps.pop(); // 丢弃过期帧
            return;
        }

        // 调用Java层SurfaceTexture.updateTexImage()
        JNIEnv* env;
        javaVM->AttachCurrentThread(&env, nullptr);
        jmethodID updateTex = env->GetMethodID(surfaceTextureClass, "updateTexImage", "()V");
        env->CallVoidMethod(surfaceTextureObj, updateTex);
        javaVM->DetachCurrentThread();

        // 执行OpenGL渲染
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        eglSwapBuffers(eglDisplay, eglSurface);

        frameTimestamps.pop();
    }
}

// JNI入口函数
extern "C" JNIEXPORT void JNICALL
Java_com_tsymiar_device2device_wrapper_ViewWrapper_nativeRender(
        JNIEnv* env,
        jobject thiz,
        jobject surface,
        jobject texture,  // SurfaceTexture对象
        jstring filePath) {

    // 保存Java对象引用
    surfaceTextureObj = env->NewGlobalRef(texture);
    jclass clazz = env->GetObjectClass(texture);
    surfaceTextureClass = (jclass)env->NewGlobalRef(clazz);

    // 初始化解码器
    const char* path = env->GetStringUTFChars(filePath, NULL);
    initDecoder(path);
    env->ReleaseStringUTFChars(filePath, path);

    // 创建解码线程
    pthread_t thread;
    pthread_create(&thread, nullptr, decodingThread, nullptr);

    // 主渲染循环
    while (!decodingCompleted || !frameTimestamps.empty()) {
        renderFrame();
    }

    // 等待解码线程结束
    pthread_join(thread, nullptr);

    // 释放资源...
}
