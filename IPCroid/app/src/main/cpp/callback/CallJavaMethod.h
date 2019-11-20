#ifndef IPCROID_CALLJAVAMETHOD_H
#define IPCROID_CALLJAVAMETHOD_H

#include <string>

class CallJavaMethod {
private:
    CallJavaMethod();

    ~CallJavaMethod();

    static CallJavaMethod *m_Instance;

    void callback(const std::string &method, int action, const char *content, bool isStatic);

public:
    static CallJavaMethod *getInstance();

    void callStaticMethod(const std::string &method, int action, const char *content);

    void callNonstaticMethod(const std::string &method, int action, const char *content);
};

#endif //IPCROID_CALLJAVAMETHOD_H
