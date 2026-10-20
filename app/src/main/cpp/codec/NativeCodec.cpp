//
// Created by Shenyrion on 2025/3/10.
//
// 视频渲染：AMediaExtractor 取视频轨 + AMediaCodec 硬解，解码器直接把每一帧渲到
// 传进来的 ANativeWindow（TextureView 的 Surface）上，所以不用自己搬 YUV、也不用自己做 EGL。
//
// 解码器直出：按 PTS 控节奏，播放放在后台线程，UI 线程调完立即返回。
//（自己 eglSwapBuffers + updateTexImage 那条路要先把 eglDisplay / eglSurface 初始化好、
//  并且从 surface 参数里取出 nativeWindow，少一步就什么都不会画。）
//

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <android/native_window_jni.h>

#include <atomic>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <pthread.h>
#include <unistd.h>

#ifndef LOG_TAG
#define LOG_TAG "NativeCodec"
#endif
#include <utils/logging.h>

namespace {

AMediaExtractor* g_extractor = nullptr;
AMediaCodec* g_codec = nullptr;
ANativeWindow* g_window = nullptr;
pthread_t g_thread{};
std::atomic<bool> g_stop{false};
std::atomic<bool> g_playing{false};

/** 单调时钟的微秒数：控帧率不能用 wall clock，改系统时间会把节奏打乱 */
int64_t nowUs()
{
    struct timespec ts {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000LL + ts.tv_nsec / 1000LL;
}

/** 播完或被 stop 之后统一收摊 */
void releaseAll()
{
    if (g_codec != nullptr) {
        AMediaCodec_stop(g_codec);
        AMediaCodec_delete(g_codec);
        g_codec = nullptr;
    }
    if (g_extractor != nullptr) {
        AMediaExtractor_delete(g_extractor);
        g_extractor = nullptr;
    }
    if (g_window != nullptr) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }
}

void* decodeThread(void*)
{
    bool inputDone = false;
    bool sawFirstFrame = false;
    int64_t firstPtsUs = 0;
    int64_t startRealUs = 0;

    while (!g_stop.load()) {
        // ---- 喂输入 ----
        if (!inputDone) {
            ssize_t inIdx = AMediaCodec_dequeueInputBuffer(g_codec, 10000);
            if (inIdx >= 0) {
                size_t bufSize = 0;
                uint8_t* buf = AMediaCodec_getInputBuffer(g_codec, (size_t) inIdx, &bufSize);
                if (buf == nullptr) {
                    LOGE("getInputBuffer(%zd) returned null", inIdx);
                } else {
                    int sample = AMediaExtractor_readSampleData(g_extractor, buf, bufSize);
                    if (sample < 0) {
                        // 取不到样本就是到尾巴了，补一个 EOS 让解码器把最后的帧吐出来
                        AMediaCodec_queueInputBuffer(g_codec, (size_t) inIdx, 0, 0, 0,
                                                     AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        int64_t ptsUs = AMediaExtractor_getSampleTime(g_extractor);
                        uint32_t flags = (uint32_t) AMediaExtractor_getSampleFlags(g_extractor);
                        AMediaCodec_queueInputBuffer(g_codec, (size_t) inIdx, 0,
                                                     (size_t) sample, ptsUs, flags);
                        AMediaExtractor_advance(g_extractor);
                    }
                }
            }
        }

        // ---- 取输出，等到该帧的呈现时刻再放 ----
        AMediaCodecBufferInfo info;
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(g_codec, &info, 10000);
        if (outIdx >= 0) {
            if (info.size > 0) {
                if (!sawFirstFrame) {
                    sawFirstFrame = true;
                    firstPtsUs = info.presentationTimeUs;
                    startRealUs = nowUs();
                }
                int64_t dueUs = (info.presentationTimeUs - firstPtsUs) - (nowUs() - startRealUs);
                // 提前 2ms 放帧：每帧的轻微延迟会累积为整体偏慢
                if (dueUs > 3000) {
                    usleep((useconds_t) (dueUs - 2000));
                }
            }
            // render=true：解码器把该帧直接画到 window 上，我们不用碰像素
            AMediaCodec_releaseOutputBuffer(g_codec, (size_t) outIdx, info.size > 0);
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                break;
            }
        } else if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            LOGI("output format changed");
        } else if (outIdx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            // NDK 里不用管：缓冲区由 getInputBuffer / getOutputBuffer 现取
        }
    }

    g_stop.store(true);
    releaseAll();
    g_playing.store(false);
    LOGI("video decode thread exit");
    return nullptr;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_tsymiar_device2device_wrapper_ViewWrapper_startVideoRender(
        JNIEnv* env, jclass, jobject surface, jstring filePath)
{
    if (g_playing.load()) {
        LOGE("video is already playing");
        return -1;
    }
    if (surface == nullptr || filePath == nullptr) {
        return -2;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        LOGE("ANativeWindow_fromSurface failed");
        return -3;
    }

    const char* path = env->GetStringUTFChars(filePath, nullptr);
    if (path == nullptr) {
        ANativeWindow_release(window);
        return -2;
    }

    AMediaExtractor* extractor = AMediaExtractor_new();
    if (extractor == nullptr) {
        env->ReleaseStringUTFChars(filePath, path);
        ANativeWindow_release(window);
        return -4;
    }
    // 走 fd：中文 / 带空格的路径用 setDataSource(path) 容易解析失败
    int fd = ::open(path, O_RDONLY);
    if (fd < 0) {
        LOGE("open [%s] failed: %s", path, strerror(errno));
        env->ReleaseStringUTFChars(filePath, path);
        AMediaExtractor_delete(extractor);
        ANativeWindow_release(window);
        return -5;
    }
    off_t fileSize = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);
    media_status_t status = AMediaExtractor_setDataSourceFd(extractor, fd, 0, fileSize);
    ::close(fd);
    env->ReleaseStringUTFChars(filePath, path);
    if (status != AMEDIA_OK) {
        LOGE("setDataSourceFd failed (%d)", (int) status);
        AMediaExtractor_delete(extractor);
        ANativeWindow_release(window);
        return -6;
    }

    // 找第一条视频轨（音频轨不解码：这里只要画面）
    AMediaCodec* codec = nullptr;
    size_t tracks = AMediaExtractor_getTrackCount(extractor);
    for (size_t i = 0; i < tracks; ++i) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(extractor, i);
        if (format == nullptr) continue;
        const char* mime = nullptr;
        bool isVideo = AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime)
                       && mime != nullptr && strncmp(mime, "video/", 6) == 0;
        if (isVideo) {
            AMediaExtractor_selectTrack(extractor, i);
            codec = AMediaCodec_createDecoderByType(mime);
            if (codec != nullptr) {
                // window 交给解码器：它会按视频尺寸自己设 buffer geometry 并直接出帧
                if (AMediaCodec_configure(codec, format, window, nullptr, 0) != AMEDIA_OK) {
                    LOGE("AMediaCodec_configure failed");
                    AMediaCodec_delete(codec);
                    codec = nullptr;
                }
            }
            AMediaFormat_delete(format);
            break;
        }
        AMediaFormat_delete(format);
    }
    if (codec == nullptr) {
        LOGE("no decodable video track");
        AMediaExtractor_delete(extractor);
        ANativeWindow_release(window);
        return -7;
    }
    if (AMediaCodec_start(codec) != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed");
        AMediaCodec_delete(codec);
        AMediaExtractor_delete(extractor);
        ANativeWindow_release(window);
        return -8;
    }

    g_extractor = extractor;
    g_codec = codec;
    g_window = window;
    g_stop.store(false);
    g_playing.store(true);
    if (pthread_create(&g_thread, nullptr, decodeThread, nullptr) != 0) {
        LOGE("pthread_create failed");
        releaseAll();
        g_playing.store(false);
        return -9;
    }
    LOGI("video render started");
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsymiar_device2device_wrapper_ViewWrapper_stopVideoRender(JNIEnv*, jclass)
{
    if (!g_playing.load()) {
        return;
    }
    g_stop.store(true);
    // 解码循环每次 dequeue 最多等 10ms，所以这里 join 不会久
    pthread_join(g_thread, nullptr);
    g_playing.store(false);
    LOGI("video render stopped");
}
