#include <fcntl.h>
#include <jni.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>
#include <openssl/ssl.h>
#include <openssl/err.h>
#include <iostream>

#include "include/jlong_md.h"
#include "include/jni_util.h"
#include "include/net_util.h"
#include "include/nio.h"
#include "include/socket_util.h"

#ifndef _Included_group_gnometrading_networking_sockets_NativeSSLSocket
#define _Included_group_gnometrading_networking_sockets_NativeSSLSocket
#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int socket_fd;
    SSL *ssl;
    SSL_CTX *ctx;
} SSLSocketState;

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    init
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_init(JNIEnv *, jclass) {
    SSL_library_init();
    SSL_load_error_strings();
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    socket
 * Signature: (ZZ)I
 */
JNIEXPORT jlong JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_socket(JNIEnv *env, jobject cl, jboolean stream, jboolean reuse) {
    SSLSocketState *state = (SSLSocketState*) malloc(sizeof(SSLSocketState));
    if (!state) {
        return handleSocketErrorWithMessage(env, -1, "Failed to allocate memory for SSLSocketState");
    }

    if (state->ctx = SSL_CTX_new(TLS_client_method())) {
        int type = (stream ? SOCK_STREAM : SOCK_DGRAM);
        int domain = ipv6_available() ? AF_INET6 : AF_INET;

        state->socket_fd = socket(domain, type, 0);
        if (state->socket_fd < 0) {
            SSL_CTX_free(state->ctx);
            free(state);
            return handleSocketError(env, errno);
        }

        if (domain == AF_INET6 && ipv4_available()) {
            int arg = 0;
            if (setsockopt(state->socket_fd, IPPROTO_IPV6, IPV6_V6ONLY, (char *)&arg,
                           sizeof(int)) < 0) {
                JNU_ThrowByNameWithLastError(env, JNU_JAVANETPKG "SocketException",
                                             "Unable to set IPV6_V6ONLY");
                close(state->socket_fd);
                SSL_CTX_free(state->ctx);
                free(state);
                return -1;
            }
        }

        if (reuse) {
            int arg = 1;
            if (setsockopt(state->socket_fd, SOL_SOCKET, SO_REUSEADDR, (char *)&arg,
                           sizeof(arg)) < 0) {
                JNU_ThrowByNameWithLastError(env, JNU_JAVANETPKG "SocketException",
                                             "Unable to set SO_REUSEADDR");
                close(state->socket_fd);
                SSL_CTX_free(state->ctx);
                free(state);
                return -1;
            }
        }

#if defined(__linux__)
        if (type == SOCK_DGRAM) {
            int arg = 0;
            int level = (domain == AF_INET6) ? IPPROTO_IPV6 : IPPROTO_IP;
            if ((setsockopt(state->socket_fd, level, IP_MULTICAST_ALL, (char *)&arg,
                            sizeof(arg)) < 0) &&
                (errno != ENOPROTOOPT)) {
                JNU_ThrowByNameWithLastError(env, JNU_JAVANETPKG "SocketException",
                                             "Unable to set IP_MULTICAST_ALL");
                close(state->socket_fd);
                SSL_CTX_free(state->ctx);
                free(state);
                return -1;
            }
        }

        if (domain == AF_INET6 && type == SOCK_DGRAM) {
            /* By default, Linux uses the route default */
            int arg = 1;
            if (setsockopt(state->socket_fd, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, &arg,
                           sizeof(arg)) < 0) {
                JNU_ThrowByNameWithLastError(env, JNU_JAVANETPKG "SocketException",
                                             "Unable to set IPV6_MULTICAST_HOPS");
                close(state->socket_fd);
                SSL_CTX_free(state->ctx);
                free(state);
                return -1;
            }

            /* Disable IPV6_MULTICAST_ALL if option supported */
            arg = 0;
            if ((setsockopt(state->socket_fd, IPPROTO_IPV6, IPV6_MULTICAST_ALL, (char *)&arg,
                            sizeof(arg)) < 0) &&
                (errno != ENOPROTOOPT)) {
                JNU_ThrowByNameWithLastError(env, JNU_JAVANETPKG "SocketException",
                                             "Unable to set IPV6_MULTICAST_ALL");
                close(state->socket_fd);
                SSL_CTX_free(state->ctx);
                free(state);
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
            if (getsockopt(state->socket_fd, SOL_SOCKET, SO_SNDBUF, &size, &arglen) == 0) {
                int minSize = (domain == AF_INET6) ? 65527 : 65507;
                if (size < minSize) {
                    setsockopt(state->socket_fd, SOL_SOCKET, SO_SNDBUF, &minSize,
                               sizeof(minSize));
                }
            }
        }
#endif

        return (jlong) state;
    } else {
        free(state);
        return handleSocketErrorWithMessage(env, -1, "Failed to create SSL context");
    }
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    connect0
 * Signature: (ILjava/net/InetAddress;I)I
 */
JNIEXPORT jint JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_connect0(
    JNIEnv *env, jobject cl, jlong handle, jobject iao, jint port, jstring hostname
) {
    SSLSocketState *state = (SSLSocketState *)handle;
    SOCKETADDRESS sa;
    int sa_len = 0;
    int rv;

    if (NET_InetAddressToSockaddr(env, iao, port, &sa, &sa_len, JNI_TRUE) != 0) {
        return IOS_THROWN;
    }

    rv = connect(state->socket_fd, &sa.sa, sa_len);
    if (rv != 0) {
        if (errno == EINPROGRESS) {
            return IOS_UNAVAILABLE;
        } else if (errno == EINTR) {
            return IOS_INTERRUPTED;
        }
        return handleSocketError(env, errno);
    }

    state->ssl = SSL_new(state->ctx);
    if (!state->ssl) {
        return handleSocketErrorWithMessage(env, -1, "Failed to create SSL object");
    }

    SSL_set_fd(state->ssl, state->socket_fd);
    const char *nativeString = env->GetStringUTFChars(hostname, 0);
    SSL_set_tlsext_host_name(state->ssl, nativeString);

    if (SSL_connect(state->ssl) <= 0) {
        ERR_print_errors_fp(stderr);
        env->ReleaseStringUTFChars(hostname, nativeString);
        return handleSocketErrorWithMessage(env, -1, "SSL handshake failed");
    }
    env->ReleaseStringUTFChars(hostname, nativeString);

    return 1;
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    close0
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_close0(JNIEnv *env, jobject, jlong handle) {
    SSLSocketState *state = (SSLSocketState *)handle;
    
    int closeResult;
    if (state->ssl) {
        SSL_shutdown(state->ssl);
        SSL_free(state->ssl);
    }
    if (state->socket_fd >= 0) {
        closeResult = close(state->socket_fd);
    }
    if (state->ctx) {
        SSL_CTX_free(state->ctx);
    }
    free(state);
    if (closeResult < 0) JNU_ThrowIOExceptionWithLastError(env, "Close failed");
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    read0
 * Signature: (IJI)I
 */
JNIEXPORT jint JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_read0(JNIEnv *env, jobject, jlong handle, jlong address, jint len) {
    SSLSocketState *state = (SSLSocketState *)handle;
    void *buf = (void *)jlong_to_ptr(address);
    jint n = SSL_read(state->ssl, buf, len);
    
    if (n <= 0) {
        int ssl_error = SSL_get_error(state->ssl, n);
        if (ssl_error == SSL_ERROR_WANT_READ || ssl_error == SSL_ERROR_WANT_WRITE || errno == EINTR) {
            return 0;  // Return 0 for temporary unavailability
        }
        return -1;  // Return -1 for actual errors
    }
    return n;
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    write0
 * Signature: (IJI)I
 */
JNIEXPORT jint JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_write0(JNIEnv *env, jobject, jlong handle, jlong address, jint len) {
    SSLSocketState *state = (SSLSocketState *)handle;
    void *buf = (void *)jlong_to_ptr(address);
    jint n = SSL_write(state->ssl, buf, len);
    
    if (n <= 0) {
        int ssl_error = SSL_get_error(state->ssl, n);
        if (ssl_error == SSL_ERROR_WANT_READ || ssl_error == SSL_ERROR_WANT_WRITE || errno == EINTR) {
            return 0;  // Return 0 for temporary unavailability
        }
        return -1;  // Return -1 for actual errors
    }
    return n;
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    configureBlocking0
 * Signature: (IZ)V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_configureBlocking0(JNIEnv *env, jobject, jlong handle, jboolean blocking) {
    SSLSocketState *state = (SSLSocketState *)handle;
    if (configureBlocking(state->socket_fd, blocking) < 0)
        JNU_ThrowIOExceptionWithLastError(env, "Configure blocking failed");
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    setKeepAlive0
 * Signature: (JZ)V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_setKeepAlive0
  (JNIEnv* env, jobject obj, jlong handle, jboolean on) {
    SSLSocketState *state = (SSLSocketState *)handle;
    int optval = on ? 1 : 0;
    if (setsockopt(state->socket_fd, SOL_SOCKET, SO_KEEPALIVE, &optval, sizeof(optval)) < 0) {
        JNU_ThrowIOExceptionWithLastError(env, "Set keep alive failed");
    }
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    setReceiveBufferSize0
 * Signature: (JI)V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_setReceiveBufferSize0
  (JNIEnv* env, jobject obj, jlong handle, jint size) {
    SSLSocketState *state = (SSLSocketState *)handle;
    if (setsockopt(state->socket_fd, SOL_SOCKET, SO_RCVBUF, &size, sizeof(size)) < 0) {
        JNU_ThrowIOExceptionWithLastError(env, "Set receive buffer size failed");
    }
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    setSendBufferSize0
 * Signature: (JI)V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_setSendBufferSize0
  (JNIEnv* env, jobject obj, jlong handle, jint size) {
    SSLSocketState *state = (SSLSocketState *)handle;
    if (setsockopt(state->socket_fd, SOL_SOCKET, SO_SNDBUF, &size, sizeof(size)) < 0) {
        JNU_ThrowIOExceptionWithLastError(env, "Set send buffer size failed");
    }
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    setTcpNoDelay0
 * Signature: (JZ)V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_setTcpNoDelay0
  (JNIEnv* env, jobject obj, jlong handle, jboolean on) {
    SSLSocketState *state = (SSLSocketState *)handle;
    int optval = on ? 1 : 0;
    if (setsockopt(state->socket_fd, IPPROTO_TCP, TCP_NODELAY, &optval, sizeof(optval)) < 0) {
        JNU_ThrowIOExceptionWithLastError(env, "Set TCP no delay failed");
    }
}

#ifdef __cplusplus
}
#endif
#endif
