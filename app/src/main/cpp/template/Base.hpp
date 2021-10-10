//
// Created by Shenyrion on 2020/8/21.
//

#ifndef DEVIDROID_BASE_HPP
#define DEVIDROID_BASE_HPP

#ifndef LOG_TAG
#define LOG_TAG "Base"
#endif

#include <utils/logging.h>
#include <vector>

class Base;

template<typename T>
struct GetSet {
    GetSet(T *t, int (T::*f)(void) = nullptr,
           void (T::*s)(int) = nullptr, int v = 0, char *c = 0)
            : clz(t), get(f), set(s), val(v), key(c)
    {
    }

    T *clz;

    // function<int(void)> get;
    int (T::*get)(void);

    // function<void(int)> set;
    void (T::*set)(int);

    int val;
    char *key;
};

class Base {
public:
    template<typename T>
    int setBase(const char *key, int val);

    virtual int Get() = 0;

    virtual void Set(int) = 0;

private:
    template<typename T>
    int parseBase(vector<GetSet<T>>);
};

template<typename T>
int Base::setBase(const char *key, int val)
{
    vector<GetSet<T>> vec = {};/*{
            { // [this]() -> int { return Get(); },
                    this, &T::Get, &T::Set, val,     "A"
            },
            {       this, &T::Get, &T::Set, val + 1, key}
    };*/
    LOGI("parseBase = %d.", parseBase(vec));
    return val;
}

template<typename T>
int Base::parseBase(vector<GetSet<T>> vec)
{
    for (auto it = vec.begin(); it != vec.end(); ++it) {
        // LOGI("%d, %s.", (it->clz->*get)(), it->key);
    }
    return 0;
}

#endif //DEVIDROID_BASE_HPP
