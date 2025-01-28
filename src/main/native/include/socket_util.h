#include <fcntl.h>
#include <jni.h>

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