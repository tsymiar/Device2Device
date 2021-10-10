//
// Created by Shenyrion on 2020/8/21.
//

#include "Clazz2.h"

#ifndef LOG_TAG
#define LOG_TAG "Clazz2"
#endif

#include <utils/logging.h>

int Clazz2::Get()
{
    LOGI("%s", "B");
    return 0;
}

void Clazz2::Set(int val)
{
    LOGI("%d", val);
}
