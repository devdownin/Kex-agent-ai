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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/** Fichier JSON chiffré AES-GCM ; aucune configuration sensible n'est écrite sans clé. */
public final class EncryptedMcpServerStore {

    private static final Logger log = LoggerFactory.getLogger(EncryptedMcpServerStore.class);
    private static final String MAGIC = "KEXMCP1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper objectMapper;
    private final Path path;
    private final SecretKeySpec key;

    public EncryptedMcpServerStore(ObjectMapper objectMapper, McpRuntimeProperties properties) {
        this.objectMapper = objectMapper;
        Path configured = Path.of(properties.storagePath());
        this.path = configured.isAbsolute()
                ? configured
                : Path.of(System.getProperty("user.home"), configured.toString());
        this.key = StringUtils.hasText(properties.storageKey()) ? deriveKey(properties.storageKey()) : null;
    }

    public boolean enabled() {
        return key != null;
    }

    public List<PersistedServer> load() {
        if (!enabled() || !Files.exists(path)) return List.of();
        try {
            String[] parts = Files.readString(path, StandardCharsets.UTF_8).split("\\.", 3);
            if (parts.length != 3 || !MAGIC.equals(parts[0])) {
                throw new IllegalStateException("Format du stockage MCP inconnu");
            }
            byte[] nonce = Base64.getUrlDecoder().decode(parts[1]);
            byte[] clear = crypt(Cipher.DECRYPT_MODE, nonce, Base64.getUrlDecoder().decode(parts[2]));
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
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            byte[] clear = objectMapper.writeValueAsBytes(new StoredServers(1, servers));
            byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, nonce, clear);
            String value = MAGIC + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) + "."
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

    private byte[] crypt(int mode, byte[] nonce, byte[] input) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(MAGIC.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(input);
    }

    private static SecretKeySpec deriveKey(String value) {
        try {
            return new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)), "AES");
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 indisponible", ex);
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
