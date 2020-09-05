//
// Created by Shenyrion on 2020/8/21.
//

#include "Clazz1.h"

#ifndef LOG_TAG
#define LOG_TAG "Clazz1"
#endif

#include <utils/logger.h>

int Clazz1::Get()
{
    LOGI("%s", "A");
    return 0;
}

void Clazz1::Set(int val)
{
    LOGI("%d", val);
}
