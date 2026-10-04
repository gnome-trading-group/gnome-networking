#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>

#include "jni_util.h"
#include "net_util.h"

#ifndef SOCKET_UTIL_H
#define SOCKET_UTIL_H
#ifdef __cplusplus
extern "C" {
#endif

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

static jint handleSocketError(JNIEnv *env, jint errorValue) {
    return handleSocketErrorWithMessage(env, errorValue, NULL);
}

static void throwWithErrno(JNIEnv *env, const char *className, const char *prefix, int err) {
    char message[256];
    snprintf(message, sizeof(message), "%s: %s", prefix, strerror(err));
    JNU_ThrowByName(env, className, message);
}

#define NO_DEADLINE (-1L)

static long monotonicMillis() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/** A deadline {@code timeoutMillis} from now, or NO_DEADLINE when the timeout is 0 or less (wait forever). */
static long deadlineAfter(jint timeoutMillis) {
    return timeoutMillis > 0 ? monotonicMillis() + timeoutMillis : NO_DEADLINE;
}

#define POLL_READY 1
#define POLL_TIMEOUT 0
#define POLL_ERROR (-1)

/** Waits until {@code fd} is ready for {@code events} or the deadline passes; an interrupt waits the remainder. */
static int pollUntil(int fd, short events, long deadline) {
    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = events;
    while (true) {
        int waitMillis = -1;
        if (deadline != NO_DEADLINE) {
            const long remaining = deadline - monotonicMillis();
            if (remaining <= 0) {
                return POLL_TIMEOUT;
            }
            waitMillis = (int)remaining;
        }
        const int rv = poll(&pfd, 1, waitMillis);
        if (rv > 0) {
            return POLL_READY;
        }
        if (rv == 0) {
            if (deadline == NO_DEADLINE) {
                continue;
            }
            return POLL_TIMEOUT;
        }
        if (errno != EINTR) {
            return POLL_ERROR;
        }
    }
}

/*
 * A blocking connect() cannot be interrupted from Java and, without a deadline, waits out the kernel's SYN
 * retries (minutes), so the connect runs non-blocking and is bounded with poll(). The socket is left
 * non-blocking. Returns 0 on success; otherwise a Java exception is pending.
 */
static int connectWithTimeout(JNIEnv *env, int fd, jobject iao, jint port, long deadline) {
    SOCKETADDRESS sa;
    int sa_len = 0;
    if (NET_InetAddressToSockaddr(env, iao, port, &sa, &sa_len, JNI_TRUE) != 0) {
        return -1;
    }
    if (configureBlocking(fd, JNI_FALSE) < 0) {
        throwWithErrno(env, JNU_JAVANETPKG "SocketException", "Unable to configure connect", errno);
        return -1;
    }

    int rv;
    do {
        rv = connect(fd, &sa.sa, sa_len);
    } while (rv != 0 && errno == EINTR);

    if (rv != 0) {
        if (errno != EINPROGRESS) {
            handleSocketError(env, errno);
            return -1;
        }
        const int ready = pollUntil(fd, POLLOUT, deadline);
        if (ready == POLL_TIMEOUT) {
            JNU_ThrowByName(env, JNU_JAVANETPKG "SocketTimeoutException", "Connect timed out");
            return -1;
        }
        if (ready == POLL_ERROR) {
            handleSocketError(env, errno);
            return -1;
        }
        int soError = 0;
        socklen_t len = sizeof(soError);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &soError, &len) < 0) {
            handleSocketError(env, errno);
            return -1;
        }
        if (soError != 0) {
            handleSocketError(env, soError);
            return -1;
        }
    }
    return 0;
}

static void setKeepAliveOptions(JNIEnv *env, int fd, jint idleSeconds, jint intervalSeconds, jint count) {
    int on = 1;
    if (setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &on, sizeof(on)) < 0) {
        JNU_ThrowIOExceptionWithLastError(env, "Set keep alive failed");
        return;
    }
#if defined(TCP_KEEPIDLE)
    const int idleOption = TCP_KEEPIDLE;
#else
    const int idleOption = TCP_KEEPALIVE;
#endif
    if (setsockopt(fd, IPPROTO_TCP, idleOption, &idleSeconds, sizeof(idleSeconds)) < 0 ||
        setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &intervalSeconds, sizeof(intervalSeconds)) < 0 ||
        setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &count, sizeof(count)) < 0) {
        JNU_ThrowIOExceptionWithLastError(env, "Set keep alive options failed");
    }
}

static void setBusyPoll(JNIEnv *env, int fd, jint micros) {
#if defined(SO_BUSY_POLL)
    if (setsockopt(fd, SOL_SOCKET, SO_BUSY_POLL, &micros, sizeof(micros)) < 0) {
        JNU_ThrowIOExceptionWithLastError(env, "Set busy poll failed");
    }
#else
    JNU_ThrowByName(env, JNU_JAVAIOPKG "IOException", "SO_BUSY_POLL is not supported on this platform");
#endif
}

#define PROBE_DEAD 0
#define PROBE_IDLE 1
#define PROBE_PENDING 2

/* Checks an idle connection without blocking: whether the peer has closed it, or bytes are waiting. */
static int probeConnection(int fd) {
    char byte;
    ssize_t n;
    do {
        n = recv(fd, &byte, 1, MSG_PEEK | MSG_DONTWAIT);
    } while (n < 0 && errno == EINTR);
    if (n > 0) return PROBE_PENDING;
    if (n == 0) return PROBE_DEAD;
    return (errno == EAGAIN || errno == EWOULDBLOCK) ? PROBE_IDLE : PROBE_DEAD;
}

static jint convertReturnVal(JNIEnv *env, jint n, jboolean reading) {
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