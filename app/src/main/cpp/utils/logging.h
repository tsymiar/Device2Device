#ifndef DEVICE2DEVICE_LOGGING_H
#define DEVICE2DEVICE_LOGGING_H

#include <stdio.h>
#include <time.h>
#include <stdarg.h>
#include <string.h>
#include <libgen.h>
#include <stdlib.h>
#include <sys/time.h>

#ifdef __ANDROID__
#include <android/log.h>
// 自定义阈值常量（用于 LOG_LEVEL 过滤比较）
#define _LOG_THRESH_DEBUG 0
#define _LOG_THRESH_INFO  1
#define _LOG_THRESH_WARN  2
#define _LOG_THRESH_ERROR 3
// 映射到 Android 实际优先级（__android_log_print 需要）
#define _LOG_LEVEL_DEBUG ANDROID_LOG_DEBUG
#define _LOG_LEVEL_INFO  ANDROID_LOG_INFO
#define _LOG_LEVEL_WARN  ANDROID_LOG_WARN
#define _LOG_LEVEL_ERROR ANDROID_LOG_ERROR
#else
#define _LOG_LEVEL_DEBUG 0
#define _LOG_LEVEL_INFO  1
#define _LOG_LEVEL_WARN  2
#define _LOG_LEVEL_ERROR 3
#endif

#ifndef LOG_LEVEL
#define LOG_LEVEL _LOG_LEVEL_INFO
#endif

#ifndef LOG_TAG
#define LOG_TAG "native"
#endif

static inline void getCurrentTime(char* buf, size_t len)
{
    struct timeval tv;
    gettimeofday(&tv, NULL);
    struct tm* tm_info = localtime(&tv.tv_sec);
    size_t offset = strftime(buf, len, "%H:%M:%S", tm_info);
    if (offset > 0 && offset < len) {
        snprintf(buf + offset, len - offset, ".%03ld", tv.tv_usec / 1000);
    }
}

#ifdef __ANDROID__
#define _LOG_PRINT_(level, tag, fmt, ...) do { \
    char timeBuf[16]; \
    getCurrentTime(timeBuf, sizeof(timeBuf)); \
    __android_log_print(level, tag, "[%s](%s:%d)[%s]: " fmt, \
        timeBuf, basename(strdup(__FILE__)), __LINE__, __FUNCTION__, ##__VA_ARGS__); \
} while(0)
#elif defined(__linux__) || defined(__APPLE__)
#define _LOG_PRINT_(level, tag, fmt, ...) do { \
    if (level >= LOG_LEVEL) { \
        char timeBuf[16]; \
        getCurrentTime(timeBuf, sizeof(timeBuf)); \
        const char* color_code; \
        switch (level) { \
            case _LOG_LEVEL_DEBUG: color_code = "0;36"; break; \
            case _LOG_LEVEL_INFO:  color_code = "0;32"; break; \
            case _LOG_LEVEL_WARN:  color_code = "0;33"; break; \
            default:               color_code = "0;31"; break; \
        } \
        fprintf(stderr, "\033[%sm[%s][%s](%s:%d)[%s]: \033[0m" fmt "\n", \
                color_code, timeBuf, tag, basename(strdup(__FILE__)), __LINE__, __FUNCTION__, ##__VA_ARGS__); \
    } \
} while(0)
#else
#define _LOG_PRINT_(level, tag, fmt, ...) do { \
    if (level >= LOG_LEVEL) { \
        char timeBuf[16]; \
        getCurrentTime(timeBuf, sizeof(timeBuf)); \
        fprintf(stderr, "[%s][%s:%d]: " fmt "\n", \
                timeBuf, basename(strdup(__FILE__)), __LINE__, ##__VA_ARGS__); \
    } \
} while(0)
#endif

#define LOGD(fmt, ...) _LOG_PRINT_(_LOG_LEVEL_DEBUG, LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGI(fmt, ...) _LOG_PRINT_(_LOG_LEVEL_INFO,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) _LOG_PRINT_(_LOG_LEVEL_WARN,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) _LOG_PRINT_(_LOG_LEVEL_ERROR, LOG_TAG, fmt, ##__VA_ARGS__)

#endif //DEVICE2DEVICE_LOGGING_H
