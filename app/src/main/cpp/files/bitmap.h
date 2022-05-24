/*
 * Windows BMP file definitions for OpenGL.
 *
 * Written by Michael Sweet.
 */

#ifndef _BITMAP_H_
#  define _BITMAP_H_

 /*
  * Include necessary headers.
  */
#ifndef GLubyte
typedef unsigned char GLubyte;
#else
#  include "GL/glut.h"
#endif

#  ifdef WIN32
#    include <windows.h>
#    include <wingdi.h>
#  endif /* WIN32 */

  /*
   * Make this header file work with C and C++ source code...
   */

#  ifdef __cplusplus
extern "C" {
#  endif /* __cplusplus */


    /*
     * Bitmap file data structures (these are defined in <wingdi.h> under
     * Windows...)
     *
     * Note that most Windows compilers will pack the following structures, so
     * when reading them under MacOS or UNIX we need to read individual fields
     * to avoid differences in alignment...
     */

#  ifndef WIN32
    typedef struct                       /**** BMP file header structure ****/
    {
        // unsigned short bfType;        /* Magic number for file */
        unsigned int   bfSize;           /* Size of file */
        unsigned short bfReserved1;      /* Reserved */
        unsigned short bfReserved2;      /* ... */
        unsigned int   bfOffBits;        /* Offset to bitmap data */
    } BITMAPFILEHEADER;

    typedef struct
    {
        unsigned short bfType;           /* Magic number for file */
        BITMAPFILEHEADER bsHeader;
    } BITMAPFILETYPEHEADER;

#  define BF_TYPE 0x4D42                 /* "MB" */

    typedef struct                       /**** BMP file info structure ****/
    {
        unsigned int   biSize;           /* Size of info header */
        unsigned int   biWidth;          /* Width of image */
        unsigned int   biHeight;         /* Height of image */
        unsigned short biPlanes;         /* Number of color planes */
        unsigned short biBitCount;       /* Number of bits per pixel */
        unsigned int   biCompression;    /* Type of compression to use */
        unsigned int   biSizeImage;      /* Size of image data */
        long           biXPelsPerMeter;  /* X pixels per meter */
        long           biYPelsPerMeter;  /* Y pixels per meter */
        unsigned int   biClrUsed;        /* Number of colors used */
        unsigned int   biClrImportant;   /* Number of important colors */
    } BITMAPINFOHEADER;

    /*
     * Constants for the biCompression field...
     */

#  define BI_RGB       0             /* No compression - straight BGR data */
#  define BI_RLE8      1             /* 8-bit run-length compression */
#  define BI_RLE4      2             /* 4-bit run-length compression */
#  define BI_BITFIELDS 3             /* RGB bitmap with RGB masks */

    typedef struct                       /**** Colormap entry structure ****/
    {
        unsigned char  rgbBlue;          /* Blue value */
        unsigned char  rgbGreen;         /* Green value */
        unsigned char  rgbRed;           /* Red value */
        unsigned char  rgbReserved;      /* Reserved */
    } RGBQUAD;

    typedef struct                       /**** Bitmap information structure ****/
    {
        BITMAPINFOHEADER bmiHeader;      /* Image header */
        RGBQUAD          bmiColors[256]; /* Image colormap */
    } BITMAPINFO;
#  endif /* !WIN32 */

    /*
     * Prototypes...
     */

    typedef struct
    {
        unsigned int biWidth;
        unsigned int biHeight;
        long blSize;
    } BITMAPPROP;

    extern GLubyte *LoadDIBitmap(const char *filename, BITMAPINFO **info);
    extern int SaveDIBitmap(const char *filename, BITMAPINFO *info, GLubyte *bits);
    extern BITMAPPROP BitmapToRgba(const char *filename, unsigned char **pRgba);

#  ifdef __cplusplus
}
#  endif /* __cplusplus */
#endif /* !_BITMAP_H_ */
