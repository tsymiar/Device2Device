//
// Created by Shenyrion on 2022/5/3.
//

#ifndef DEVICE2DEVICE_FILEUTILS_H
#define DEVICE2DEVICE_FILEUTILS_H

#include <cstdint>
#include <string>
#include <fstream>

typedef void (*FileCallback)(uint8_t *, size_t);

namespace FileUtils {
    int MakeDirs(const char *fullPath);
    long GetFileSize(FILE *file);
    std::string GetFileAsString(const std::string& filename);
    unsigned char *GetFileContentNeedFree(const char *filename, long& size);
    long ReadBinaryFile(const std::string& filename, size_t maxSize, FileCallback callback);
}

#endif //DEVICE2DEVICE_FILEUTILS_H
