#ifndef LOG_TAG
#define LOG_TAG "JavaFuncCalls"
#endif

#include "JavaFuncCalls.h"
#include <algorithm>
#include <thread>
#include <Utils/logging.h>

JavaFuncCalls& JavaFuncCalls::GetInstance()
{
    static JavaFuncCalls instance;
    return instance;
}

extern void CallBackJavaMethod(const std::string &method, int action, const char *content, bool statics);

void JavaFuncCalls::CallBack(const std::string &method,
                                    int action,
                                    const char *content,
                                    bool statics)
{
    CallBackJavaMethod(method, action, content, statics);
}

int JavaFuncCalls::Register(char *method, CALLBACK call)
{
    int a = 999;
    std::thread th1([](CALLBACK callback, int a, const char *c) {
        LOGI("a = %d", a);
        a--;
        callback(c, a);
        LOGI("has call 'call': %p, (%d, %s)", callback, a, c);
    }, call, /*std::ref*/(a), method);
    if (th1.joinable())
        th1.detach();
    return a;
}