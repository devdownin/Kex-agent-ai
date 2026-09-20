// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class FileRepositoriesTest {

    @TempDir
    Path tempDir;

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    @Test
    void file_memory_repository_adds_supersedes_and_forgets() {
        Path path = tempDir.resolve("memory.json");
        FileMemoryRepository repo = new FileMemoryRepository(mapper, path, 10);

        MemoryEntry entry1 = new MemoryEntry("m-1", "fact 1", "conv-1", Instant.now(), null, "user1");
        repo.add(entry1);

        assertThat(repo.active(Instant.EPOCH)).containsExactly(entry1);

        assertThat(repo.supersede("m-1", "m-2")).isTrue();
        assertThat(repo.active(Instant.EPOCH)).isEmpty();

        MemoryEntry entry2 = new MemoryEntry("m-2", "fact 2", "conv-1", Instant.now(), null, "user1");
        repo.add(entry2);

        assertThat(repo.forget("m-2")).contains(entry2);
        assertThat(repo.active(Instant.EPOCH)).isEmpty();
    }

    @Test
    void file_learning_repository_adds_lists_reviews_and_deletes() {
        Path path = tempDir.resolve("learning.json");
        FileLearningRepository repo = new FileLearningRepository(mapper, path, 10);

        LearningEntry entry = new LearningEntry("l-1", "user1", "SKILL", "Skill Title", "Markdown", "Proof",
                "conv-1", Instant.now(), "PENDING", null, null, null);

        repo.add(entry);

        List<LearningEntry> list = repo.list("user1", "SKILL", Instant.EPOCH);
        assertThat(list).containsExactly(entry);

        boolean reviewed = repo.review("user1", "l-1", "APPROVED", "admin", Instant.now(), "Approved");
        assertThat(reviewed).isTrue();

        List<LearningEntry> reviewedList = repo.list("user1", "SKILL", Instant.EPOCH);
        assertThat(reviewedList.get(0).status()).isEqualTo("APPROVED");

        boolean deleted = repo.delete("user1", "l-1");
        assertThat(deleted).isTrue();
        assertThat(repo.list("user1", "SKILL", Instant.EPOCH)).isEmpty();
    }
}
