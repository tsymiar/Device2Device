//
// Created by Shenyrion on 2022/5/4.
//

#ifndef DEVICE2DEVICE_YUV2RGB_H
#define DEVICE2DEVICE_YUV2RGB_H

namespace Yuv2Rgb {
    void convertYUV420SPToARGB8888(const char* input, int height, int width, unsigned char* output);
    void convertYUV420ToARGB8888(const char* input, int width, int height, int* output);
}

#endif //DEVICE2DEVICE_YUV2RGB_H
