//
// Created by Shenyrion on 2022/5/4.
//

#ifndef DEVIDROID_YUV2RGB_H
#define DEVIDROID_YUV2RGB_H


namespace Yuv2Rgb {
    void convertYUV420SPToARGB8888(char* input, int height, int width, int* output);
}


#endif //DEVIDROID_YUV2RGB_H
