#ifndef DEVICE2DEVICE_JavaFuncCalls_H
#define DEVICE2DEVICE_JavaFuncCalls_H

#include <string>

class JavaFuncCalls {
private:

    JavaFuncCalls()= default;

    ~JavaFuncCalls() = default;

public:
    typedef int(*CALLBACK)(const char*, int);

    static JavaFuncCalls& GetInstance();

    static int Register(char* method, CALLBACK call);

    static void CallBack(const std::string &method, int action, const char *content, bool statics);
};

#endif //DEVICE2DEVICE_JavaFuncCalls_H
