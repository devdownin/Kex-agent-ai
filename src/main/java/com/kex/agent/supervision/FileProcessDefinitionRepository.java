// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Stockage mono-instance : la liste en mémoire n'est remplacée qu'après écriture complète. */
class FileProcessDefinitionRepository implements ProcessDefinitionRepository {

    private final ObjectMapper json;
    private final Path path;
    private List<MonitoredProcess> saved;

    FileProcessDefinitionRepository(ObjectMapper json, Path path) {
        this.json = json;
        this.path = path.toAbsolutePath();
        try {
            this.saved = Files.exists(this.path)
                    ? List.of(json.readValue(this.path.toFile(), MonitoredProcess[].class)) : List.of();
        }
        catch (IOException ex) {
            throw new IllegalStateException("Impossible de lire les processus enregistrés", ex);
        }
    }

    @Override
    public synchronized List<MonitoredProcess> all() {
        return saved;
    }

    @Override
    public synchronized void create(MonitoredProcess process) {
        if (saved.stream().anyMatch(existing -> existing.id().equals(process.id()))) {
            throw new ProcessDefinitionConflict(process.id());
        }
        List<MonitoredProcess> updated = new ArrayList<>(saved);
        updated.add(process);
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), ".processes-", ".tmp");
            json.writeValue(temporary.toFile(), updated);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            saved = List.copyOf(updated);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Impossible d'enregistrer le processus", ex);
        }
        finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                }
                catch (IOException ignored) {
                    // Le fichier actif a déjà été déplacé ; ce résidu temporaire sera nettoyé plus tard.
                }
            }
        }
    }
}
