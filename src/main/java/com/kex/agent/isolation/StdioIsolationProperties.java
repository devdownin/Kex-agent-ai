// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.isolation;

import java.nio.file.Path;
import java.util.Map;

import org.springframework.boot.context.properties.bind.DefaultValue;

/** Operator-owned policy; runtime registrations cannot supply Docker options or mounts. */
public record StdioIsolationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("/usr/bin/docker") String executable,
        Map<String, String> images,
        @DefaultValue("256") int memoryMb,
        @DefaultValue("1.0") double cpus,
        @DefaultValue("64") int pidsLimit) {

    public StdioIsolationProperties {
        images = images == null ? Map.of() : Map.copyOf(images);
        if (executable == null || !Path.of(executable).isAbsolute() || executable.contains("\n")) {
            throw new IllegalArgumentException("Isolation requires an absolute Docker executable path");
        }
        if (memoryMb < 16 || memoryMb > 65536 || !Double.isFinite(cpus) || cpus <= 0 || cpus > 64
                || pidsLimit < 1 || pidsLimit > 4096) {
            throw new IllegalArgumentException("Invalid isolation resource limits");
        }
        images.values().forEach(image -> {
            if (!image.matches("[a-zA-Z0-9][a-zA-Z0-9._/:@-]{0,511}")) {
                throw new IllegalArgumentException("Invalid isolation image reference");
            }
        });
    }

    public static StdioIsolationProperties defaults() {
        return new StdioIsolationProperties(false, "/usr/bin/docker", Map.of(), 256, 1.0, 64);
    }
}
