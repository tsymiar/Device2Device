//
// Created by Shenyrion on 2022/5/3.
//

#include "FileUtils.h"

#ifndef LOG_TAG
#define LOG_TAG "FileUtils"
#endif
#include <utils/logging.h>
#include <cerrno>
#include <unistd.h>
#include <sys/stat.h>
#include <vector>

int FileUtils::MakeDirs(const char *fullPath)
{
    size_t len = strlen(fullPath) + 1;
    char *pszDir = new char(len);
    memcpy(pszDir, fullPath, len);
    int iLen = strlen(pszDir);
    if (access(fullPath, F_OK) == 0) {
        LOGI("fullPath '%s' already exist.", fullPath);
        delete pszDir;
        return 1;
    }
    if (pszDir[iLen - 1] != '\\' && pszDir[iLen - 1] != '/') {
        pszDir[iLen] = '/';
        pszDir[iLen + 1] = '\0';
    }
    for (int i = 0; i <= iLen; i++) {
        if (pszDir[i] == '\\' || pszDir[i] == '/') {
            pszDir[i] = '\0';
            int iRet = access(pszDir, 0);
            if (iRet != 0) {
                iRet = mkdir(pszDir, 0755);
                if (iRet != 0) {
                    delete pszDir;
                    return -1;
                }
            }
            pszDir[i] = '/';
        }
    }
    delete pszDir;
    return 0;
}

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

long FileUtils::ReadBinaryFile(const std::string& filename, size_t sliceSize, FileCallback callback)
{
    using namespace std;
    /**
     * 按 sliceSize 切片读文件，每片回调一次（渲染一帧）。
     * 返回渲染出去的片数；<0 表示失败。
     *
     * 缓冲在堆上分配（栈上放几 MB 的帧会爆栈），循环由 gcount() 驱动：
     * 只看 fin.read() 的返回值的话，读到 0 字节时循环条件恒真会卡死。
     */
    if (sliceSize == 0) {
        LOGE("sliceSize is 0 for [%s]", filename.c_str());
        return -1;
    }
    ifstream fin;
    fin.open(filename, ios_base::binary);
    if (!fin.is_open())
    {
        LOGE("Error In Open[%s]...", filename.c_str());
        return -1;
    }

    fin.seekg(0, ios::end);
    long fileSize = fin.tellg();
    fin.seekg(0, ios::beg);
    if (fileSize <= 0) {
        LOGE("Empty or unreadable file[%s], size = %ld", filename.c_str(), fileSize);
        fin.close();
        return -2;
    }

    std::vector<uint8_t> frame(sliceSize);
    long remain = fileSize;
    long slices = 0;
    while (remain > 0)
    {
        size_t want = (static_cast<size_t>(remain) < sliceSize) ? static_cast<size_t>(remain) : sliceSize;
        fin.read(reinterpret_cast<char*>(frame.data()), static_cast<std::streamsize>(want));
        std::streamsize got = fin.gcount();
        if (got <= 0) {
            break;
        }
        if (callback != nullptr) {
            callback(frame.data(), static_cast<size_t>(got));
        }
        remain -= static_cast<long>(got);
        slices++;
    }

    fin.close();
    LOGI("ReadBinaryFile[%s]: %ld bytes, %ld slice(s) of %zu", filename.c_str(), fileSize, slices, sliceSize);
    return slices;
}
