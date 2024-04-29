#ifndef NET_UTIL_H
#define NET_UTIL_H
#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jint JNICALL ipv6_available();
JNIEXPORT jint JNICALL ipv4_available();

typedef union {
    struct sockaddr     sa;
    struct sockaddr_in  sa4;
    struct sockaddr_in6 sa6;
} SOCKETADDRESS;

JNIEXPORT int JNICALL
NET_InetAddressToSockaddr(JNIEnv *env, jobject iaObj, int port,
                          SOCKETADDRESS *sa, int *len,
                          jboolean v4MappedAddress);

#ifdef __cplusplus
}
#endif
#endif
