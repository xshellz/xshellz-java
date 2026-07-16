package com.xshellz.sandbox;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * In-memory ed25519 SSH keypair generation, OpenSSH encoding, and private-key
 * loading. The keypair is generated per {@code Sandbox.create()} and never
 * touches disk; the control plane only ever sees the public half. Internal.
 *
 * <p>Keys are generated with the JDK's native Ed25519 support
 * ({@code KeyPairGenerator.getInstance("Ed25519")}, JDK 15+). The OpenSSH
 * public line and the {@code openssh-key-v1} private container are encoded by
 * hand - both are simple SSH wire-format structures.
 */
final class SshKeys {

    static final String KEY_COMMENT = "xshellz-sdk";

    private static final String KEY_TYPE = "ssh-ed25519";
    private static final String PEM_HEADER = "-----BEGIN OPENSSH PRIVATE KEY-----";
    private static final String PEM_FOOTER = "-----END OPENSSH PRIVATE KEY-----";
    private static final byte[] AUTH_MAGIC;
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        byte[] name = "openssh-key-v1".getBytes(StandardCharsets.US_ASCII);
        AUTH_MAGIC = Arrays.copyOf(name, name.length + 1); // trailing NUL
    }

    private SshKeys() {
    }

    /**
     * An in-memory ed25519 keypair.
     *
     * @param keyPair           JDK key objects used to authenticate SSH sessions
     * @param publicKeyRaw      the raw 32-byte ed25519 public key
     * @param publicKeyLine     single-line OpenSSH public key sent to the control plane
     * @param privateKeyOpenSsh the private key in OpenSSH PEM format (persist it to
     *                          {@code detach()} + {@code connect()} later)
     */
    record Ed25519KeyPair(KeyPair keyPair, byte[] publicKeyRaw, String publicKeyLine, String privateKeyOpenSsh) {
    }

    /** Generates a fresh in-memory ed25519 keypair. */
    static Ed25519KeyPair generate() {
        try {
            KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] publicRaw = rawPublicKey(keyPair.getPublic());
            byte[] seed = ((EdECPrivateKey) keyPair.getPrivate()).getBytes()
                    .orElseThrow(() -> new XshellzException(
                            "The JDK returned an Ed25519 private key without raw bytes."));
            return new Ed25519KeyPair(
                    keyPair,
                    publicRaw,
                    publicKeyLine(publicRaw, KEY_COMMENT),
                    encodePrivateKeyOpenSsh(seed, publicRaw, KEY_COMMENT));
        } catch (GeneralSecurityException e) {
            throw new XshellzException("Ed25519 key generation is unavailable on this JVM.", e);
        }
    }

    /** Loads an unencrypted OpenSSH-format ed25519 private key produced by this SDK. */
    static Ed25519KeyPair fromOpenSsh(String privateKeyOpenSsh) {
        ParsedPrivateKey parsed = parsePrivateKeyOpenSsh(privateKeyOpenSsh);
        try {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            PrivateKey privateKey = factory.generatePrivate(
                    new EdECPrivateKeySpec(NamedParameterSpec.ED25519, parsed.seed()));
            PublicKey publicKey = factory.generatePublic(
                    new EdECPublicKeySpec(NamedParameterSpec.ED25519, decodePoint(parsed.publicKeyRaw())));
            return new Ed25519KeyPair(
                    new KeyPair(publicKey, privateKey),
                    parsed.publicKeyRaw(),
                    publicKeyLine(parsed.publicKeyRaw(), KEY_COMMENT),
                    privateKeyOpenSsh);
        } catch (GeneralSecurityException e) {
            throw new XshellzException("Could not rebuild the ed25519 keypair: " + e.getMessage(), e);
        }
    }

    /** Extracts the raw 32-byte ed25519 public key from a JDK public key (SPKI suffix). */
    static byte[] rawPublicKey(PublicKey publicKey) {
        byte[] spki = publicKey.getEncoded();
        if (spki == null || spki.length != 44) {
            throw new XshellzException("Unexpected Ed25519 SubjectPublicKeyInfo encoding.");
        }
        return Arrays.copyOfRange(spki, 12, 44);
    }

    /** Builds the single-line OpenSSH public key: {@code ssh-ed25519 <base64> <comment>}. */
    static String publicKeyLine(byte[] publicKeyRaw, String comment) {
        byte[] blob = publicKeyBlob(publicKeyRaw);
        String line = KEY_TYPE + " " + Base64.getEncoder().encodeToString(blob);
        return comment == null || comment.isEmpty() ? line : line + " " + comment;
    }

    /** The SSH wire-format public key blob: string "ssh-ed25519" + string key. */
    static byte[] publicKeyBlob(byte[] publicKeyRaw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        putString(out, KEY_TYPE.getBytes(StandardCharsets.US_ASCII));
        putString(out, publicKeyRaw);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ //
    // openssh-key-v1 private container (RFC draft-miller-ssh-agent style)
    // ------------------------------------------------------------------ //

    static String encodePrivateKeyOpenSsh(byte[] seed, byte[] publicKeyRaw, String comment) {
        ByteArrayOutputStream priv = new ByteArrayOutputStream();
        int check = RANDOM.nextInt();
        putUint32(priv, check);
        putUint32(priv, check);
        putString(priv, KEY_TYPE.getBytes(StandardCharsets.US_ASCII));
        putString(priv, publicKeyRaw);
        byte[] secret = new byte[64];
        System.arraycopy(seed, 0, secret, 0, 32);
        System.arraycopy(publicKeyRaw, 0, secret, 32, 32);
        putString(priv, secret);
        putString(priv, comment.getBytes(StandardCharsets.UTF_8));
        for (int i = 1; priv.size() % 8 != 0; i++) {
            priv.write(i);
        }

        ByteArrayOutputStream outer = new ByteArrayOutputStream();
        outer.writeBytes(AUTH_MAGIC);
        putString(outer, "none".getBytes(StandardCharsets.US_ASCII));
        putString(outer, "none".getBytes(StandardCharsets.US_ASCII));
        putString(outer, new byte[0]);
        putUint32(outer, 1);
        putString(outer, publicKeyBlob(publicKeyRaw));
        putString(outer, priv.toByteArray());

        StringBuilder pem = new StringBuilder(PEM_HEADER).append('\n');
        String base64 = Base64.getEncoder().encodeToString(outer.toByteArray());
        for (int i = 0; i < base64.length(); i += 70) {
            pem.append(base64, i, Math.min(base64.length(), i + 70)).append('\n');
        }
        return pem.append(PEM_FOOTER).append('\n').toString();
    }

    private record ParsedPrivateKey(byte[] seed, byte[] publicKeyRaw) {
    }

    private static ParsedPrivateKey parsePrivateKeyOpenSsh(String pem) {
        if (pem == null || !pem.contains(PEM_HEADER) || !pem.contains(PEM_FOOTER)) {
            throw new XshellzException(
                    "Could not load the private key: expected an unencrypted OpenSSH-format "
                            + "ed25519 private key (-----BEGIN OPENSSH PRIVATE KEY-----).");
        }
        String base64 = pem.substring(pem.indexOf(PEM_HEADER) + PEM_HEADER.length(), pem.indexOf(PEM_FOOTER))
                .replaceAll("\\s", "");
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new XshellzException("Could not load the private key: invalid base64 payload.", e);
        }

        try {
            ByteBuffer buf = ByteBuffer.wrap(blob);
            byte[] magic = new byte[AUTH_MAGIC.length];
            buf.get(magic);
            if (!Arrays.equals(magic, AUTH_MAGIC)) {
                throw new XshellzException("Could not load the private key: bad openssh-key-v1 magic.");
            }
            String cipher = new String(getString(buf), StandardCharsets.US_ASCII);
            String kdf = new String(getString(buf), StandardCharsets.US_ASCII);
            getString(buf); // kdf options
            int keyCount = buf.getInt();
            if (!"none".equals(cipher) || !"none".equals(kdf)) {
                throw new XshellzException(
                        "Could not load the private key: passphrase-protected OpenSSH keys are "
                                + "not supported - export an unencrypted key.");
            }
            if (keyCount != 1) {
                throw new XshellzException("Could not load the private key: expected exactly one key.");
            }
            getString(buf); // public key blob (re-derived from the private section)

            ByteBuffer priv = ByteBuffer.wrap(getString(buf));
            int check1 = priv.getInt();
            int check2 = priv.getInt();
            if (check1 != check2) {
                throw new XshellzException("Could not load the private key: check integers mismatch.");
            }
            String type = new String(getString(priv), StandardCharsets.US_ASCII);
            if (!KEY_TYPE.equals(type)) {
                throw new XshellzException(
                        "Could not load the private key: only ssh-ed25519 keys are supported, got '"
                                + type + "'.");
            }
            byte[] publicRaw = getString(priv);
            byte[] secret = getString(priv);
            if (publicRaw.length != 32 || secret.length != 64) {
                throw new XshellzException("Could not load the private key: malformed ed25519 payload.");
            }
            return new ParsedPrivateKey(Arrays.copyOfRange(secret, 0, 32), publicRaw);
        } catch (BufferUnderflowException e) {
            throw new XshellzException("Could not load the private key: truncated openssh-key-v1 blob.", e);
        }
    }

    /** Decodes a raw 32-byte ed25519 public key into a JDK {@link EdECPoint}. */
    private static EdECPoint decodePoint(byte[] raw) {
        byte[] littleEndian = raw.clone();
        boolean xOdd = (littleEndian[31] & 0x80) != 0;
        littleEndian[31] &= 0x7f;
        byte[] bigEndian = new byte[32];
        for (int i = 0; i < 32; i++) {
            bigEndian[i] = littleEndian[31 - i];
        }
        return new EdECPoint(xOdd, new BigInteger(1, bigEndian));
    }

    // ------------------------------------------------------------------ //
    // SSH wire-format helpers
    // ------------------------------------------------------------------ //

    private static void putUint32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void putString(ByteArrayOutputStream out, byte[] value) {
        putUint32(out, value.length);
        out.writeBytes(value);
    }

    private static byte[] getString(ByteBuffer buf) {
        int length = buf.getInt();
        if (length < 0 || length > buf.remaining()) {
            throw new BufferUnderflowException();
        }
        byte[] value = new byte[length];
        buf.get(value);
        return value;
    }
}
