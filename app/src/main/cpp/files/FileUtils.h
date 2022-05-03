//
// Created by Shenyrion on 2022/5/3.
//

#ifndef DEVIDROID_FILEUTILS_H
#define DEVIDROID_FILEUTILS_H

#include <cstdint>
#include <string>

typedef void (*FileCallback)(uint8_t *, size_t);

namespace FileUtils {
    unsigned char *readLocalFile(const char *filename);
    long readBinaryFile(const std::string& filename, size_t maxSize, FileCallback callback);
};

#endif //DEVIDROID_FILEUTILS_H
