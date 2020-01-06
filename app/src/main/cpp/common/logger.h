//
// Created by dell-pc on 2019/11/9.
//

#ifndef DEVIDROID_LOGGER_H
#define DEVIDROID_LOGGER_H

#ifndef LOG_TAG
#error Please define 'LOG_TAG' constant at first.
#else

#include <stdio.h>
#include <time.h>
#include <stdarg.h>
#include <android/log.h>
#include <libgen.h>

#ifdef NOTIME
#define TIME_ARGS(_ptm)
#define TIME_FORMAT
#else
#define TIME_ARGS(_ptm) ((_ptm)->tm_year + 1900), ((_ptm)->tm_mon + 1), (_ptm)->tm_mday, (_ptm)->tm_hour, (_ptm)->tm_min, (_ptm)->tm_sec
#define TIME_FORMAT "%d-%d-%d %d:%d:%d "
#endif
#ifdef NOLOCATE
#define LOCATE_ARGS
#define LOCATE_FORMAT
#else
#define LOCATE_ARGS(_module) _module,basename(__FILE__), __FUNCTION__, __LINE__
#define LOCATE_FORMAT " %s %s %s():%d "
#endif

#ifdef NOLOGDEBUG
#define LogDebug(...)
#else
#ifndef LogDebug
#define logger(fm, ...) do { va_list args; va_start(args, fm); (void) vprintf(fm, args); va_end(args); (void) printf("\n"); } while(0)
#define LogDebug(module, format, ...) do { \
    time_t now = time(NULL); \
    struct tm * local = localtime(&now); \
    logger(TIME_FORMAT "Debug:" LOCATE_FORMAT format,TIME_ARGS(local),LOCATE_ARGS(module),##__VA_ARGS__); \
    } while(false)
#endif
#endif

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG,__VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,LOG_TAG,__VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)
#endif
#endif //DEVIDROID_LOGGER_H
