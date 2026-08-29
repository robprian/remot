# Remot — Security Model

Security is treated as a first-class feature. This document states what is
implemented (v1.0.0), what threat each control mitigates, and what remains.

---

## 1. Device identity

- Each installation generates a P-256 (secp256r1) keypair in the **Android
  Keystore** (`DeviceIdentity`). The private key is hardware-backed where the
  device supports it and can never be exported.
- The DER public key is the device's stable identity; `deviceId` =
  SHA-256(public key). Everything downstream — pairing, grants, session auth —
  references this identity.

## 2. Signaling registration (server-side authentication)

- `register` requires the client to send its DER public key; the server checks
  `deviceId == SHA-256(pubKey)` and then issues a fresh 32-byte nonce. The
  client must sign the nonce with the matching private key (ECDSA/SHA-256).
- **Mitigates:** identity hijacking, message-routing hijacking, spoofed
  registration, eviction of a legitimate device from the routing table. A
  compromised or malicious client can only act as itself.
- Rate limiting additionally guards `join` attempts per IP and per-connection
  message floods.

## 3. Pairing

- QR pairing runs an authenticated ECDH exchange:
  1. Host publishes an ephemeral public key + nonce in the QR payload.
  2. Controller derives the shared secret and signs the transcript
     `(nonce ‖ hostPub ‖ ctrlEphPub)` with its identity key.
  3. Host verifies the proof, derives the same secret, and signs its own proof
     `(ctrlEphPub ‖ ctrlPub)`.
  4. Both sides compute an identical **safety number**; users confirm it
     out-of-band before the pairing is trusted.
- Trust records are stored in `EncryptedSharedPreferences` (AES256-GCM,
  hardware-backed master key), keyed by the peer's public key.
- A pairing code / QR is **not** a password: it establishes a trusted
  relationship that the user explicitly confirms.

## 4. Session authentication & media protection

- A controller may only dial a paired host (server enforces the pairing graph)
  or join a short-lived one-time 6-digit code with explicit host consent.
- **MITM-proof media:** each peer signs the DTLS fingerprint from its SDP with
  its identity key; the other verifies against the paired public key. Even a
  fully compromised signaling server cannot insert itself into a paired
  session's media.
- Transport: WebRTC DTLS-SRTP end to end; signaling over WSS/TLS in
  production.

## 5. Unattended access

- Grants are created on the host by its owner, reference a **paired identity**,
  are scoped (`VIEW` / `CONTROL` / …), expire, and are revocable at any time
  (locally and via the server).
- On Android 14+ the app degrades gracefully: no fake "auto-approve" of
  attended sessions; the consent dialog and MediaProjection dialog always
  gate an attended session.

## 6. TURN credentials

- coturn runs with `use-auth-secret`; clients get short-lived HMAC credentials
  (`username=<expiry>:<label>`, default TTL 1 h) from the signaling server.
  No static per-user accounts. The coturn config denies relay to internal
  ranges and disables the admin CLI.

## 7. Secrets handling

- No credentials, tokens, private keys, or TURN secrets are hardcoded or
  committed. `.env` is git-ignored; `.env.example` ships with placeholders
  only. FCM service-account JSON and release keystores are never committed.

## 8. What is deliberately NOT done

- No root, no hidden/undocumented APIs, no accessibility abuse, no
  privilege escalation. Remote control uses only public Android APIs.

## 9. Known limitations / notes

- Ad-hoc (non-paired) code sessions trust the one-time code + host consent;
  the SDP fingerprint is accepted unverified because there is no pre-shared
  identity. This is inherent to code-based (non-paired) joining and is why
  pairing exists.
- Server state is in-memory; a restart clears routing state (clients
  re-register on reconnect). Pairings/grants live on devices and are
  re-registered by the clients.
- FCM tokens and device public keys are the only server-held identifiers; no
  screen content or input data ever transits the server.
