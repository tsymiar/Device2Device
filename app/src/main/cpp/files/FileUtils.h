//
// Created by Shenyrion on 2022/5/3.
//

#ifndef DEVIDROID_FILEUTILS_H
#define DEVIDROID_FILEUTILS_H

#include <cstdint>
#include <string>
#include <fstream>

typedef void (*FileCallback)(uint8_t *, size_t);

namespace FileUtils {
    long GetFileSize(FILE *file);
    std::string GetFileAsString(const std::string& filename);
    unsigned char *GetFileContentNeedFree(const char *filename, long& size);
    long ReadBinaryFile(const std::string& filename, size_t maxSize, FileCallback callback);
}

#endif //DEVIDROID_FILEUTILS_H
