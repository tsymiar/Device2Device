package com.tsymiar.devidroid.utils;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * @author cokus
 * 将pcm转换成wav格式
 * 其实就是加入wav头
 */
class Pcm2Wav {

    public class WaveHeader {
        final char[] fileID = {'R', 'I', 'F', 'F'};

        int fileLength;

        char[] wavTag = {'W', 'A', 'V', 'E'};

        char[] FmtHdrID = {'f', 'm', 't', ' '};

        int FmtHdrLen;

        short FormatTag;

        short Channels;

        int SamplesPerSec;

        int AvgBytesPerSec;

        short BlockAlign;

        short BitsPerSample;

        char[] DataHdrID = {'d', 'a', 't', 'a'};

        int DataHdrLen;

        byte[] getHeader() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            WriteChar(bos, fileID);
            WriteInt(bos, fileLength);
            WriteChar(bos, wavTag);
            WriteChar(bos, FmtHdrID);
            WriteInt(bos, FmtHdrLen);
            WriteShort(bos, FormatTag);
            WriteShort(bos, Channels);
            WriteInt(bos, SamplesPerSec);
            WriteInt(bos, AvgBytesPerSec);
            WriteShort(bos, BlockAlign);
            WriteShort(bos, BitsPerSample);
            WriteChar(bos, DataHdrID);
            WriteInt(bos, DataHdrLen);
            bos.flush();
            byte[] r = bos.toByteArray();
            bos.close();
            return r;
        }

        private void WriteShort(ByteArrayOutputStream bos, int s)
                throws IOException {
            byte[] mybyte = new byte[2];
            mybyte[1] = (byte) ((s << 16) >> 24);
            mybyte[0] = (byte) ((s << 24) >> 24);
            bos.write(mybyte);
        }

        private void WriteInt(ByteArrayOutputStream bos, int n) throws IOException {
            byte[] buf = new byte[4];
            buf[3] = (byte) (n >> 24);
            buf[2] = (byte) ((n << 8) >> 24);
            buf[1] = (byte) ((n << 16) >> 24);
            buf[0] = (byte) ((n << 24) >> 24);
            bos.write(buf);
        }

        private void WriteChar(ByteArrayOutputStream bos, char[] id) {
            for (char c : id) {
                bos.write(c);
            }
        }
    }

    void convertAudioFiles(String src, String target) throws Exception {
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(target);


        byte[] buf = new byte[1024 * 1000];
        int size = fis.read(buf);
        int PCMSize = 0;
        while (size != -1) {
            PCMSize += size;
            size = fis.read(buf);
        }
        fis.close();

        WaveHeader header = new WaveHeader();
        header.fileLength = PCMSize + (44 - 8);
        header.FmtHdrLen = 16;
        header.BitsPerSample = 16;
        header.Channels = 1;
        header.FormatTag = 0x0001;
        header.SamplesPerSec = 16000;
        header.BlockAlign = (short) (header.Channels * header.BitsPerSample / 8);
        header.AvgBytesPerSec = header.BlockAlign * header.SamplesPerSec;
        header.DataHdrLen = PCMSize;

        byte[] h = header.getHeader();

        if (h.length != 44) {
            return;
        }
        //write header
        fos.write(h, 0, h.length);
        //write data stream
        fis = new FileInputStream(src);
        size = fis.read(buf);
        while (size != -1) {
            fos.write(buf, 0, size);
            size = fis.read(buf);
        }
        fis.close();
        fos.close();
    }
}
