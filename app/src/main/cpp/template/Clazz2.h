//
// Created by Shenyrion on 2020/8/21.
//

#ifndef DEVICE2DEVICE_CLAZZ2_H
#define DEVICE2DEVICE_CLAZZ2_H


#include "Base.hpp"

class Clazz2 : public Base {
public:
    int Get() override;

    void Set(int val) override;
};


#endif //DEVICE2DEVICE_CLAZZ2_H
