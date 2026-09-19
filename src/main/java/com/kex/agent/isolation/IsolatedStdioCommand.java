// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.isolation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** One isolated MCP process and private secret file, disposed with its client. No shell is used. */
public final class IsolatedStdioCommand implements AutoCloseable {
    private final String executable;
    private final String name = "kex-task-" + UUID.randomUUID();
    private final List<String> args;
    private Path environmentFile;

    public IsolatedStdioCommand(StdioIsolationProperties policy, String command, List<String> arguments,
                                Map<String, String> environment) {
        executable = policy.executable();
        String image = policy.images().get(command);
        if (!policy.enabled() || image == null) {
            throw new IllegalArgumentException("No approved isolation image for STDIO command");
        }
        if (command == null || command.startsWith("-") || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid isolated command");
        }
        arguments.forEach(value -> {
            if (value == null || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid STDIO argument");
        });
        environment.forEach((key, value) -> {
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*") || value == null || value.contains("\n")
                    || value.contains("\r") || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid container environment entry");
            }
        });
        args = new ArrayList<>(List.of("run", "--rm", "-i", "--name", name, "--pull=never",
                "--read-only", "--user=65532:65532", "--cap-drop=ALL", "--security-opt=no-new-privileges:true",
                "--network=none", "--memory=" + policy.memoryMb() + "m", "--memory-swap=" + policy.memoryMb() + "m",
                "--cpus=" + policy.cpus(), "--pids-limit=" + policy.pidsLimit(),
                "--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777", "--workdir=/tmp",
                "--entrypoint=" + command));
        if (!environment.isEmpty()) {
            try {
                environmentFile = Files.createTempFile("kex-container-", ".env",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                List<String> lines = environment.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
                Files.write(environmentFile, lines, StandardCharsets.UTF_8);
                args.add("--env-file=" + environmentFile);
            }
            catch (IOException | UnsupportedOperationException ex) {
                deleteEnvironmentFile();
                throw new IllegalStateException("Cannot create private container environment", ex);
            }
        }
        args.add(image);
        args.addAll(arguments);
    }

    public String executable() { return executable; }
    public List<String> arguments() { return List.copyOf(args); }

    @Override
    public void close() {
        try {
            Process cleanup = new ProcessBuilder(executable, "rm", "--force", name)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!cleanup.waitFor(5, TimeUnit.SECONDS)) cleanup.destroyForcibly();
        }
        catch (IOException ex) {
            // run --rm still handles a normally terminated Docker client; callers receive launch errors.
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        finally {
            deleteEnvironmentFile();
        }
    }

    private void deleteEnvironmentFile() {
        if (environmentFile == null) return;
        try { Files.deleteIfExists(environmentFile); }
        catch (IOException ex) { throw new IllegalStateException("Cannot remove private container environment", ex); }
        environmentFile = null;
    }
}
