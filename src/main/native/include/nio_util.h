#ifndef NIO_UTIL_H
#define NIO_UTIL_H
#ifdef __cplusplus
extern "C" {
#endif

jint handleSocketError(JNIEnv *env, jint errorValue);

jint convertReturnVal(JNIEnv *env, jint n, jboolean reading);

static int configureBlocking(int fd, jboolean blocking);

#ifdef __cplusplus
}
#endif
#endif