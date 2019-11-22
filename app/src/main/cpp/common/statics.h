//
// Created by dell-pc on 2019/11/20.
//

#ifndef IPCROID_STATICS_H
#define IPCROID_STATICS_H

#include <cstdlib>

namespace Statics {
    constexpr const int BYTES_PER_LINE = 512;

    static void singlePrint(char *buf, unsigned int size)
    {
        LOGD("----------------");
        size_t len = size * 3;
        char *text = (char *) malloc(len);
        memset(text, 0, len);
        for (int i = 0; i < size; i++) {
            sprintf(text + 3 * i, "%02x ", (unsigned char) *(buf + i));
        }
        LOGD("%s", text);
        free(text);
    }

    static void printBuffer(char *buf, size_t size)
    {
        int i = 0;
        int limit = size / BYTES_PER_LINE;
        for (; i < limit; i++) {
            singlePrint(buf + BYTES_PER_LINE * i, BYTES_PER_LINE);
        }
        singlePrint((buf + BYTES_PER_LINE * i), (size - limit * BYTES_PER_LINE));
    }
}
#endif //IPCROID_STATICS_H
