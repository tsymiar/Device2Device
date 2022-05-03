//
// Created by dell-pc on 2019/11/20.
//

#ifndef DEVIDROID_STATICS_H
#define DEVIDROID_STATICS_H

#include <malloc.h>
#include <mutex>

namespace Statics {
    constexpr const int BYTES_PER_LINE = 513;

    static void singlePrint(char *buf, unsigned int size, int num)
    {
        unsigned int len = size * 3 + 1;
        char *text = (char *) malloc(len);
        memset(text, 0, len);
        for (int i = 0; i < size; i++) {
            sprintf(text + 3 * i, "%02x ", (unsigned char) *(buf + i));
        }
        int total = strlen(text);
        LOGD("--------(%d)[%d]--------", num, total);
        if (total >= BYTES_PER_LINE) {
            char *pre = text;
            while (total / BYTES_PER_LINE >= 0) {
                if (total <= 0)
                    break;
                pre[BYTES_PER_LINE - 1] = '\0';
                LOGD("%s", pre);
                pre += BYTES_PER_LINE;
                total -= BYTES_PER_LINE;
            }
        } else {
            LOGD("%s", text);
        }
        free(text);
    }

    static void printBuffer(char *buf, unsigned int size)
    {
        std::mutex mtxLck{};
        std::lock_guard <std::mutex> lock(mtxLck);
        int i = 0;
        int limit = size / BYTES_PER_LINE;
        for (; i < limit; i++) {
            singlePrint((buf + BYTES_PER_LINE * i), BYTES_PER_LINE, i);
        }
        singlePrint((buf + BYTES_PER_LINE * i), (size - limit * BYTES_PER_LINE), i);
    }
}
#endif //DEVIDROID_STATICS_H
