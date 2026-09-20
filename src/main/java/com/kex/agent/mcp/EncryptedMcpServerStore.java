// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Fichier JSON chiffré AES-GCM ; aucune configuration sensible n'est écrite sans clé.
 *
 * <p>La clé AES n'est jamais {@code kex.mcp.runtime.storage-key} telle quelle : elle en est
 * dérivée par PBKDF2-HMAC-SHA256 avec un sel aléatoire propre à chaque écriture, plutôt qu'un
 * simple hachage — sans quoi une passphrase faible resterait cassable hors ligne sans qu'aucun
 * ralentissement ne s'y oppose. Le sel voyage avec le fichier, jamais séparément : lui seul permet
 * de retrouver la clé à la lecture suivante, pas de registre externe à tenir cohérent.
 *
 * <p>{@code KEXMCP2} succède à {@code KEXMCP1} (hachage nu, sans sel) : un fichier de l'ancien
 * format échoue à se déchiffrer avec un message explicite plutôt qu'en silence — la fonctionnalité
 * est récente, aucune migration automatique n'a semblé justifiée pour des enregistrements que la
 * console recrée en quelques clics.
 */
public final class EncryptedMcpServerStore {

    private static final Logger log = LoggerFactory.getLogger(EncryptedMcpServerStore.class);
    private static final String MAGIC = "KEXMCP2";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int KEY_LENGTH_BITS = 256;
    // OWASP Password Storage Cheat Sheet (2023) pour PBKDF2-HMAC-SHA256 : la clé dérive d'une
    // passphrase d'opérateur, pas d'un mot de passe utilisateur tapé à chaque connexion, et cette
    // dérivation n'a lieu qu'à l'enregistrement ou au démarrage — jamais sur un chemin de requête.
    private static final int PBKDF2_ITERATIONS = 210_000;

    private final ObjectMapper objectMapper;
    private final Path path;
    private final String storageKey;

    public EncryptedMcpServerStore(ObjectMapper objectMapper, McpRuntimeProperties properties) {
        this.objectMapper = objectMapper;
        Path configured = Path.of(properties.storagePath());
        this.path = configured.isAbsolute()
                ? configured
                : Path.of(System.getProperty("user.home"), configured.toString());
        this.storageKey = properties.storageKey();
    }

    public boolean enabled() {
        return StringUtils.hasText(storageKey);
    }

    public List<PersistedServer> load() {
        if (!enabled() || !Files.exists(path)) return List.of();
        try {
            String[] parts = Files.readString(path, StandardCharsets.UTF_8).split("\\.", 4);
            if (parts.length != 4 || !MAGIC.equals(parts[0])) {
                throw new IllegalStateException("Format du stockage MCP inconnu");
            }
            byte[] salt = Base64.getUrlDecoder().decode(parts[1]);
            byte[] nonce = Base64.getUrlDecoder().decode(parts[2]);
            byte[] clear = crypt(Cipher.DECRYPT_MODE, deriveKey(salt), nonce,
                    Base64.getUrlDecoder().decode(parts[3]));
            StoredServers stored = objectMapper.readValue(clear, StoredServers.class);
            return stored.servers() == null ? List.of() : List.copyOf(stored.servers());
        }
        catch (IOException | GeneralSecurityException | IllegalArgumentException ex) {
            throw new McpStorageException("Impossible de déchiffrer le stockage MCP", ex);
        }
    }

    public void save(List<PersistedServer> servers) {
        if (!enabled()) return;
        try {
            byte[] salt = new byte[SALT_LENGTH_BYTES];
            RANDOM.nextBytes(salt);
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            byte[] clear = objectMapper.writeValueAsBytes(new StoredServers(1, servers));
            byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, deriveKey(salt), nonce, clear);
            String value = MAGIC + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
            Path parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, ".mcp-servers-", ".tmp");
            try {
                Files.writeString(temporary, value, StandardCharsets.UTF_8);
                restrictPermissions(temporary);
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                }
                catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
                restrictPermissions(path);
            }
            finally {
                Files.deleteIfExists(temporary);
            }
        }
        catch (IOException | GeneralSecurityException ex) {
            throw new McpStorageException("Impossible d'enregistrer les serveurs MCP", ex);
        }
    }

    private static byte[] crypt(int mode, SecretKeySpec key, byte[] nonce, byte[] input)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(MAGIC.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(input);
    }

    private SecretKeySpec deriveKey(byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(storageKey.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        try {
            byte[] keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        }
        catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 indisponible", ex);
        }
        finally {
            spec.clearPassword();
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        }
        catch (UnsupportedOperationException | IOException ex) {
            log.debug("Permissions POSIX non disponibles pour {}", file);
        }
    }

    public record PersistedServer(McpServerRegistration registration, Instant secretRotatedAt) {
    }

    private record StoredServers(int version, List<PersistedServer> servers) {
    }
}
