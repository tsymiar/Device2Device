//
// Created by dell-pc on 2019/11/20.
//

#ifndef IPCROID_STATICS_H
#define IPCROID_STATICS_H

#include <cstdlib>

namespace Statics {
    static void printBuffer(char *buf, size_t size)
    {
        LOGD("----------------");
        size_t len = size * 3;
        char *text = (char *)malloc(len);
        memset(text, 0, len);
        for (int i = 0; i < size; i++) {
            sprintf(text + 3 * i, "%02x ", (unsigned char)*(buf + i));
        }
        LOGD("%s", text);
    }
}
#endif //IPCROID_STATICS_H
