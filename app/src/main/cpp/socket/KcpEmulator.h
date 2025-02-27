#ifndef DEVICE2DEVICE_KCPEMULATOR_H
#define DEVICE2DEVICE_KCPEMULATOR_H

#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <ctype.h>
#include <string.h>
#include "kcp/ikcp.h"

#if defined(WIN32) || defined(_WIN32) || defined(WIN64) || defined(_WIN64)
#include <windows.h>
#elif !defined(__unix)
#define __unix
#endif

#ifdef __unix

#include <unistd.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <sys/types.h>

#endif

/* get system time */
static inline void itimeofday(long *sec, long *usec)
{
#if defined(__unix)
    struct timeval time;
    gettimeofday(&time, NULL);
    if (sec) *sec = time.tv_sec;
    if (usec) *usec = time.tv_usec;
#else
    static long mode = 0, addsec = 0;
    BOOL retval;
    static IINT64 freq = 1;
    IINT64 qpc;
    if (mode == 0) {
        retval = QueryPerformanceFrequency((LARGE_INTEGER*)&freq);
        freq = (freq == 0)? 1 : freq;
        retval = QueryPerformanceCounter((LARGE_INTEGER*)&qpc);
        addsec = (long)time(NULL);
        addsec = addsec - (long)((qpc / freq) & 0x7fffffff);
        mode = 1;
    }
    retval = QueryPerformanceCounter((LARGE_INTEGER*)&qpc);
    retval = retval * 2;
    if (sec) *sec = (long)(qpc / freq) + addsec;
    if (usec) *usec = (long)((qpc % freq) * 1000000 / freq);
#endif
}

/* get clock in millisecond 64 */
static inline IINT64 iclock64(void)
{
    long s, u;
    IINT64 value;
    itimeofday(&s, &u);
    value = ((IINT64) s) * 1000 + (u / 1000);
    return value;
}

static inline IUINT32 iclock()
{
    return (IUINT32) (iclock64() & 0xffffffffull);
}

/* sleep in millisecond */
static inline void isleep(unsigned long millisecond)
{
#ifdef __unix    /* usleep( time * 1000 ); */
    struct timespec ts;
    ts.tv_sec = (time_t) (millisecond / 1000);
    ts.tv_nsec = (long) ((millisecond % 1000) * 1000000);
    /*nanosleep(&ts, NULL);*/
    usleep((millisecond << 10) - (millisecond << 4) - (millisecond << 3));
#elif defined(_WIN32)
    Sleep(millisecond);
#endif
}

#ifdef __cplusplus

#include <list>
#include <vector>

// 带延迟的数据包
class DelayPacket {
public:
    virtual ~DelayPacket()
    {
        if (_ptr) delete _ptr;
        _ptr = NULL;
    }

    DelayPacket(int size, const void *src = NULL)
    {
        _ptr = new unsigned char[size];
        _size = size;
        if (src) {
            memcpy(_ptr, src, size);
        }
    }

    unsigned char *ptr()
    { return _ptr; }

    const unsigned char *ptr() const
    { return _ptr; }

    int size() const
    { return _size; }

    IUINT32 ts() const
    { return _ts; }

    void setts(IUINT32 ts)
    { _ts = ts; }

protected:
    unsigned char *_ptr;
    int _size;
    IUINT32 _ts;
};

// 均匀分布的随机数
class Random {
public:
    Random(int size)
    {
        this->size = 0;
        seeds.resize(size);
    }

    int random()
    {
        int x, i;
        if (seeds.size() == 0) return 0;
        if (size == 0) {
            for (i = 0; i < (int) seeds.size(); i++) {
                seeds[i] = i;
            }
            size = (int) seeds.size();
        }
        i = rand() % size;
        x = seeds[i];
        seeds[i] = seeds[--size];
        return x;
    }

protected:
    int size;
    std::vector<int> seeds;
};

// 网络延迟模拟器
class KcpEmulator {
public:
    KcpEmulator(int lostrate = 10, int rttmin = 60, int rttmax = 125, int nmax = 1000);

    void Sender(int peer, const void *data, int size);

    int Receiver(int peer, void *data, int maxsize);

    virtual ~KcpEmulator();

public:
    int tx1;
    int tx2;
protected:
    IUINT32 current;
    int lostrate;
    int rttmin;
    int rttmax;
    int nmax;
    typedef std::list<DelayPacket *> DelayTunnel;
    DelayTunnel p12;
    DelayTunnel p21;
    Random r12;
    Random r21;

private:
    const int RECVWND = 128;
    void freeKcp();
public:
    void KcpRun(int speed);
};

#endif

#endif //DEVICE2DEVICE_KCPEMULATOR_H
