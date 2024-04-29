#include <fcntl.h>
#include <jni.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <iostream>

#include "include/jlong_md.h"
#include "include/jni_util.h"
#include "include/net_util.h"
#include "include/nio.h"
#include "include/nio_util.h"

#ifndef _Included_group_gnometrading_networking_sockets_NativeSocket
#define _Included_group_gnometrading_networking_sockets_NativeSocket
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     group_gnometrading_networking_sockets_NativeSocket
 * Method:    init
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_group_gnometrading_networking_sockets_NativeSocket_init(JNIEnv *, jclass) {
    return;
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSocket
 * Method:    socket
 * Signature: (ZZ)I
 */
JNIEXPORT jint JNICALL
Java_group_gnometrading_networking_sockets_NativeSocket_socket(JNIEnv *env,
                                                               jobject cl,
                                                               jboolean stream,
                                                               jboolean reuse) {
    int fd;
    int type = (stream ? SOCK_STREAM : SOCK_DGRAM);
    int domain = ipv6_available() ? AF_INET6 : AF_INET;

    fd = socket(domain, type, 0);
    if (fd < 0) {
        return handleSocketError(env, errno);
    }

    if (domain == AF_INET6 && ipv4_available()) {
        int arg = 0;
        if (setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, (char *)&arg,
                       sizeof(int)) < 0) {
            JNU_ThrowByNameWithLastError(env, JNU_JAVANETPKG "SocketException",
                                         "Unable to set IPV6_V6ONLY");
            close(fd);
            return -1;
        }
    }

    if (reuse) {
        int arg = 1;
        if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, (char *)&arg,
                       sizeof(arg)) < 0) {
            JNU_ThrowByNameWithLastError(env, JNU_JAVANETPKG "SocketException",
                                         "Unable to set SO_REUSEADDR");
            close(fd);
            return -1;
        }
    }

#if defined(__linux__)
    if (type == SOCK_DGRAM) {
        int arg = 0;
        int level = (domain == AF_INET6) ? IPPROTO_IPV6 : IPPROTO_IP;
        if ((setsockopt(fd, level, IP_MULTICAST_ALL, (char *)&arg,
                        sizeof(arg)) < 0) &&
            (errno != ENOPROTOOPT)) {
            JNU_ThrowByNameWithLastError(env, JNU_JAVANETPKG "SocketException",
                                         "Unable to set IP_MULTICAST_ALL");
            close(fd);
            return -1;
        }
    }

    if (domain == AF_INET6 && type == SOCK_DGRAM) {
        /* By default, Linux uses the route default */
        int arg = 1;
        if (setsockopt(fd, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, &arg,
                       sizeof(arg)) < 0) {
            JNU_ThrowByNameWithLastError(env, JNU_JAVANETPKG "SocketException",
                                         "Unable to set IPV6_MULTICAST_HOPS");
            close(fd);
            return -1;
        }

        /* Disable IPV6_MULTICAST_ALL if option supported */
        arg = 0;
        if ((setsockopt(fd, IPPROTO_IPV6, IPV6_MULTICAST_ALL, (char *)&arg,
                        sizeof(arg)) < 0) &&
            (errno != ENOPROTOOPT)) {
            JNU_ThrowByNameWithLastError(env, JNU_JAVANETPKG "SocketException",
                                         "Unable to set IPV6_MULTICAST_ALL");
            close(fd);
            return -1;
        }
    }
#endif

#ifdef __APPLE__
    /**
     * Attempt to set SO_SNDBUF to a minimum size to allow sending large
     * datagrams (net.inet.udp.maxdgram defaults to 9216).
     */
    if (type == SOCK_DGRAM) {
        int size;
        socklen_t arglen = sizeof(size);
        if (getsockopt(fd, SOL_SOCKET, SO_SNDBUF, &size, &arglen) == 0) {
            int minSize = (domain == AF_INET6) ? 65527 : 65507;
            if (size < minSize) {
                setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &minSize,
                           sizeof(minSize));
            }
        }
    }
#endif

    return fd;
}

#define CHECK_NULL(expr)  \
    if (expr == NULL) {   \
        return JNI_FALSE; \
    }

/*
 * Class:     group_gnometrading_networking_sockets_NativeSocket
 * Method:    connect0
 * Signature: (ILjava/net/InetAddress;I)I
 */
JNIEXPORT jint JNICALL
Java_group_gnometrading_networking_sockets_NativeSocket_connect0(
    JNIEnv *env, jobject cl, jint fd, jobject iao, jint port) {
    SOCKETADDRESS sa;
    int sa_len = 0;
    int rv;

    if (NET_InetAddressToSockaddr(env, iao, port, &sa, &sa_len, JNI_TRUE) !=
        0) {
        std::cout << "THROWN" << std::endl;
        return IOS_THROWN;
    }

    rv = connect(fd, &sa.sa, sa_len);
    if (rv != 0) {
        if (errno == EINPROGRESS) {
            return IOS_UNAVAILABLE;
        } else if (errno == EINTR) {
            return IOS_INTERRUPTED;
        }
        return handleSocketError(env, errno);
    }
    return 1;
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSocket
 * Method:    close0
 * Signature: (I)V
 */
JNIEXPORT void JNICALL
Java_group_gnometrading_networking_sockets_NativeSocket_close0(JNIEnv *env,
                                                               jobject,
                                                               jint fd) {
    if (fd != -1) {
        int result =
            close(fd);  // TODO: Should we use shutdown instead? For now no.
        if (result < 0) JNU_ThrowIOExceptionWithLastError(env, "Close failed");
    }
    return;
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSocket
 * Method:    read0
 * Signature: (IJII)I
 */
JNIEXPORT jint JNICALL
Java_group_gnometrading_networking_sockets_NativeSocket_read0(
    JNIEnv *env, jobject, jint fd, jlong address, jint len) {
    void *buf = (void *)jlong_to_ptr(address);
    jint n = read(fd, buf, len);
    if ((n == -1) && (errno == ECONNRESET || errno == EPIPE)) {
        JNU_ThrowByName(env, "sun/net/ConnectionResetException",
                        "Connection reset");
        return IOS_THROWN;
    } else {
        return convertReturnVal(env, n, JNI_TRUE);
    }
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSocket
 * Method:    write0
 * Signature: (IJII)I
 */
JNIEXPORT jint JNICALL
Java_group_gnometrading_networking_sockets_NativeSocket_write0(JNIEnv *env,
                                                               jobject, jint fd,
                                                               jlong address, jint len) {
    void *buf = (void *)jlong_to_ptr(address);

    return convertReturnVal(env, write(fd, buf, len), JNI_FALSE);
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSocket
 * Method:    configureBlocking0
 * Signature: (IZ)V
 */
JNIEXPORT void JNICALL
Java_group_gnometrading_networking_sockets_NativeSocket_configureBlocking0(
    JNIEnv *env, jobject, jint fd, jboolean blocking) {
    if (configureBlocking(fd, blocking) < 0)
        JNU_ThrowIOExceptionWithLastError(env, "Configure blocking failed");
}

static int configureBlocking(int fd, jboolean blocking) {
    int flags = fcntl(fd, F_GETFL);
    int newflags = blocking ? (flags & ~O_NONBLOCK) : (flags | O_NONBLOCK);

    return (flags == newflags) ? 0 : fcntl(fd, F_SETFL, newflags);
}

static jint handleSocketErrorWithMessage(JNIEnv *env, jint errorValue,
                                         const char *message) {
    char *xn;
    switch (errorValue) {
        case EINPROGRESS: /* Non-blocking connect */
            return 0;
#ifdef EPROTO
        case EPROTO:
            xn = JNU_JAVANETPKG "ProtocolException";
            break;
#endif
        case ECONNREFUSED:
        case ETIMEDOUT:
        case ENOTCONN:
            xn = JNU_JAVANETPKG "ConnectException";
            break;

        case EHOSTUNREACH:
            xn = JNU_JAVANETPKG "NoRouteToHostException";
            break;
        case EADDRINUSE: /* Fall through */
        case EADDRNOTAVAIL:
        case EACCES:
            xn = JNU_JAVANETPKG "BindException";
            break;
        default:
            xn = JNU_JAVANETPKG "SocketException";
            break;
    }
    errno = errorValue;
    if (message == NULL) {
        JNU_ThrowByNameWithLastError(env, xn, "NioSocketError");
    } else {
        JNU_ThrowByNameWithMessageAndLastError(env, xn, message);
    }
    return IOS_THROWN;
}

jint handleSocketError(JNIEnv *env, jint errorValue) {
    return handleSocketErrorWithMessage(env, errorValue, NULL);
}

jint convertReturnVal(JNIEnv *env, jint n, jboolean reading) {
    if (n > 0) /* Number of bytes written */
        return n;
    else if (n == 0) {
        if (reading) {
            return IOS_EOF; /* EOF is -1 in javaland */
        } else {
            return 0;
        }
    } else if (errno == EAGAIN || errno == EWOULDBLOCK)
        return IOS_UNAVAILABLE;
    else if (errno == EINTR)
        return IOS_INTERRUPTED;
    else {
        const char *msg = reading ? "Read failed" : "Write failed";
        JNU_ThrowIOExceptionWithLastError(env, msg);
        return IOS_THROWN;
    }
}

#ifdef __cplusplus
}
#endif
#endif
