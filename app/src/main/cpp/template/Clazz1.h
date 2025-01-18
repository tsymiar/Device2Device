//
// Created by Shenyrion on 2020/8/21.
//

#ifndef DEVICE2DEVICE_CLAZZ1_H
#define DEVICE2DEVICE_CLAZZ1_H

#include "Base.hpp"

class Clazz1 : public Base {
public:
    int Get() override;

    void Set(int val) override;
};


#endif //DEVICE2DEVICE_CLAZZ1_H
