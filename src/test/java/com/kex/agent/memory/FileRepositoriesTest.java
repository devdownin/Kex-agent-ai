// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void durable_files_handles_read_write_and_errors() throws IOException {
        Path path = tempDir.resolve("durable.json");
        DurableFiles durable = new DurableFiles(mapper, path);

        assertThat(durable.read(MemoryEntry[].class, new MemoryEntry[0])).isEmpty();

        durable.write(List.of(new MemoryEntry("m-1", "content", "conv-1", Instant.now(), null, "user1")));
        assertThat(durable.read(MemoryEntry[].class, new MemoryEntry[0])).hasSize(1);

        Files.writeString(path, "{invalid json");
        assertThatThrownBy(() -> durable.read(MemoryEntry[].class, new MemoryEntry[0]))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void file_memory_repository_adds_supersedes_and_forgets_with_branch_coverage() {
        Path path = tempDir.resolve("memory.json");
        FileMemoryRepository repo = new FileMemoryRepository(mapper, path, 2);

        MemoryEntry entry1 = new MemoryEntry("m-1", "fact 1", "conv-1", Instant.now(), null, "user1");
        repo.add(entry1);

        assertThat(repo.active(Instant.EPOCH)).containsExactly(entry1);

        // Fail to supersede non-existent or already superseded entry
        assertThat(repo.supersede("unknown", "m-2")).isFalse();
        assertThat(repo.supersede("m-1", "m-2")).isTrue();
        assertThat(repo.supersede("m-1", "m-3")).isFalse(); // already superseded

        assertThat(repo.active(Instant.EPOCH)).isEmpty();

        MemoryEntry entry2 = new MemoryEntry("m-2", "fact 2", "conv-1", Instant.now(), null, "user1");
        MemoryEntry entry3 = new MemoryEntry("m-3", "fact 3", "conv-1", Instant.now(), null, "user1");
        repo.add(entry2);

        assertThat(repo.forget("non-existent")).isEmpty();
        assertThat(repo.forget("m-2")).contains(entry2);

        // Test capacity eviction
        repo.add(entry2);
        repo.add(entry3);
        MemoryEntry entry4 = new MemoryEntry("m-4", "fact 4", "conv-1", Instant.now(), null, "user1");
        repo.add(entry4);
        assertThat(repo.active(Instant.EPOCH)).hasSize(2);
    }

    @Test
    void file_learning_repository_adds_lists_reviews_and_deletes_with_branch_coverage() {
        Path path = tempDir.resolve("learning.json");
        FileLearningRepository repo = new FileLearningRepository(mapper, path, 2);

        LearningEntry entry1 = new LearningEntry("l-1", "user1", "SKILL", "Skill Title 1", "Markdown 1", "Proof",
                "conv-1", Instant.now(), "PENDING", null, null, null);
        LearningEntry entry2 = new LearningEntry("l-2", "user1", "SKILL", "Skill Title 2", "Markdown 2", "Proof",
                "conv-1", Instant.now(), "PENDING", null, null, null);

        repo.add(entry1);
        repo.add(entry2);

        // Capacity eviction for same kind
        LearningEntry entry3 = new LearningEntry("l-3", "user1", "SKILL", "Skill Title 3", "Markdown 3", "Proof",
                "conv-1", Instant.now(), "PENDING", null, null, null);
        repo.add(entry3);

        List<LearningEntry> list = repo.list("user1", "SKILL", Instant.EPOCH);
        assertThat(list).hasSize(2);

        // Review negative branches (wrong id, wrong owner, wrong kind, wrong status)
        assertThat(repo.review("user1", "unknown", "APPROVED", "admin", Instant.now(), "Approved")).isFalse();
        assertThat(repo.review("other-user", "l-3", "APPROVED", "admin", Instant.now(), "Approved")).isFalse();

        boolean reviewed = repo.review("user1", "l-3", "APPROVED", "admin", Instant.now(), "Approved");
        assertThat(reviewed).isTrue();

        // Cannot review already approved skill
        assertThat(repo.review("user1", "l-3", "APPROVED", "admin", Instant.now(), "Approved")).isFalse();

        // Delete negative branch
        assertThat(repo.delete("user1", "unknown-id")).isFalse();

        boolean deleted = repo.delete("user1", "l-3");
        assertThat(deleted).isTrue();
    }
}
