#ifndef DEVICE2DEVICE_LOGGING_H
#define DEVICE2DEVICE_LOGGING_H

#include <stdio.h>
#include <time.h>
#include <stdarg.h>
#include <string.h>
#include <libgen.h>
//#pragma GCC diagnostic ignored "-Wwritable-strings"
#pragma GCC diagnostic ignored "-Wformat"
#include <android/log.h>
#ifdef __cplusplus
#define _LOG_(level, fmt, ...) __android_log_print(level, LOG_TAG,"(%s:%d)[%s]: " fmt, basename(const_cast<char*>(__FILE__)), __LINE__, __FUNCTION__, ##__VA_ARGS__)
#else
#define _LOG_(level, fmt, ...) __android_log_print(level, LOG_TAG,"(%s:%d)[%s]: " fmt, basename(__FILE__), __LINE__, __FUNCTION__, ##__VA_ARGS__)
#endif
#define LOGD(fmt, ...) _LOG_(ANDROID_LOG_DEBUG, fmt, ##__VA_ARGS__)
#define LOGI(fmt, ...) _LOG_(ANDROID_LOG_INFO, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) _LOG_(ANDROID_LOG_WARN, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) _LOG_(ANDROID_LOG_ERROR, fmt, ##__VA_ARGS__)
#endif //DEVICE2DEVICE_LOGGING_H
