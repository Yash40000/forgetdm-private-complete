package io.forgetdm.virtualization;

/**
 * Retired. Zero-trust staging encryption (RFP §3.1.2) now lives inside {@link ChunkStore} itself,
 * which wraps each compressed chunk in an authenticated AES-256-GCM envelope (key derived from the
 * Vault-held masking secret, chunk hash bound as AAD, key fingerprint to detect a wrong key). This
 * class is intentionally not a Spring bean and is kept only to avoid breaking references.
 *
 * @deprecated use {@link ChunkStore#encryptedAtRest()} and the pool's built-in encryption.
 */
@Deprecated
final class StagingCrypto {
    private StagingCrypto() {}
}
