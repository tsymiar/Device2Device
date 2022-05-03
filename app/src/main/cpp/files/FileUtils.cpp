//
// Created by Shenyrion on 2022/5/3.
//

#include "FileUtils.h"

#ifndef LOG_TAG
#define LOG_TAG "FileUtils"
#endif
#include <Utils/logging.h>
#include <cerrno>
#include <fstream>

long g_fileSize = 0;
FILE *g_fileDesc = nullptr;
unsigned char *g_fileContent = nullptr;

long getFileSize(FILE *stream) {
    long offset = ftell(stream);
    if (offset == -1) {
        LOGE("ftell begin failed :%s", strerror(errno));
        return -1;
    }
    if (fseek(stream, 0, SEEK_END) != 0) {
        LOGE("fseek END failed: %s", strerror(errno));
        return -1;
    }
    long size = ftell(stream);
    if (size == -1) {
        LOGE("ftell end failed :%s", strerror(errno));
    }
    if (fseek(stream, offset, SEEK_SET) != 0) {
        LOGE("fseek offset failed: %s", strerror(errno));
        return -1;
    }
    return size;
}

unsigned char *FileUtils::readLocalFile(const char *filename)
{
    g_fileDesc = fopen(filename, "rbe");
    if (g_fileDesc != nullptr) {
        long size = getFileSize(g_fileDesc);
        g_fileContent = new unsigned char[size];
        memset(g_fileContent, '\0', size);
        fread(g_fileContent, size, 1, g_fileDesc);
        g_fileSize = size;
    } else {
        char msg[128];
        char text[] = "'%s' open failed:\n%s!";
        sprintf(msg, text, filename, strerror(errno));
        LOGE("%s", msg);
        return nullptr;
    }
    LOGI("get file size = %ld", g_fileSize);
    return g_fileContent;
}

long FileUtils::readBinaryFile(const std::string& filename, size_t maxSize, FileCallback callback)
{
    using namespace std;
    ifstream fin;
    fin.open(filename, ios_base::binary);
    if (!fin.is_open())
    {
        LOGE("Error In Open...");
        return -1;
    }

    fin.seekg(0, ios::end);
    long fsize = fin.tellg();
    g_fileSize = fsize;

    fin.seekg(0, ios::beg);

    long len = maxSize;
    uint8_t szin[maxSize];
    memset(szin, 0, maxSize);

    if (fsize <= maxSize)
        len = fsize;

    while (fin.read((char*)szin, len))
    {
        if (callback != nullptr) {
            callback(szin, len);
        }
        fsize -= maxSize;
        if (fsize <= maxSize)
        {
            len = fsize;
        }
        if (fsize < 0)
            break;
    }

    fin.close();
    return g_fileSize;
}
