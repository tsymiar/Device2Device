#ifndef IPCROID_JNICOMM_H
#define IPCROID_JNICOMM_H
#define JNI_WRAPPER(func) Java_com_tsymiar_ipcroid_JniWrapper_##func
#define CPP_FUNC_CALL(func) JNICALL JNI_WRAPPER(func)

#include <jni.h>

#endif //IPCROID_JNICOMM_H
