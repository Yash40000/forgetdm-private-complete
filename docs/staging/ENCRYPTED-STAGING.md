# Zero-trust encrypted staging zone (RFP §3.1.2)

Unmasked production data pulled from sources lands in the TimeFlow **storage pool** (the staging
zone) before masking/materialization. With encrypted staging on, those payloads are **ciphertext at
rest**, decryptable only by the engine that holds the key — so a copy of the pool on disk discloses
nothing.

## How it works

The pool (`ChunkStore`) wraps each compressed chunk in an authenticated **AES-256-GCM** envelope:

```
FTDMCS01 | key-fingerprint(8) | nonce(12) | AES-256-GCM( gzip(rows), AAD = chunk-hash )
```

- **Key custody:** the key is derived from the masking secret — which is sourced from **HashiCorp
  Vault** when configured (§3.2.3). So the staging key follows the same custody as the masking key;
  only the engine can decrypt.
- **Authenticated:** the GCM tag detects any tampering; the chunk hash is bound as additional
  authenticated data (AAD), so a valid ciphertext can't be moved to another chunk key.
- **Wrong-key safe:** an 8-byte key fingerprint in the header rejects a chunk encrypted under a
  different key with a clear error instead of a garbage decrypt.
- **Dedup preserved:** the content hash is over the *plaintext*, so deduplication (changed-block-only
  storage) still works.
- **Backward-compatible:** legacy plaintext `.gz` chunks remain readable, and are transparently
  re-written as encrypted on the next write.

## Configuration

```
FORGETDM_STAGING_ENCRYPT=true        # off by default
# key = SHA-256(masking secret); with Vault enabled the secret (hence key) comes from Vault
```

`ChunkStore.encryptedAtRest()` reports whether the pool is encrypting.

## Verification

`ChunkStoreEncryptionTest` proves, without a live snapshot:

- encrypted round-trip with **no plaintext at rest** (on-disk bytes start with `FTDMCS01`, and the
  cleartext value is absent from the file);
- a **wrong key** and any **tampering** are rejected (GCM authentication + key fingerprint);
- legacy plaintext chunks are read and upgraded;
- a plaintext-mode reader fails clearly on an encrypted chunk (points at `forgetdm.staging.encrypt`).

Run: `mvn test -Dtest=ChunkStoreEncryptionTest`.

## Notes

- Rotating the masking secret changes the staging key: old chunks stay readable only while the old
  key is available. Encrypted staging is about at-rest confidentiality of the pool, not long-term
  key rotation of already-staged data — re-stage after a rotation.
- This encrypts the pool payloads; transport to the pool uses the source driver's own TLS.
