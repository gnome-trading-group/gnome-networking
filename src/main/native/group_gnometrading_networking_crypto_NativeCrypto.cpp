#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <openssl/bytestring.h>
#include <openssl/curve25519.h>
#include <openssl/digest.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/mem.h>
#include <openssl/rand.h>
#include <openssl/rsa.h>
#include <openssl/sha.h>
#include <secp256k1.h>
#include <secp256k1_recovery.h>

#include "group_gnometrading_networking_crypto_NativeCrypto.h"

/*
 * Bounds are checked in Java before every call. Messages are read in place through GetPrimitiveArrayCritical, and
 * fixed-size inputs and outputs are copied through the stack with Get/SetByteArrayRegion; neither allocates on the
 * Java heap. Nothing between a critical Get and its Release calls back into the JVM.
 */

static void throwIllegalArgument(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    if (cls != NULL) {
        env->ThrowNew(cls, message);
    }
}

static void throwIllegalState(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != NULL) {
        env->ThrowNew(cls, message);
    }
}

// ---- RSASSA-PSS (SHA-256, MGF1-SHA-256, 32-byte salt) ----

JNIEXPORT jlong JNICALL Java_group_gnometrading_networking_crypto_NativeCrypto_rsaCreate(
        JNIEnv *env, jclass, jbyteArray pkcs8) {
    jsize length = env->GetArrayLength(pkcs8);
    jbyte *der = env->GetByteArrayElements(pkcs8, NULL);
    if (der == NULL) {
        return 0;
    }
    CBS cbs;
    CBS_init(&cbs, (const uint8_t *)der, (size_t)length);
    EVP_PKEY *pkey = EVP_parse_private_key(&cbs);
    env->ReleaseByteArrayElements(pkcs8, der, JNI_ABORT);
    ERR_clear_error();
    if (pkey == NULL) {
        throwIllegalArgument(env, "Not a PKCS#8 private key");
        return 0;
    }
    RSA *rsa = EVP_PKEY_get1_RSA(pkey);
    EVP_PKEY_free(pkey);
    if (rsa == NULL) {
        throwIllegalArgument(env, "Not an RSA private key");
        return 0;
    }
    return (jlong)rsa;
}

JNIEXPORT jint JNICALL Java_group_gnometrading_networking_crypto_NativeCrypto_rsaSize(
        JNIEnv *, jclass, jlong handle) {
    return (jint)RSA_size((RSA *)handle);
}

JNIEXPORT jint JNICALL Java_group_gnometrading_networking_crypto_NativeCrypto_rsaPssSha256Sign(
        JNIEnv *env, jclass, jlong handle, jbyteArray message, jint offset, jint length, jbyteArray out,
        jint outOffset) {
    RSA *rsa = (RSA *)handle;
    uint8_t digest[SHA256_DIGEST_LENGTH];
    jbyte *in = (jbyte *)env->GetPrimitiveArrayCritical(message, NULL);
    if (in == NULL) {
        return 0;
    }
    SHA256((const uint8_t *)in + offset, (size_t)length, digest);
    env->ReleasePrimitiveArrayCritical(message, in, JNI_ABORT);

    size_t signatureLength = 0;
    size_t maxLength = RSA_size(rsa);
    jbyte *target = (jbyte *)env->GetPrimitiveArrayCritical(out, NULL);
    if (target == NULL) {
        return 0;
    }
    int ok = RSA_sign_pss_mgf1(rsa, &signatureLength, (uint8_t *)target + outOffset, maxLength, digest,
                               sizeof(digest), EVP_sha256(), EVP_sha256(), SHA256_DIGEST_LENGTH);
    env->ReleasePrimitiveArrayCritical(out, target, 0);
    if (!ok) {
        ERR_clear_error();
        throwIllegalState(env, "RSA-PSS signing failed");
        return 0;
    }
    return (jint)signatureLength;
}

JNIEXPORT void JNICALL Java_group_gnometrading_networking_crypto_NativeCrypto_rsaFree(
        JNIEnv *, jclass, jlong handle) {
    RSA_free((RSA *)handle);
}

// ---- Ed25519 ----

typedef struct {
    uint8_t privateKey[64];
} Ed25519Key;

JNIEXPORT jlong JNICALL Java_group_gnometrading_networking_crypto_NativeCrypto_ed25519Create(
        JNIEnv *env, jclass, jbyteArray seed) {
    Ed25519Key *key = (Ed25519Key *)malloc(sizeof(Ed25519Key));
    if (key == NULL) {
        throwIllegalState(env, "Out of native memory");
        return 0;
    }
    uint8_t seedBytes[32];
    uint8_t publicKey[32];
    env->GetByteArrayRegion(seed, 0, 32, (jbyte *)seedBytes);
    ED25519_keypair_from_seed(publicKey, key->privateKey, seedBytes);
    OPENSSL_cleanse(seedBytes, sizeof(seedBytes));
    return (jlong)key;
}

JNIEXPORT void JNICALL Java_group_gnometrading_networking_crypto_NativeCrypto_ed25519Sign(
        JNIEnv *env, jclass, jlong handle, jbyteArray message, jint offset, jint length, jbyteArray out,
        jint outOffset) {
    Ed25519Key *key = (Ed25519Key *)handle;
    uint8_t signature[64];
    jbyte *in = (jbyte *)env->GetPrimitiveArrayCritical(message, NULL);
    if (in == NULL) {
        return;
    }
    int ok = ED25519_sign(signature, (const uint8_t *)in + offset, (size_t)length, key->privateKey);
    env->ReleasePrimitiveArrayCritical(message, in, JNI_ABORT);
    if (!ok) {
        throwIllegalState(env, "Ed25519 signing failed");
        return;
    }
    env->SetByteArrayRegion(out, outOffset, 64, (const jbyte *)signature);
}

JNIEXPORT void JNICALL Java_group_gnometrading_networking_crypto_NativeCrypto_ed25519Free(
        JNIEnv *, jclass, jlong handle) {
    Ed25519Key *key = (Ed25519Key *)handle;
    OPENSSL_cleanse(key, sizeof(Ed25519Key));
    free(key);
}

// ---- HMAC-SHA256 ----

JNIEXPORT jlong JNICALL Java_group_gnometrading_networking_crypto_NativeCrypto_hmacSha256Create(
        JNIEnv *env, jclass, jbyteArray key) {
    HMAC_CTX *ctx = HMAC_CTX_new();
    if (ctx == NULL) {
        throwIllegalState(env, "Out of native memory");
        return 0;
    }
    jsize length = env->GetArrayLength(key);
    jbyte *bytes = env->GetByteArrayElements(key, NULL);
    if (bytes == NULL) {
        HMAC_CTX_free(ctx);
        return 0;
    }
    int ok = HMAC_Init_ex(ctx, bytes, (size_t)length, EVP_sha256(), NULL);
    env->ReleaseByteArrayElements(key, bytes, JNI_ABORT);
    if (!ok) {
        HMAC_CTX_free(ctx);
        ERR_clear_error();
        throwIllegalState(env, "HMAC setup failed");
        return 0;
    }
    return (jlong)ctx;
}

JNIEXPORT void JNICALL Java_group_gnometrading_networking_crypto_NativeCrypto_hmacSha256Update(
        JNIEnv *env, jclass, jlong handle, jbyteArray data, jint offset, jint length) {
    jbyte *in = (jbyte *)env->GetPrimitiveArrayCritical(data, NULL);
    if (in == NULL) {
        return;
    }
    HMAC_Update((HMAC_CTX *)handle, (const uint8_t *)in + offset, (size_t)length);
    env->ReleasePrimitiveArrayCritical(data, in, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_group_gnometrading_networking_crypto_NativeCrypto_hmacSha256Final(
        JNIEnv *env, jclass, jlong handle, jbyteArray out, jint outOffset) {
    HMAC_CTX *ctx = (HMAC_CTX *)handle;
    uint8_t mac[SHA256_DIGEST_LENGTH];
    unsigned int length = 0;
    // Re-initializing with no key keeps the key and restarts the hash for the next message.
    if (!HMAC_Final(ctx, mac, &length) || !HMAC_Init_ex(ctx, NULL, 0, NULL, NULL)) {
        ERR_clear_error();
        throwIllegalState(env, "HMAC failed");
        return;
    }
    env->SetByteArrayRegion(out, outOffset, SHA256_DIGEST_LENGTH, (const jbyte *)mac);
}

JNIEXPORT void JNICALL Java_group_gnometrading_networking_crypto_NativeCrypto_hmacSha256Free(
        JNIEnv *, jclass, jlong handle) {
    HMAC_CTX_free((HMAC_CTX *)handle);
}

// ---- secp256k1 ECDSA with recovery id ----

typedef struct {
    secp256k1_context *context;
    uint8_t privateKey[32];
} Secp256k1Key;

static void freeSecp256k1Key(Secp256k1Key *key) {
    if (key->context != NULL) {
        secp256k1_context_destroy(key->context);
    }
    OPENSSL_cleanse(key->privateKey, sizeof(key->privateKey));
    free(key);
}

JNIEXPORT jlong JNICALL Java_group_gnometrading_networking_crypto_NativeCrypto_secp256k1Create(
        JNIEnv *env, jclass, jbyteArray privateKey) {
    Secp256k1Key *key = (Secp256k1Key *)calloc(1, sizeof(Secp256k1Key));
    if (key == NULL) {
        throwIllegalState(env, "Out of native memory");
        return 0;
    }
    key->context = secp256k1_context_create(SECP256K1_CONTEXT_NONE);
    uint8_t seed[32];
    // Randomizing the context blinds its precomputed tables against side channels; signatures stay deterministic.
    if (key->context == NULL || !RAND_bytes(seed, sizeof(seed)) || !secp256k1_context_randomize(key->context, seed)) {
        freeSecp256k1Key(key);
        throwIllegalState(env, "secp256k1 setup failed");
        return 0;
    }
    env->GetByteArrayRegion(privateKey, 0, 32, (jbyte *)key->privateKey);
    if (!secp256k1_ec_seckey_verify(key->context, key->privateKey)) {
        freeSecp256k1Key(key);
        throwIllegalArgument(env, "Not a valid secp256k1 private key");
        return 0;
    }
    return (jlong)key;
}

JNIEXPORT jint JNICALL Java_group_gnometrading_networking_crypto_NativeCrypto_secp256k1Sign(
        JNIEnv *env, jclass, jlong handle, jbyteArray digest, jint offset, jbyteArray out, jint outOffset) {
    Secp256k1Key *key = (Secp256k1Key *)handle;
    uint8_t hash[32];
    env->GetByteArrayRegion(digest, offset, 32, (jbyte *)hash);

    secp256k1_ecdsa_recoverable_signature signature;
    // A null nonce function selects RFC 6979; the signature comes out with a low s.
    if (!secp256k1_ecdsa_sign_recoverable(key->context, &signature, hash, key->privateKey, NULL, NULL)) {
        throwIllegalState(env, "secp256k1 signing failed");
        return -1;
    }
    uint8_t compact[64];
    int recoveryId = 0;
    secp256k1_ecdsa_recoverable_signature_serialize_compact(key->context, compact, &recoveryId, &signature);
    env->SetByteArrayRegion(out, outOffset, 64, (const jbyte *)compact);
    return (jint)recoveryId;
}

JNIEXPORT void JNICALL Java_group_gnometrading_networking_crypto_NativeCrypto_secp256k1Free(
        JNIEnv *, jclass, jlong handle) {
    freeSecp256k1Key((Secp256k1Key *)handle);
}
