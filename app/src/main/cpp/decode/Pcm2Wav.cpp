#include <cstdarg>
#include <cstring>
#include <cstdio>

#ifndef LOG_TAG
#define LOG_TAG "Pcm2Wav"
#endif

#include <Utils/logging.h>
#include <files/FileUtils.h>

const char wavTag[] = {'W', 'A', 'V', 'E'};
const char fileID[] = {'R', 'I', 'F', 'F'};
const char fmtHdrID[] = {'f', 'm', 't', ' '};
const char dataHdrID[] = {'d', 'a', 't', 'a'};

int writeShort(char *l, int s)
{
    char byte[2];
    byte[1] = (char) (s << 16 >> 24);
    byte[0] = (char) (s << 24 >> 24);
    memset(l, s, 2);
    return 2;
}

int writeInt(char *l, int n)
{
    char buf[4];
    buf[3] = (char) (n >> 24);
    buf[2] = (char) (n << 8 >> 24);
    buf[1] = (char) (n << 16 >> 24);
    buf[0] = (char) (n << 24 >> 24);
    memcpy(l, buf, 4);
    return 4;
}

int writeChars(char *l, const char id[], size_t s)
{
    memcpy(l, id, s);
    return s;
}

class WaveHeader {
public:
    struct Content {
        char WavTag[4];
        short FormatTag;

        char FileID[4];
        char FmtHdrID[4];
        char DataHdrID[4];

        int FileLen;
        int FmtHdrLen;
        int DataHdrLen;

        short Channels;
        short BlockAlign;
        short BitsPerSample;
        int SamplesPerSec;
        int AvgBytesPerSec;
    };

    static char *getHeader(Content content)
    {
        static char header[sizeof(WaveHeader)];
        memcpy(content.WavTag, wavTag, 4);
        memcpy(content.FileID, fileID, 4);
        memcpy(content.FmtHdrID, fmtHdrID, 4);
        memcpy(content.DataHdrID, dataHdrID, 4);

        int len = writeChars(header, content.FileID, 4);
        len = writeInt(header + len, content.FileLen);
        len = writeChars(header + len, content.WavTag, 4);
        len = writeChars(header + len, content.FmtHdrID, 4);
        len = writeInt(header + len, content.FmtHdrLen);
        len = writeShort(header + len, content.FormatTag);
        len = writeShort(header + len, content.Channels);
        len = writeInt(header + len, content.SamplesPerSec);
        len = writeInt(header + len, content.AvgBytesPerSec);
        len = writeShort(header + len, content.BlockAlign);
        len = writeShort(header + len, content.BitsPerSample);
        len = writeChars(header + len, content.DataHdrID, 4);
        writeInt(header + len, content.DataHdrLen);
        return header;
    }

};

int convertAudioFiles(const char *from, const char *target)
{
    size_t len = 1024 * 1000;
    char buf[len];
    int PCMSize = 0;
    WaveHeader::Content content{};
    content.FileLen = PCMSize + (44 - 8);
    content.FmtHdrLen = 16;
    content.BitsPerSample = 16;
    content.Channels = 1;
    content.FormatTag = 0x0001;
    content.SamplesPerSec = 16000;
    content.BlockAlign = (short) (content.Channels * content.BitsPerSample / 8);
    content.AvgBytesPerSec = content.BlockAlign * content.SamplesPerSec;
    content.DataHdrLen = PCMSize;

    //write header
    size_t size = 44;
    char *h = WaveHeader::getHeader(content);
    if (h == nullptr) {
        return -1;
    }
    memcpy(buf, h, size);

    //get data from stream
    FILE *fp = fopen(from, "r");
    if (fp == nullptr) {
        LOGE("can not load '%s' file!", from);
        return -2;
    }
    int c;
    while (EOF != (c = getc(fp))) {
        memset(buf + size, c, 1);
        size++;
    }
    fclose(fp);

    //write data stream
    FileUtils::MakeDirs(target);
    fp = fopen(target, "w");
    if (fp == nullptr) {
        LOGE("can not load '%s' file!", target);
        return -4;
    }
    if (fwrite(buf, 1, size, fp)) {
        LOGE("write target file '%s' failed.", target);
        return -3;
    }
    fclose(fp);
    return 0;
}
