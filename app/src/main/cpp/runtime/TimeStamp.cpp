#include "TimeStamp.h"
#include <ctime>
#include <sys/times.h>
#include <cstdlib>
#include <sys/types.h>
#include <unistd.h>

#ifndef LOG_TAG
#define LOG_TAG "TimeStamp"
#endif

#include <utils/logger.h>

constexpr const int KILO = 1000;
constexpr const int TIME_LANG = 24;

TimeStamp *TimeStamp::m_Instance = nullptr;

TimeStamp *TimeStamp::get()
{
    return m_Instance;
}

unsigned long long TimeStamp::BootTime()
{
    constexpr const int MILLION = 1000000;
    struct timespec time{};
    clock_gettime(CLOCK_MONOTONIC, &time);
    unsigned long long micro = MILLION * (unsigned long long) time.tv_sec + time.tv_nsec / KILO;
    auto tm0 = static_cast<time_t>(micro / KILO);
    struct tm *ttm = localtime(&tm0);
    char szTime[TIME_LANG] = {0};
    snprintf(szTime, sizeof(szTime) - 1,
             "%d-%02d-%02d %02d:%02d:%02d",
             ttm->tm_year + 1900,
             ttm->tm_mon + 1,
             ttm->tm_mday,
             ttm->tm_hour,
             ttm->tm_min,
             ttm->tm_sec);
    LOGD("BootTime: micro = %lld, tm0 = %ld, szTime = %s", micro, tm0, szTime);
    return micro;
}

unsigned long long TimeStamp::AbsoluteTime()
{
    timeval time{};
    gettimeofday(&time, NULL);
    char stm[TIME_LANG] = "";
    strftime(stm, sizeof(stm), "%Y-%m-%d %T", localtime(&time.tv_sec));
    unsigned long long micro = (unsigned long long) time.tv_sec * KILO + time.tv_usec / KILO;
    LOGD("Absolute TimeStamp = %lld, %s", micro, stm);
    return micro;
}
