//
// Created by Shenyrion on 2022/5/3.
//

#include "FileUtils.h"

#ifndef LOG_TAG
#define LOG_TAG "FileUtils"
#endif
#include <Utils/logging.h>
#include <cerrno>

long FileUtils::GetFileSize(FILE *file)
{
    long offset = ftell(file);
    if (offset == -1) {
        LOGE("ftell begin failed :%s", strerror(errno));
        return -1;
    }
    if (fseek(file, 0, SEEK_END) != 0) {
        LOGE("fseek END failed: %s", strerror(errno));
        return -1;
    }
    long size = ftell(file);
    if (size == -1) {
        LOGE("ftell end failed :%s", strerror(errno));
    }
    if (fseek(file, offset, SEEK_SET) != 0) {
        LOGE("fseek offset failed: %s", strerror(errno));
        return -1;
    }
    return size;
}

std::string FileUtils::GetFileAsString(const std::string& filename)
{
    std::string content{};
    std::ifstream file(filename);
    if (file.is_open()) {
        content.assign(std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>());
    }
    file.close();
    return content;
}

unsigned char* FileUtils::GetFileContentNeedFree(const char *filename, long& size)
{
    unsigned char* content = nullptr;
    FILE *file = fopen(filename, "rbe");
    if (file != nullptr) {
        size = GetFileSize(file);
        content = new unsigned char[size + 1];
        memset(content, '\0', size);
        fread(content, sizeof(unsigned char), size, file);
        content[size] = '\0';
        fclose(file);
    } else {
        char msg[128];
        char text[] = "'%s' open failed:\n%s!";
        sprintf(msg, text, filename, strerror(errno));
        LOGE("%s", msg);
    }
    return content;
}

long FileUtils::ReadBinaryFile(const std::string& filename, size_t maxSize, FileCallback callback)
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
    return fsize;
}
