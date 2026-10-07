/*
 * Windows BMP file functions for OpenGL.
 *
 * Written by Michael Sweet.
 */

#include "bitmap.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

#ifndef LOG_TAG
#define LOG_TAG "bitmap"
#endif
#include <utils/logging.h>

#ifdef WIN32
 /*
  * 'LoadDIBitmap()' - Load a DIB/BMP file from disk.
  *
  * Returns a pointer to the bitmap if successful, NULL otherwise...
  */

GLubyte *                          /* O - Bitmap data */
LoadDIBitmap(const char *filename, /* I - File to load */
             BITMAPINFO **info)    /* O - Bitmap information */
{
    FILE             *fp;          /* Open file pointer */
    GLubyte          *bits;        /* Bitmap pixel bits */
    int              bitsize;      /* Size of bitmap */
    int              infosize;     /* Size of header information */
    BITMAPFILEHEADER header;       /* File header */


    /* Try opening the file; use "rb" mode to read this *binary* file. */
    if ((fp = fopen(filename, "rb")) == NULL)
        return (NULL);

    /* Read the file header and any following bitmap information... */
    if (fread(&header, sizeof(BITMAPFILEHEADER), 1, fp) < 1)
    {
        /* Couldn't read the file header - return NULL... */
        fclose(fp);
        return (NULL);
    }

    if (header.bfType != 'MB')	/* Check for BM reversed... */
    {
        /* Not a bitmap file - return NULL... */
        fclose(fp);
        return (NULL);
    }

    infosize = header.bfOffBits - sizeof(BITMAPFILEHEADER);
    if ((*info = (BITMAPINFO *)malloc(infosize)) == NULL)
    {
        /* Couldn't allocate memory for bitmap info - return NULL... */
        fclose(fp);
        free(info);
        return (NULL);
    }

    if (fread(*info, 1, infosize, fp) < (unsigned)infosize)
    {
        /* Couldn't read the bitmap header - return NULL... */
        free(*info);
        free(info);
        fclose(fp);
        return (NULL);
    }

    /* Now that we have all the header info read in, allocate memory for *
     * the bitmap and read *it* in...                                    */
    if ((bitsize = (*info)->bmiHeader.biSizeImage) == 0)
        bitsize = ((*info)->bmiHeader.biWidth *
        (*info)->bmiHeader.biBitCount + 7) / 8 *
        abs((*info)->bmiHeader.biHeight);

    if ((bits = (GLubyte *)malloc(bitsize)) == NULL)
    {
        /* Couldn't allocate memory - return NULL! */
        free(*info);
        free(info);
        fclose(fp);
        return (NULL);
    }

    if (fread(bits, 1, bitsize, fp) < (unsigned)bitsize)
    {
        /* Couldn't read bitmap - free memory and return NULL! */
        free(*info);
        free(bits);
        bits = NULL;
    }

    /* OK, everything went fine - return the allocated bitmap... */
    fclose(fp);
    if (info) {
        free(info);
    }
    return (bits);
}


/*
 * 'SaveDIBitmap()' - Save a DIB/BMP file to disk.
 *
 * Returns 0 on success or -1 on failure...
 */

int                                /* O - 0 = success, -1 = failure */
SaveDIBitmap(const char *filename, /* I - File to load */
             BITMAPINFO *info,     /* I - Bitmap information */
             GLubyte    *bits)     /* I - Bitmap data */
{
    FILE             *fp;          /* Open file pointer */
    int              size,         /* Size of file */
                     infosize,     /* Size of bitmap info */
                     bitsize;      /* Size of bitmap pixels */
    BITMAPFILEHEADER header;       /* File header */


    /* Try opening the file; use "wb" mode to write this *binary* file. */
    if ((fp = fopen(filename, "wb")) == NULL)
        return (-1);

    /* Figure out the bitmap size */
    if (info->bmiHeader.biSizeImage == 0)
        bitsize = (info->bmiHeader.biWidth *
            info->bmiHeader.biBitCount + 7) / 8 *
        abs(info->bmiHeader.biHeight);
    else
        bitsize = info->bmiHeader.biSizeImage;

    /* Figure out the header size */
    infosize = sizeof(BITMAPINFOHEADER);
    switch (info->bmiHeader.biCompression)
    {
    case BI_BITFIELDS:
        infosize += 12; /* Add 3 RGB doubleword masks */
        if (info->bmiHeader.biClrUsed == 0)
            break;
    case BI_RGB:
        if (info->bmiHeader.biBitCount > 8 &&
            info->bmiHeader.biClrUsed == 0)
            break;
    case BI_RLE8:
    case BI_RLE4:
        if (info->bmiHeader.biClrUsed == 0)
            infosize += (1 << info->bmiHeader.biBitCount) * 4;
        else
            infosize += info->bmiHeader.biClrUsed * 4;
        break;
    }

    size = sizeof(BITMAPFILEHEADER) + infosize + bitsize;

    /* Write the file header, bitmap information, and bitmap pixel data... */
    header.bfType = 'MB'; /* Non-portable... sigh */
    header.bfSize = size;
    header.bfReserved1 = 0;
    header.bfReserved2 = 0;
    header.bfOffBits = sizeof(BITMAPFILEHEADER) + infosize;

    if (fwrite(&header, 1, sizeof(BITMAPFILEHEADER), fp) < sizeof(BITMAPFILEHEADER))
    {
        /* Couldn't write the file header - return... */
        fclose(fp);
        return (-1);
    }

    if (fwrite(info, 1, infosize, fp) < (unsigned)infosize)
    {
        /* Couldn't write the bitmap header - return... */
        fclose(fp);
        return (-1);
    }

    if (fwrite(bits, 1, bitsize, fp) < (unsigned)bitsize)
    {
        /* Couldn't write the bitmap - return... */
        fclose(fp);
        return (-1);
    }

    /* OK, everything went fine - return... */
    fclose(fp);
    return (0);
}


#else /* !WIN32 */
 /*
  * Functions for reading and writing 16- and 32-bit little-endian integers.
  */

static unsigned short read_word(FILE *fp);
static unsigned int   read_dword(FILE *fp);
static int            read_long(FILE *fp);

static int            write_word(FILE *fp, unsigned short w);
static int            write_dword(FILE *fp, unsigned int dw);
static int            write_long(FILE *fp, int l);


/*
 * 'LoadDIBitmap()' - Load a DIB/BMP file from disk.
 *
 * Returns a pointer to the bitmap if successful, NULL otherwise...
 */

GLubyte *                          /* O - Bitmap data */
LoadDIBitmap(const char *filename, /* I - File to load */
             BITMAPINFO **info)    /* O - Bitmap information */
{
    FILE             *fp;          /* Open file pointer */
    GLubyte          *bits;        /* Bitmap pixel bits */
    GLubyte          *ptr;         /* Pointer into bitmap */
    GLubyte          temp;         /* Temporary variable to swap red and blue */
    int              x, y;         /* X and Y position in image */
    int              length;       /* Line length */
    int              bitsize;      /* Size of bitmap */
    int              infosize;     /* Size of header information */
    BITMAPFILETYPEHEADER header;       /* File header */


    /* Try opening the file; use "rb" mode to read this *binary* file. */
    if ((fp = fopen(filename, "rb")) == NULL)
        return (NULL);

    /* Read the file header and any following bitmap information... */
    header.bfType = read_word(fp);
    header.bsHeader.bfSize = read_dword(fp);
    header.bsHeader.bfReserved1 = read_word(fp);
    header.bsHeader.bfReserved2 = read_word(fp);
    header.bsHeader.bfOffBits = read_dword(fp);

    if (header.bfType != BF_TYPE) /* Check for BM reversed... */
    {
        /* Not a bitmap file - return NULL... */
         fclose(fp);
         return (NULL);
    }

    infosize = header.bsHeader.bfOffBits - 18;
    if ((*info = (BITMAPINFO *)malloc(sizeof(BITMAPINFO))) == NULL)
    {
        /* Couldn't allocate memory for bitmap info - return NULL... */
        fclose(fp);
        return (NULL);
    }

    (*info)->bmiHeader.biSize = read_dword(fp);
    (*info)->bmiHeader.biWidth = read_dword(fp);
    (*info)->bmiHeader.biHeight = read_long(fp);
    (*info)->bmiHeader.biPlanes = read_word(fp);
    (*info)->bmiHeader.biBitCount = read_word(fp);
    (*info)->bmiHeader.biCompression = read_dword(fp);
    (*info)->bmiHeader.biSizeImage = read_dword(fp);
    (*info)->bmiHeader.biXPelsPerMeter = read_long(fp);
    (*info)->bmiHeader.biYPelsPerMeter = read_long(fp);
    (*info)->bmiHeader.biClrUsed = read_dword(fp);
    (*info)->bmiHeader.biClrImportant = read_dword(fp);

    if (infosize > 40)
        if (fread((*info)->bmiColors, infosize - 40, 1, fp) < 1)
        {
            /* Couldn't read the bitmap header - return NULL... */
            free(*info);
            fclose(fp);
            return (NULL);
        }

    /* Now that we have all the header info read in, allocate memory for *
     * the bitmap and read *it* in...                                    */
    if ((bitsize = (*info)->bmiHeader.biSizeImage) == 0)
        bitsize = ((*info)->bmiHeader.biWidth *
        (*info)->bmiHeader.biBitCount + 7) / 8 *
        abs((*info)->bmiHeader.biHeight);

    if ((bits = malloc(bitsize)) == NULL)
    {
        /* Couldn't allocate memory - return NULL! */
        free(*info);
        fclose(fp);
        return (NULL);
    }

    if (fread(bits, 1, bitsize, fp) < bitsize)
    {
        /* Couldn't read bitmap - free memory and return NULL! */
        free(*info);
        free(bits);
        fclose(fp);
        return (NULL);
    }

    /* Swap red and blue */
    length = ((*info)->bmiHeader.biWidth * 3 + 3) & ~3;
    for (y = 0; y < (*info)->bmiHeader.biHeight; y++)
        for (ptr = bits + y * length, x = (*info)->bmiHeader.biWidth;
            x > 0;
            x--, ptr += 3)
    {
        temp = ptr[0];
        ptr[0] = ptr[2];
        ptr[2] = temp;
    }

    /* OK, everything went fine - return the allocated bitmap... */
    fclose(fp);
    return (bits);
}


/*
 * 'SaveDIBitmap()' - Save a DIB/BMP file to disk.
 *
 * Returns 0 on success or -1 on failure...
 */

int                                /* O - 0 = success, -1 = failure */
SaveDIBitmap(const char *filename, /* I - File to load */
             BITMAPINFO *info,     /* I - Bitmap information */
             GLubyte    *bits)     /* I - Bitmap data */
{
    FILE    *fp;                   /* Open file pointer */
    int     size,                  /* Size of file */
        infosize,                  /* Size of bitmap info */
        bitsize;                   /* Size of bitmap pixels */
    GLubyte *ptr;                  /* Pointer into bitmap */
    GLubyte temp;                  /* Temporary variable to swap red and blue */
    int     x, y;                  /* X and Y position in image */
    int     length;                /* Line length */


    /* Try opening the file; use "wb" mode to write this *binary* file. */
    if ((fp = fopen(filename, "wb")) == NULL)
        return (-1);

    /* Figure out the bitmap size */
    if (info->bmiHeader.biSizeImage == 0)
        bitsize = (info->bmiHeader.biWidth *
            info->bmiHeader.biBitCount + 7) / 8 *
        abs(info->bmiHeader.biHeight);
    else
        bitsize = info->bmiHeader.biSizeImage;

    /* Figure out the header size */
    infosize = sizeof(BITMAPINFOHEADER);
    switch (info->bmiHeader.biCompression)
    {
    case BI_BITFIELDS:
        infosize += 12; /* Add 3 RGB doubleword masks */
        if (info->bmiHeader.biClrUsed == 0)
            break;
    case BI_RGB:
        if (info->bmiHeader.biBitCount > 8 &&
            info->bmiHeader.biClrUsed == 0)
            break;
    case BI_RLE8:
    case BI_RLE4:
        if (info->bmiHeader.biClrUsed == 0)
            infosize += (1 << info->bmiHeader.biBitCount) * 4;
        else
            infosize += info->bmiHeader.biClrUsed * 4;
        break;
    }

    size = sizeof(BITMAPFILEHEADER) + infosize + bitsize;

    /* Write the file header, bitmap information, and bitmap pixel data... */
    write_word(fp, BF_TYPE);        /* bfType */
    write_dword(fp, size);          /* bfSize */
    write_word(fp, 0);              /* bfReserved1 */
    write_word(fp, 0);              /* bfReserved2 */
    write_dword(fp, 18 + infosize); /* bfOffBits */

    write_dword(fp, info->bmiHeader.biSize);
    write_long(fp, info->bmiHeader.biWidth);
    write_long(fp, info->bmiHeader.biHeight);
    write_word(fp, info->bmiHeader.biPlanes);
    write_word(fp, info->bmiHeader.biBitCount);
    write_dword(fp, info->bmiHeader.biCompression);
    write_dword(fp, info->bmiHeader.biSizeImage);
    write_long(fp, info->bmiHeader.biXPelsPerMeter);
    write_long(fp, info->bmiHeader.biYPelsPerMeter);
    write_dword(fp, info->bmiHeader.biClrUsed);
    write_dword(fp, info->bmiHeader.biClrImportant);

    if (infosize > 40)
        if (fwrite(info->bmiColors, infosize - 40, 1, fp) < 1)
        {
            /* Couldn't write the bitmap color palette - return... */
            fclose(fp);
            return (-1);
        }

    /* Swap red and blue */
    length = (info->bmiHeader.biWidth * 3 + 3) & ~3;
    for (y = 0; y < info->bmiHeader.biHeight; y++)
        for (ptr = bits + y * length, x = info->bmiHeader.biWidth;
            x > 0;
            x--, ptr += 3)
    {
        temp = ptr[0];
        ptr[0] = ptr[2];
        ptr[2] = temp;
    }

    if (fwrite(bits, 1, bitsize, fp) < bitsize)
    {
        /* Couldn't write the bitmap - return... */
        fclose(fp);
        return (-1);
    }

    /* OK, everything went fine - return... */
    fclose(fp);
    return (0);
}


/*
 * 'read_word()' - Read a 16-bit unsigned integer.
 */

static unsigned short     /* O - 16-bit unsigned integer */
read_word(FILE *fp)       /* I - File to read from */
{
    unsigned char b0, b1; /* Bytes from file */

    b0 = getc(fp);
    b1 = getc(fp);

    return ((b1 << 8) | b0);
}


/*
 * 'read_dword()' - Read a 32-bit unsigned integer.
 */

static unsigned int               /* O - 32-bit unsigned integer */
read_dword(FILE *fp)              /* I - File to read from */
{
    unsigned char b0, b1, b2, b3; /* Bytes from file */

    b0 = getc(fp);
    b1 = getc(fp);
    b2 = getc(fp);
    b3 = getc(fp);

    return ((((((b3 << 8) | b2) << 8) | b1) << 8) | b0);
}


/*
 * 'read_long()' - Read a 32-bit signed integer.
 */

static int                        /* O - 32-bit signed integer */
read_long(FILE *fp)               /* I - File to read from */
{
    unsigned char b0, b1, b2, b3; /* Bytes from file */

    b0 = getc(fp);
    b1 = getc(fp);
    b2 = getc(fp);
    b3 = getc(fp);

    return ((int)(((((b3 << 8) | b2) << 8) | b1) << 8) | b0);
}


/*
 * 'write_word()' - Write a 16-bit unsigned integer.
 */

static int                     /* O - 0 on success, -1 on error */
write_word(FILE           *fp, /* I - File to write to */
           unsigned short w)   /* I - Integer to write */
{
    putc(w, fp);
    return (putc(w >> 8, fp));
}


/*
 * 'write_dword()' - Write a 32-bit unsigned integer.
 */

static int                    /* O - 0 on success, -1 on error */
write_dword(FILE         *fp, /* I - File to write to */
            unsigned int dw)  /* I - Integer to write */
{
    putc(dw, fp);
    putc(dw >> 8, fp);
    putc(dw >> 16, fp);
    return (putc(dw >> 24, fp));
}


/*
 * 'write_long()' - Write a 32-bit signed integer.
 */

static int           /* O - 0 on success, -1 on error */
write_long(FILE *fp, /* I - File to write to */
           int  l)   /* I - Integer to write */
{
    putc(l, fp);
    putc(l >> 8, fp);
    putc(l >> 16, fp);
    return (putc(l >> 24, fp));
}

/*
 * 'BitmapToRgba()' - 把一张 24/32 位 BMP 解成 RGBA（自上而下，alpha 不透明）。
 *
 * 成功时 prop.blSize > 0（= 宽*高*4），*pRgba 指向 malloc 出来的缓冲，由调用方 free。
 * 失败时 blSize 为负： -1 打不开 / -2 不是 BMP / -3 尺寸非法 /
 *                    -4 压缩格式不支持 / -5 位深不支持 / -6 读不满 / -7 内存不足。
 *
 * 解码时一步做完三件事：BGR(A) → RGBA、bottom-up 翻转、alpha 补成不透明。
 * 行步进按 4 字节对齐算（stride），行末的 padding 不参与像素读取。
 */
BITMAPPROP BitmapToRgba(const char *filename, unsigned char **pRgba)
{
    BITMAPPROP prop;
    prop.biWidth = 0;
    prop.biHeight = 0;
    prop.blSize = -1;

    if (filename == NULL || pRgba == NULL) {
        return prop;
    }
    *pRgba = NULL;

    FILE *fpBmp = fopen(filename, "rb");
    if (fpBmp == NULL) {
        LOGE("the bmp file can not open: %s", filename);
        return prop;
    }

    unsigned short fileType = 0;
    if (fread(&fileType, 1, sizeof(unsigned short), fpBmp) != sizeof(unsigned short)) {
        prop.blSize = -2;
        fclose(fpBmp);
        return prop;
    }
    if (fileType != BF_TYPE) {
        LOGE("file type(0x%x) error, not a BMP!", fileType);
        prop.blSize = -2;
        fclose(fpBmp);
        return prop;
    }

    BITMAPFILEHEADER bmpHeader;
    if (fread(&bmpHeader, 1, sizeof(BITMAPFILEHEADER), fpBmp) != sizeof(BITMAPFILEHEADER)) {
        LOGE("bmp file header truncated");
        prop.blSize = -2;
        fclose(fpBmp);
        return prop;
    }

    /* BITMAPINFOHEADER 里 biXPelsPerMeter/biYPelsPerMeter 是 long，在 64 位上被撑到 8 字节，
       整个结构 sizeof 变成 48 —— 而文件里的信息头只有 40 字节。
       按 sizeof 读会多啃掉 8 个字节，头小的文件直接读不满就失败了。
       这里按标准的 40 字节读，前 24 字节（biSize..biSizeImage）的偏移与结构体一致，
       直接拷进去就够用。 */
    unsigned char ih[40];
    BITMAPINFOHEADER bmpInfHeader;
    memset(&bmpInfHeader, 0, sizeof(bmpInfHeader));
    if (fread(ih, 1, sizeof(ih), fpBmp) != sizeof(ih)) {
        LOGE("bmp info header truncated");
        prop.blSize = -2;
        fclose(fpBmp);
        return prop;
    }
    memcpy(&bmpInfHeader, ih, 24);

    int width = (int) bmpInfHeader.biWidth;
    int height = (int) bmpInfHeader.biHeight;   /* 负数表示自上而下存储 */
    if (width <= 0 || height == 0) {
        LOGE("the bmp size invalid: [%d]x[%d]!", height, width);
        prop.blSize = -3;
        fclose(fpBmp);
        return prop;
    }
    int topDown = (height < 0);
    if (topDown) height = -height;

    /* BI_BITFIELDS 只是多了 3 个掩码，像素排布与 BI_RGB 一致，可以按 BI_RGB 读 */
    if (bmpInfHeader.biCompression != BI_RGB && bmpInfHeader.biCompression != BI_BITFIELDS) {
        LOGE("bmp compression %u not supported (only BI_RGB)", bmpInfHeader.biCompression);
        prop.blSize = -4;
        fclose(fpBmp);
        return prop;
    }
    int bpp = bmpInfHeader.biBitCount;
    if (bpp != 24 && bpp != 32) {
        LOGE("bmp bit count %d not supported (only 24/32)", bpp);
        prop.blSize = -5;
        fclose(fpBmp);
        return prop;
    }

    /* 每行按 4 字节对齐补齐，这个 padding 不能当成像素读 */
    size_t stride = (((size_t) width * (size_t) (bpp / 8)) + 3u) & ~((size_t) 3u);
    size_t rawSize = stride * (size_t) height;
    unsigned char *raw = (unsigned char *) malloc(rawSize);
    if (raw == NULL) {
        LOGE("malloc %zu bytes for bmp raw failed", rawSize);
        prop.blSize = -7;
        fclose(fpBmp);
        return prop;
    }

    if (fseek(fpBmp, (long) bmpHeader.bfOffBits, SEEK_SET) != 0) {
        LOGE("bmp seek to %u failed", bmpHeader.bfOffBits);
        free(raw);
        prop.blSize = -6;
        fclose(fpBmp);
        return prop;
    }
    size_t got = fread(raw, 1, rawSize, fpBmp);
    fclose(fpBmp);
    if (got != rawSize) {
        LOGE("bmp size not match: [%zu][%zu]", got, rawSize);
        free(raw);
        prop.blSize = -6;
        return prop;
    }

    size_t outSize = (size_t) width * (size_t) height * 4u;
    unsigned char *rgba = (unsigned char *) malloc(outSize);
    if (rgba == NULL) {
        LOGE("malloc %zu bytes for rgba failed", outSize);
        free(raw);
        prop.blSize = -7;
        return prop;
    }

    /* 边读边做 BGR(A)→RGBA 和上下翻转，不再经由中间那份 3 通道缓冲 */
    for (int y = 0; y < height; y++) {
        const unsigned char *src = raw + (size_t) (topDown ? y : (height - 1 - y)) * stride;
        unsigned char *dst = rgba + (size_t) y * (size_t) width * 4u;
        for (int x = 0; x < width; x++) {
            unsigned char b = src[0];
            unsigned char g = src[1];
            unsigned char r = src[2];
            dst[0] = r;
            dst[1] = g;
            dst[2] = b;
            /* 24 位 BMP 不带 alpha：不补成 FF 的话整张图就是全透明的 */
            dst[3] = (bpp == 32) ? src[3] : 0xFF;
            src += bpp / 8;
            dst += 4;
        }
    }
    free(raw);

    prop.biWidth = (unsigned int) width;
    prop.biHeight = (unsigned int) height;
    prop.blSize = (long) outSize;
    *pRgba = rgba;
    LOGI("bmp [%s] decoded %dx%d, %ld bytes", filename, width, height, prop.blSize);
    return prop;
}

#endif /* WIN32 */
