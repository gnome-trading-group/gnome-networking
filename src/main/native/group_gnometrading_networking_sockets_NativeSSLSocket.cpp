#include <arpa/inet.h>
#include <fcntl.h>
#include <jni.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include <openssl/ssl.h>
#include <openssl/err.h>
#include <openssl/x509.h>

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

/*
 * One TLS connection.
 *
 * An SSL object may only be used by one thread at a time, but a gateway reads on one thread and writes on
 * another, so every TLS call holds |lock|. The descriptor is always non-blocking: a "blocking" read or a write
 * that cannot complete releases the lock and waits in poll(), so a reader waiting for data never holds up a
 * writer. Closing is not guarded by the lock; callers must stop using the socket on every thread first.
 */
typedef struct {
    int socket_fd;
    SSL *ssl;
    // Newest resumable session the server issued on this connection; handed to Java on close.
    SSL_SESSION *session;
    pthread_mutex_t lock;
    int blocking;
    int read_timeout_ms;
} SSLSocketState;

static const char *const CA_BUNDLE_PATHS[] = {
    "/etc/ssl/certs/ca-certificates.crt",  // Debian / Ubuntu
    "/etc/pki/tls/certs/ca-bundle.crt",    // Amazon Linux / RHEL
    "/etc/ssl/cert.pem",                   // macOS / Alpine
};

static SSLSocketState *requireState(JNIEnv *env, jlong handle) {
    SSLSocketState *state = (SSLSocketState *)handle;
    if (state == NULL) {
        JNU_ThrowByName(env, JNU_JAVANETPKG "SocketException", "Socket is closed");
    }
    return state;
}

static int newSessionCallback(SSL *ssl, SSL_SESSION *session) {
    SSLSocketState *state = (SSLSocketState *)SSL_get_app_data(ssl);
    if (state == NULL) {
        return 0;
    }
    if (state->session != NULL) {
        SSL_SESSION_free(state->session);
    }
    state->session = session;
    return 1;
}

static void throwSslError(JNIEnv *env, const char *className, const char *prefix) {
    char reason[256];
    char message[512];
    uint32_t err = ERR_get_error();
    if (err != 0) {
        ERR_error_string_n(err, reason, sizeof(reason));
    } else {
        snprintf(reason, sizeof(reason), "%s", errno != 0 ? strerror(errno) : "connection closed");
    }
    ERR_clear_error();
    snprintf(message, sizeof(message), "%s: %s", prefix, reason);
    JNU_ThrowByName(env, className, message);
}

static int isIpLiteral(const char *host) {
    unsigned char buf[sizeof(struct in6_addr)];
    return inet_pton(AF_INET, host, buf) == 1 || inet_pton(AF_INET6, host, buf) == 1;
}

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
 * Method:    createContext0
 * Signature: (Ljava/lang/String;)J
 */
JNIEXPORT jlong JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_createContext0(
    JNIEnv *env, jclass, jstring caFile) {
    SSL_CTX *ctx = SSL_CTX_new(TLS_client_method());
    if (ctx == NULL) {
        throwSslError(env, "javax/net/ssl/SSLException", "Failed to create SSL context");
        return 0;
    }
    SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION);
    SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, NULL);

    if (caFile != NULL) {
        const char *path = env->GetStringUTFChars(caFile, NULL);
        if (path == NULL) {
            SSL_CTX_free(ctx);
            return 0;
        }
        const int loaded = SSL_CTX_load_verify_locations(ctx, path, NULL);
        env->ReleaseStringUTFChars(caFile, path);
        if (!loaded) {
            SSL_CTX_free(ctx);
            throwSslError(env, "javax/net/ssl/SSLException", "Failed to load CA file");
            return 0;
        }
    } else {
        const char *override = getenv("SSL_CERT_FILE");
        if (override != NULL) {
            // An explicit override that cannot be loaded is a misconfiguration, not a reason to quietly
            // trust some other bundle.
            if (!SSL_CTX_load_verify_locations(ctx, override, NULL)) {
                SSL_CTX_free(ctx);
                throwSslError(env, "javax/net/ssl/SSLException", "Failed to load SSL_CERT_FILE");
                return 0;
            }
        } else {
            int loaded = 0;
            for (size_t i = 0; !loaded && i < sizeof(CA_BUNDLE_PATHS) / sizeof(CA_BUNDLE_PATHS[0]); i++) {
                if (access(CA_BUNDLE_PATHS[i], R_OK) == 0) {
                    loaded = SSL_CTX_load_verify_locations(ctx, CA_BUNDLE_PATHS[i], NULL);
                }
            }
            if (!loaded && getenv("SSL_CERT_DIR") == NULL) {
                SSL_CTX_free(ctx);
                ERR_clear_error();
                JNU_ThrowByName(env, "javax/net/ssl/SSLException",
                                "No CA bundle found; install ca-certificates or set SSL_CERT_FILE");
                return 0;
            }
            SSL_CTX_set_default_verify_paths(ctx);
        }
        ERR_clear_error();
    }

    SSL_CTX_set_session_cache_mode(ctx, SSL_SESS_CACHE_CLIENT | SSL_SESS_CACHE_NO_INTERNAL);
    SSL_CTX_sess_set_new_cb(ctx, newSessionCallback);
    return (jlong)ctx;
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    upRefSession0
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_upRefSession0(
    JNIEnv *, jclass, jlong sessionHandle) {
    if (sessionHandle != 0) {
        SSL_SESSION_up_ref((SSL_SESSION *)sessionHandle);
    }
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    freeSession0
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_freeSession0(
    JNIEnv *, jclass, jlong sessionHandle) {
    if (sessionHandle != 0) {
        SSL_SESSION_free((SSL_SESSION *)sessionHandle);
    }
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    socket
 * Signature: (ZZ)J
 */
JNIEXPORT jlong JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_socket(JNIEnv *env, jobject cl, jboolean stream, jboolean reuse) {
    SSLSocketState *state = (SSLSocketState *)calloc(1, sizeof(SSLSocketState));
    if (!state) {
        JNU_ThrowByName(env, JNU_JAVAPKG "OutOfMemoryError", "Failed to allocate SSLSocketState");
        return 0;
    }
    state->blocking = 1;

    int type = (stream ? SOCK_STREAM : SOCK_DGRAM);
    int domain = ipv6_available() ? AF_INET6 : AF_INET;

    state->socket_fd = socket(domain, type, 0);
    if (state->socket_fd < 0) {
        free(state);
        handleSocketError(env, errno);
        return 0;
    }

    if (domain == AF_INET6 && ipv4_available()) {
        int arg = 0;
        if (setsockopt(state->socket_fd, IPPROTO_IPV6, IPV6_V6ONLY, (char *)&arg, sizeof(int)) < 0) {
            JNU_ThrowByNameWithLastError(env, JNU_JAVANETPKG "SocketException", "Unable to set IPV6_V6ONLY");
            close(state->socket_fd);
            free(state);
            return 0;
        }
    }

    if (reuse) {
        int arg = 1;
        if (setsockopt(state->socket_fd, SOL_SOCKET, SO_REUSEADDR, (char *)&arg, sizeof(arg)) < 0) {
            JNU_ThrowByNameWithLastError(env, JNU_JAVANETPKG "SocketException", "Unable to set SO_REUSEADDR");
            close(state->socket_fd);
            free(state);
            return 0;
        }
    }

#if defined(SO_NOSIGPIPE)
    int noSigPipe = 1;
    setsockopt(state->socket_fd, SOL_SOCKET, SO_NOSIGPIPE, &noSigPipe, sizeof(noSigPipe));
#endif

    if (pthread_mutex_init(&state->lock, NULL) != 0) {
        close(state->socket_fd);
        free(state);
        JNU_ThrowByName(env, JNU_JAVAIOPKG "IOException", "Failed to create socket lock");
        return 0;
    }
    return (jlong)state;
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    connect0
 * Signature: (JJLjava/net/InetAddress;ILjava/lang/String;IJ)I
 */
JNIEXPORT jint JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_connect0(
    JNIEnv *env, jobject cl, jlong handle, jlong contextHandle, jobject iao, jint port, jstring hostname,
    jint timeoutMillis, jlong sessionHandle) {
    SSLSocketState *state = requireState(env, handle);
    if (state == NULL) {
        return IOS_THROWN;
    }
    SSL_CTX *ctx = (SSL_CTX *)contextHandle;
    // One deadline covers the TCP connect and the whole TLS handshake, however the server paces its bytes.
    const long deadline = deadlineAfter(timeoutMillis);

    if (connectWithTimeout(env, state->socket_fd, iao, port, deadline) != 0) {
        return IOS_THROWN;
    }

    state->ssl = SSL_new(ctx);
    if (!state->ssl) {
        throwSslError(env, "javax/net/ssl/SSLException", "Failed to create SSL object");
        return IOS_THROWN;
    }
    SSL_set_app_data(state->ssl, state);
    SSL_set_fd(state->ssl, state->socket_fd);
    if (sessionHandle != 0) {
        SSL_set_session(state->ssl, (SSL_SESSION *)sessionHandle);
    }

    const char *host = env->GetStringUTFChars(hostname, NULL);
    if (host == NULL) {
        return IOS_THROWN;
    }
    int hostConfigured;
    if (isIpLiteral(host)) {
        hostConfigured = X509_VERIFY_PARAM_set1_ip_asc(SSL_get0_param(state->ssl), host);
    } else {
        SSL_set_tlsext_host_name(state->ssl, host);
        SSL_set_hostflags(state->ssl, X509_CHECK_FLAG_NEVER_CHECK_SUBJECT);
        hostConfigured = SSL_set1_host(state->ssl, host);
    }
    env->ReleaseStringUTFChars(hostname, host);
    if (!hostConfigured) {
        throwSslError(env, "javax/net/ssl/SSLException", "Failed to configure hostname verification");
        return IOS_THROWN;
    }

    while (true) {
        ERR_clear_error();
        const int rv = SSL_connect(state->ssl);
        if (rv > 0) {
            return 1;
        }
        const int sslError = SSL_get_error(state->ssl, rv);
        if (sslError == SSL_ERROR_WANT_READ || sslError == SSL_ERROR_WANT_WRITE) {
            const int ready = pollUntil(state->socket_fd, sslError == SSL_ERROR_WANT_READ ? POLLIN : POLLOUT, deadline);
            if (ready == POLL_READY) {
                continue;
            }
            ERR_clear_error();
            if (ready == POLL_TIMEOUT) {
                JNU_ThrowByName(env, JNU_JAVANETPKG "SocketTimeoutException", "TLS handshake timed out");
            } else {
                throwWithErrno(env, JNU_JAVAIOPKG "IOException", "TLS handshake failed", errno);
            }
            return IOS_THROWN;
        }

        const long verifyResult = SSL_get_verify_result(state->ssl);
        if (verifyResult != X509_V_OK && verifyResult != X509_V_ERR_INVALID_CALL) {
            // X509_V_ERR_INVALID_CALL means the handshake failed before the certificate was checked.
            char message[256];
            snprintf(message, sizeof(message), "Certificate verification failed: %s",
                     X509_verify_cert_error_string(verifyResult));
            ERR_clear_error();
            JNU_ThrowByName(env, "javax/net/ssl/SSLHandshakeException", message);
        } else {
            throwSslError(env, "javax/net/ssl/SSLHandshakeException", "TLS handshake failed");
        }
        return IOS_THROWN;
    }
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    close0
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_close0(JNIEnv *env, jobject, jlong handle) {
    SSLSocketState *state = (SSLSocketState *)handle;
    if (state == NULL) {
        return 0;
    }

    if (state->ssl) {
        // The descriptor is non-blocking, so sending close_notify cannot hang on a dead peer.
        SSL_shutdown(state->ssl);
        SSL_free(state->ssl);
        ERR_clear_error();
    }
    int closeResult = 0;
    int closeErrno = 0;
    if (state->socket_fd >= 0) {
        closeResult = close(state->socket_fd);
        closeErrno = errno;
    }
    SSL_SESSION *session = state->session;
    pthread_mutex_destroy(&state->lock);
    free(state);

    if (closeResult < 0) {
        if (session != NULL) {
            SSL_SESSION_free(session);
        }
        throwWithErrno(env, JNU_JAVAIOPKG "IOException", "Close failed", closeErrno);
        return 0;
    }
    return (jlong)session;
}

/*
 * Returns bytes read, 0 when no data is available (immediately on a non-blocking socket, or once the read
 * timeout elapses), -1 on a clean close. Throws with the reason on any other failure.
 */
JNIEXPORT jint JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_read0(JNIEnv *env, jobject, jlong handle, jlong address, jint len) {
    SSLSocketState *state = requireState(env, handle);
    if (state == NULL) {
        return IOS_THROWN;
    }
    if (len <= 0) {
        return 0;
    }
    void *buf = (void *)jlong_to_ptr(address);
    const long deadline = state->blocking ? deadlineAfter(state->read_timeout_ms) : 0;

    while (true) {
        pthread_mutex_lock(&state->lock);
        ERR_clear_error();
        // Cleared so a syscall error is told apart from a close by this call's errno, not a stale one.
        errno = 0;
        const int n = SSL_read(state->ssl, buf, len);
        const int sslError = n > 0 ? SSL_ERROR_NONE : SSL_get_error(state->ssl, n);
        const int savedErrno = errno;
        pthread_mutex_unlock(&state->lock);

        if (n > 0) {
            return n;
        }
        switch (sslError) {
            case SSL_ERROR_WANT_READ:
            case SSL_ERROR_WANT_WRITE: {
                if (!state->blocking) {
                    return 0;
                }
                const int ready = pollUntil(state->socket_fd, sslError == SSL_ERROR_WANT_READ ? POLLIN : POLLOUT, deadline);
                if (ready == POLL_READY) {
                    continue;
                }
                if (ready == POLL_TIMEOUT) {
                    return 0;
                }
                throwWithErrno(env, JNU_JAVAIOPKG "IOException", "TLS read failed", errno);
                return IOS_THROWN;
            }
            case SSL_ERROR_ZERO_RETURN:
                return -1;
            case SSL_ERROR_SYSCALL:
                if (n == 0 || savedErrno == 0) {
                    // The peer closed the TCP connection without a close_notify.
                    ERR_clear_error();
                    return -1;
                }
                errno = savedErrno;
                throwSslError(env, JNU_JAVAIOPKG "IOException", "TLS read failed");
                return IOS_THROWN;
            default:
                throwSslError(env, JNU_JAVAIOPKG "IOException", "TLS read failed");
                return IOS_THROWN;
        }
    }
}

/*
 * Writes all {@code len} bytes or throws: a frame or request cut off part way would corrupt the stream, so
 * there is no partial result. Waits for the socket when it cannot take the bytes yet, up to the timeout.
 */
JNIEXPORT jint JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_write0(JNIEnv *env, jobject, jlong handle, jlong address, jint len, jint timeoutMillis) {
    SSLSocketState *state = requireState(env, handle);
    if (state == NULL) {
        return IOS_THROWN;
    }
    if (len <= 0) {
        return 0;
    }
    void *buf = (void *)jlong_to_ptr(address);
    const long deadline = deadlineAfter(timeoutMillis);

    while (true) {
        pthread_mutex_lock(&state->lock);
        ERR_clear_error();
        // Without SSL_MODE_ENABLE_PARTIAL_WRITE this writes everything or nothing; a retry after
        // SSL_ERROR_WANT_WRITE must pass the same buffer and length, which this loop does.
        errno = 0;
        const int n = SSL_write(state->ssl, buf, len);
        const int sslError = n > 0 ? SSL_ERROR_NONE : SSL_get_error(state->ssl, n);
        const int savedErrno = errno;
        pthread_mutex_unlock(&state->lock);

        if (n > 0) {
            return n;
        }
        if (sslError == SSL_ERROR_WANT_READ || sslError == SSL_ERROR_WANT_WRITE) {
            const int ready = pollUntil(state->socket_fd, sslError == SSL_ERROR_WANT_READ ? POLLIN : POLLOUT, deadline);
            if (ready == POLL_READY) {
                continue;
            }
            if (ready == POLL_TIMEOUT) {
                JNU_ThrowByName(env, JNU_JAVANETPKG "SocketTimeoutException", "TLS write timed out");
            } else {
                throwWithErrno(env, JNU_JAVAIOPKG "IOException", "TLS write failed", errno);
            }
            return IOS_THROWN;
        }
        errno = savedErrno;
        throwSslError(env, JNU_JAVAIOPKG "IOException", "TLS write failed");
        return IOS_THROWN;
    }
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    probe0
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_probe0(JNIEnv *env, jobject, jlong handle) {
    SSLSocketState *state = (SSLSocketState *)handle;
    if (state == NULL) {
        return PROBE_DEAD;
    }
    pthread_mutex_lock(&state->lock);
    int result;
    if (SSL_pending(state->ssl) > 0) {
        result = PROBE_PENDING;
    } else {
        result = probeConnection(state->socket_fd);
        if (result == PROBE_PENDING) {
            // Raw bytes on an idle TLS connection are usually a session ticket or a close_notify, not data,
            // so let TLS look at them; the descriptor is non-blocking, so this never waits.
            char byte;
            ERR_clear_error();
            const int n = SSL_peek(state->ssl, &byte, 1);
            if (n > 0) {
                result = PROBE_PENDING;
            } else {
                const int sslError = SSL_get_error(state->ssl, n);
                result = (sslError == SSL_ERROR_WANT_READ || sslError == SSL_ERROR_WANT_WRITE) ? PROBE_IDLE : PROBE_DEAD;
                ERR_clear_error();
            }
        }
    }
    pthread_mutex_unlock(&state->lock);
    return result;
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    isSessionReused0
 * Signature: (J)Z
 */
JNIEXPORT jboolean JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_isSessionReused0(JNIEnv *env, jobject, jlong handle) {
    SSLSocketState *state = requireState(env, handle);
    if (state == NULL) {
        return JNI_FALSE;
    }
    pthread_mutex_lock(&state->lock);
    const jboolean reused = SSL_session_reused(state->ssl) ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&state->lock);
    return reused;
}

/*
 * Whether reads wait for data. The descriptor itself always stays non-blocking (see SSLSocketState).
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_configureBlocking0(JNIEnv *env, jobject, jlong handle, jboolean blocking) {
    SSLSocketState *state = requireState(env, handle);
    if (state != NULL) {
        state->blocking = blocking ? 1 : 0;
    }
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    setKeepAlive0
 * Signature: (JZ)V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_setKeepAlive0
  (JNIEnv* env, jobject obj, jlong handle, jboolean on) {
    SSLSocketState *state = requireState(env, handle);
    if (state == NULL) {
        return;
    }
    int optval = on ? 1 : 0;
    if (setsockopt(state->socket_fd, SOL_SOCKET, SO_KEEPALIVE, &optval, sizeof(optval)) < 0) {
        JNU_ThrowIOExceptionWithLastError(env, "Set keep alive failed");
    }
}

JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_setKeepAliveOptions0
  (JNIEnv* env, jobject obj, jlong handle, jint idleSeconds, jint intervalSeconds, jint count) {
    SSLSocketState *state = requireState(env, handle);
    if (state != NULL) {
        setKeepAliveOptions(env, state->socket_fd, idleSeconds, intervalSeconds, count);
    }
}

JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_setBusyPoll0
  (JNIEnv* env, jobject obj, jlong handle, jint micros) {
    SSLSocketState *state = requireState(env, handle);
    if (state != NULL) {
        setBusyPoll(env, state->socket_fd, micros);
    }
}

/*
 * Class:     group_gnometrading_networking_sockets_NativeSSLSocket
 * Method:    setReceiveBufferSize0
 * Signature: (JI)V
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_setReceiveBufferSize0
  (JNIEnv* env, jobject obj, jlong handle, jint size) {
    SSLSocketState *state = requireState(env, handle);
    if (state == NULL) {
        return;
    }
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
    SSLSocketState *state = requireState(env, handle);
    if (state == NULL) {
        return;
    }
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
    SSLSocketState *state = requireState(env, handle);
    if (state == NULL) {
        return;
    }
    int optval = on ? 1 : 0;
    if (setsockopt(state->socket_fd, IPPROTO_TCP, TCP_NODELAY, &optval, sizeof(optval)) < 0) {
        JNU_ThrowIOExceptionWithLastError(env, "Set TCP no delay failed");
    }
}

/*
 * How long a blocking read waits for data before returning 0; 0 waits indefinitely.
 */
JNIEXPORT void JNICALL Java_group_gnometrading_networking_sockets_NativeSSLSocket_setReadTimeout0
  (JNIEnv* env, jobject obj, jlong handle, jint millis) {
    SSLSocketState *state = requireState(env, handle);
    if (state != NULL) {
        state->read_timeout_ms = millis;
    }
}

#ifdef __cplusplus
}
#endif
#endif
