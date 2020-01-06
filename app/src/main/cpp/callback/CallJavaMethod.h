#ifndef DEVIDROID_CALLJAVAMETHOD_H
#define DEVIDROID_CALLJAVAMETHOD_H

#include <string>

class CallJavaMethod {
private:
    CallJavaMethod();

    ~CallJavaMethod();

    static CallJavaMethod *m_Instance;

    void callback(const std::string &method, int action, const char *content, bool isStatic);

public:
    static CallJavaMethod *getInstance();

    void callMethodBack(const std::string &method, int action, const char *content, bool statics);
};

#endif //DEVIDROID_CALLJAVAMETHOD_H
