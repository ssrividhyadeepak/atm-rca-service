package com.srividhya.bankrca.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Local RSA key pair for the 'dev' profile, standing in for an identity provider: DevTokens
 * signs tokens with the private key and the server checks them with the public key.
 * The files are created on first use and must never be committed or used outside development.
 */
public final class DevKeys {

    // Has to be a URL to be advertised as an authorization server; nothing is served there
    public static final String ISSUER = "http://localhost/bank-rca-dev";

    private DevKeys() {
    }

    public static synchronized KeyPair loadOrCreate(Path dir) {
        Path privateFile = dir.resolve("dev-private.pem");
        Path publicFile = dir.resolve("dev-public.pem");
        try {
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            if (Files.exists(privateFile) && Files.exists(publicFile)) {
                return new KeyPair(rsa.generatePublic(new X509EncodedKeySpec(read(publicFile))),
                        rsa.generatePrivate(new PKCS8EncodedKeySpec(read(privateFile))));
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            Files.createDirectories(dir);
            write(publicFile, "PUBLIC KEY", keys.getPublic().getEncoded());
            write(privateFile, "PRIVATE KEY", keys.getPrivate().getEncoded());
            try {
                Files.setPosixFilePermissions(privateFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                // not a POSIX file system (Windows): the file keeps its default permissions
            }
            return keys;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load or create dev keys in " + dir, e);
        }
    }

    private static byte[] read(Path pem) throws IOException {
        String base64 = Files.readAllLines(pem).stream().filter(l -> !l.startsWith("-----")).reduce("", String::concat);
        return Base64.getDecoder().decode(base64);
    }

    private static void write(Path file, String label, byte[] der) throws IOException {
        Files.writeString(file, "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + label + "-----\n");
    }
}
