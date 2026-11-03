#ifndef LSB_TEST_NATIVE_WINDOW_H
#define LSB_TEST_NATIVE_WINDOW_H
#include <jni.h>
#include <stdint.h>
typedef struct ANativeWindow { int unused; } ANativeWindow;
typedef struct ANativeWindow_Buffer { int32_t width,height,stride,format;void *bits;uint32_t reserved[6]; } ANativeWindow_Buffer;
#define WINDOW_FORMAT_RGBA_8888 1
ANativeWindow *ANativeWindow_fromSurface(JNIEnv *,jobject);
int ANativeWindow_setBuffersGeometry(ANativeWindow *,int32_t,int32_t,int32_t);
int ANativeWindow_lock(ANativeWindow *,ANativeWindow_Buffer *,void *);
int ANativeWindow_unlockAndPost(ANativeWindow *);
void ANativeWindow_release(ANativeWindow *);
#endif
