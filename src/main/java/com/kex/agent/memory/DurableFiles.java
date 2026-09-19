// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Local single-instance storage. A failed write never publishes partial JSON. */
public final class DurableFiles {
    private final ObjectMapper mapper;
    private final Path path;

    public DurableFiles(ObjectMapper mapper, Path path) {
        this.mapper = mapper;
        this.path = path.toAbsolutePath();
    }

    public <T> T read(Class<T> type, T empty) {
        if (!Files.exists(path)) return empty;
        try {
            return mapper.readValue(path.toFile(), type);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Impossible de lire la mémoire durable : " + path, ex);
        }
    }

    public void write(Object value) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), ".kex-memory-", ".tmp");
            try {
                try {
                    Files.setPosixFilePermissions(temporary, Set.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE));
                }
                catch (UnsupportedOperationException ignored) { /* Windows uses directory ACLs. */ }
                mapper.writeValue(temporary.toFile(), value);
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            finally {
                Files.deleteIfExists(temporary);
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException("Impossible de persister la mémoire durable : " + path, ex);
        }
    }
}
