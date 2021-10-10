#ifndef DEVIDROID_CALLJAVAMETHOD_H
#define DEVIDROID_CALLJAVAMETHOD_H

#include <string>

class CallJavaMethod {
private:

    CallJavaMethod();

    ~CallJavaMethod();

    static void callback(const std::string &method, int action, const char *content, bool isStatic);

public:
    typedef int(*CALLBACK)(const char*, int);

    static CallJavaMethod& GetInstance();

    static void callMethodBack(const std::string &method, int action, const char *content, bool statics);

    static int registerCallBack(char* method, CALLBACK call);
};

#endif //DEVIDROID_CALLJAVAMETHOD_H
