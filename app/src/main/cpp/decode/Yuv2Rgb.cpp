//
// Created by Shenyrion on 2022/5/4.
//

#include "Yuv2Rgb.h"

int YUV2RGB(int y, int u, int v) {
    // Adjust and check YUV values
    y = (y - 16) < 0 ? 0 : (y - 16);
    u -= 128;
    v -= 128;
    int kMaxChannelValue = 262143;
    // This is the floating point equivalent. We do the conversion in integer
    // because some Android devices do not have floating point in hardware.
    // nR = (int)(1.164 * nY + 2.018 * nU);
    // nG = (int)(1.164 * nY - 0.813 * nV - 0.391 * nU);
    // nB = (int)(1.164 * nY + 1.596 * nV);
    int y1192 = 1192 * y;
    int r = (y1192 + 1634 * v);
    int g = (y1192 - 833 * v - 400 * u);
    int b = (y1192 + 2066 * u);

    // Clipping RGB values to be inside boundaries [ 0 , kMaxChannelValue ]
    r = r > kMaxChannelValue ? kMaxChannelValue : (r < 0 ? 0 : r);
    g = g > kMaxChannelValue ? kMaxChannelValue : (g < 0 ? 0 : g);
    b = b > kMaxChannelValue ? kMaxChannelValue : (b < 0 ? 0 : b);

    return 0xff000000 | ((r << 6) & 0xff0000) | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
}
//uvPixelStride取值为1，uvRowStride为width的一半
void convertYUV420ToARGB8888(char* yData, char* uData, char* vData, int width, int height, int yRowStride, int uvRowStride, int uvPixelStride, int* out)
{
    int yp = 0;
    for (int j = 0; j < height; j++) {
        int pY = yRowStride * j;
        int pUV = uvRowStride * (j >> 1);

        for (int i = 0; i < width; i++) {
            int uv_offset = pUV + (i >> 1) * uvPixelStride;

            out[yp++] = YUV2RGB(0xff & yData[pY + i], 0xff & uData[uv_offset], 0xff & vData[uv_offset]);
        }
    }
}

void convertYUV420ToARGB8888(char* input, int width, int height, int* output)
{
    int frameSize = width * height;
    for (int j = 0, yp = 0; j < height; j++) {
        int uvp = frameSize + (j >> 1) * width / 2;
        int vp = frameSize * 5 / 4 + (j >> 1) * width / 2;
        int u = 0;
        int v = 0;

        for (int i = 0; i < width; i++, yp++) {
            int y = 0xff & input[yp];
            if ((i & 1) == 0) {
                v = 0xff & input[vp++];
                u = 0xff & input[uvp++];
            }

            output[yp] = YUV2RGB(y, u, v);
        }
    }
}

void Yuv2Rgb::convertYUV420SPToARGB8888(char* input, int height, int width, int* output)
{
    int frameSize = width * height;
    for (int j = 0, yp = 0; j < height; j++) {
        int uvp = frameSize + (j >> 1) * width;
        int u = 0;
        int v = 0;

        for (int i = 0; i < width; i++, yp++) {
            int y = 0xff & input[yp];
            if ((i & 1) == 0) {
                v = 0xff & input[uvp++];
                u = 0xff & input[uvp++];
            }

            output[yp] = YUV2RGB(y, u, v);
        }
    }
}

void YUV420P_TO_RGB24(char* data, int* rgb, int width, int height)
{
    int index = 0;
    char* ybase = data;
    char* ubase = data + width * height;
    char* vbase = data + width * height * 5 / 4;
    for (int y = 0; y < height; y++) {
        int pY = 1920 * y;
        int pUV = 960 * (y >> 1);
        for (int x = 0; x < width; x++) {
            int uv_offset = pUV + (x >> 1) * 1;
            //YYYYYYYYUUVV
            unsigned char Y = ybase[pY + x] & 0xff;
            unsigned char U = ubase[uv_offset] & 0xff;
            unsigned char V = vbase[uv_offset] & 0xff;

            unsigned  char B = Y + 1.73322 * (U - 128); //B
            unsigned char G = Y - 0.33557 * (U - 128) - 0.6988255 * (V - 128); //G

            unsigned char R = Y + 1.3708 * (V - 128);//R

            //rgb[index++] = 0XFF;
            rgb[index++] = 0xff000000 | ((R << 16) & 0xff0000) | ((G << 8) & 0xff00) | ((B) & 0xff);

            //rgb[index++] = YUV2RGB(0xff & ybase[pY + x], 0xff & ubase[uv_offset], 0xff & vbase[uv_offset]);
        }
    }
}
