#include <fcntl.h>
#include <jni.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include <iostream>

#include "include/jlong_md.h"
#include "include/jni_util.h"
#include "include/net_util.h"
#include "include/nio.h"
#include "include/socket_util.h"

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

#if defined(SO_NOSIGPIPE)
    int noSigPipe = 1;
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &noSigPipe, sizeof(noSigPipe));
#endif

    return fd;
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSocket
 * Method:    connect0
 * Signature: (ILjava/net/InetAddress;II)I
 */
JNIEXPORT jint JNICALL
Java_group_gnometrading_networking_sockets_NativeSocket_connect0(
    JNIEnv *env, jobject cl, jint fd, jobject iao, jint port, jint timeoutMillis) {
    // The fd stays non-blocking: blocking reads are emulated with poll so that every read and write can be
    // bounded by a deadline (macOS ignores MSG_DONTWAIT on sends to a blocking socket).
    if (connectWithTimeout(env, fd, iao, port, deadlineAfter(timeoutMillis)) != 0) {
        return IOS_THROWN;
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
        int result = close(fd);  // TODO: Should we use shutdown instead? For now no.
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
    JNIEnv *env, jobject, jint fd, jlong address, jint len, jboolean blocking, jint timeoutMillis) {
    if (len <= 0) {
        return 0;
    }
    void *buf = (void *)jlong_to_ptr(address);
    const long deadline = blocking ? deadlineAfter(timeoutMillis) : 0;
    while (true) {
        const ssize_t n = read(fd, buf, len);
        if (n > 0) {
            return (jint)n;
        }
        if (n == 0) {
            return -1;  // EOF - connection closed by peer
        }
        if (errno == EINTR) {
            continue;
        }
        if (errno != EAGAIN && errno != EWOULDBLOCK) {
            throwWithErrno(env, JNU_JAVAIOPKG "IOException", "Read failed", errno);
            return IOS_THROWN;
        }
        if (!blocking) {
            return 0;
        }
        const int ready = pollUntil(fd, POLLIN, deadline);
        if (ready == POLL_TIMEOUT) {
            return 0;
        }
        if (ready == POLL_ERROR) {
            throwWithErrno(env, JNU_JAVAIOPKG "IOException", "Read failed", errno);
            return IOS_THROWN;
        }
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
                                                               jlong address, jint len, jint timeoutMillis) {
    // Writes all |len| bytes or throws: a request or frame cut off part way would corrupt the stream.
    const char *buf = (const char *)jlong_to_ptr(address);
    const long deadline = deadlineAfter(timeoutMillis);
    jint written = 0;
    while (written < len) {
#if defined(MSG_NOSIGNAL)
        const ssize_t n = send(fd, buf + written, len - written, MSG_NOSIGNAL | MSG_DONTWAIT);
#else
        const ssize_t n = send(fd, buf + written, len - written, MSG_DONTWAIT);
#endif
        if (n > 0) {
            written += (jint)n;
            continue;
        }
        if (n < 0 && errno == EINTR) {
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            const int ready = pollUntil(fd, POLLOUT, deadline);
            if (ready == POLL_READY) {
                continue;
            }
            if (ready == POLL_TIMEOUT) {
                JNU_ThrowByName(env, JNU_JAVANETPKG "SocketTimeoutException", "Write timed out");
            } else {
                throwWithErrno(env, JNU_JAVAIOPKG "IOException", "Write failed", errno);
            }
            return IOS_THROWN;
        }
        throwWithErrno(env, JNU_JAVAIOPKG "IOException", "Write failed", n < 0 ? errno : EIO);
        return IOS_THROWN;
    }
    return written;
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSocket
 * Method:    setKeepAlive0
 * Signature: (IZ)V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSocket_setKeepAlive0
  (JNIEnv* env, jobject obj, jint fd, jboolean on) {
    int optval = on ? 1 : 0;
    if (setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &optval, sizeof(optval)) < 0) {
        JNU_ThrowIOExceptionWithLastError(env, "Set keep alive failed");
    }
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSocket
 * Method:    setReceiveBufferSize0
 * Signature: (II)V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSocket_setReceiveBufferSize0
  (JNIEnv* env, jobject obj, jint fd, jint size) {
    if (setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &size, sizeof(size)) < 0) {
        JNU_ThrowIOExceptionWithLastError(env, "Set receive buffer size failed");
    }
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSocket
 * Method:    setSendBufferSize0
 * Signature: (II)V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSocket_setSendBufferSize0
  (JNIEnv* env, jobject obj, jint fd, jint size) {
    if (setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &size, sizeof(size)) < 0) {
        JNU_ThrowIOExceptionWithLastError(env, "Set send buffer size failed");
    }
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSocket
 * Method:    setTcpNoDelay0
 * Signature: (IZ)V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSocket_setTcpNoDelay0
  (JNIEnv* env, jobject obj, jint fd, jboolean on) {
    int optval = on ? 1 : 0;
    if (setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &optval, sizeof(optval)) < 0) {
        JNU_ThrowIOExceptionWithLastError(env, "Set TCP no delay failed");
    }
}

JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSocket_setKeepAliveOptions0
  (JNIEnv* env, jobject obj, jint fd, jint idleSeconds, jint intervalSeconds, jint count) {
    setKeepAliveOptions(env, fd, idleSeconds, intervalSeconds, count);
}

JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSocket_setBusyPoll0
  (JNIEnv* env, jobject obj, jint fd, jint micros) {
    setBusyPoll(env, fd, micros);
}

JNIEXPORT jint JNICALL Java_group_gnometrading_networking_sockets_NativeSocket_probe0
  (JNIEnv* env, jobject obj, jint fd) {
    return probeConnection(fd);
}

#ifdef __cplusplus
}
#endif
#endif
