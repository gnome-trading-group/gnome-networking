#ifndef JNI_UTIL_H
#define JNI_UTIL_H
#ifdef __cplusplus
extern "C" {
#endif

#define JNU_JAVAPKG         "java/lang/"
#define JNU_JAVAIOPKG       "java/io/"
#define JNU_JAVANETPKG      "java/net/"

JNIEXPORT void JNICALL
JNU_ThrowByNameWithLastError(JNIEnv *env, const char *name,
                             const char *defaultDetail);

JNIEXPORT void JNICALL
JNU_ThrowByName(JNIEnv *env, const char *name, const char *msg);

JNIEXPORT void JNICALL
JNU_ThrowNullPointerException(JNIEnv *env, const char *msg);

JNIEXPORT void JNICALL
JNU_ThrowIOExceptionWithLastError(JNIEnv *env, const char *defaultDetail);

JNIEXPORT void JNICALL
JNU_ThrowByNameWithMessageAndLastError
  (JNIEnv *env, const char *name, const char *message);

#ifdef __cplusplus
}
#endif
#endif