// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

class FileForecastAssociationRepository implements ForecastAssociationRepository {
    private final ObjectMapper json;
    private final Path path;
    private Map<String, List<ForecastAssociation>> saved;

    FileForecastAssociationRepository(ObjectMapper json, Path path) {
        this.json = json;
        this.path = path.toAbsolutePath();
        try {
            saved = Files.exists(this.path) ? json.readValue(this.path.toFile(), new TypeReference<>() { }) : Map.of();
            saved = Map.copyOf(saved);
        } catch (IOException ex) {
            throw new IllegalStateException("Impossible de lire les associations TimesFM", ex);
        }
    }

    public synchronized Map<String, List<ForecastAssociation>> all() { return saved; }

    public synchronized void replace(String id, List<ForecastAssociation> associations) {
        var updated = new LinkedHashMap<>(saved);
        updated.put(id, List.copyOf(associations));
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), ".forecasts-", ".tmp");
            json.writeValue(temporary.toFile(), updated);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            saved = Map.copyOf(updated);
        } catch (IOException ex) {
            throw new IllegalStateException("Impossible d’enregistrer les associations TimesFM", ex);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* inactive residue only */ }
            }
        }
    }
}
