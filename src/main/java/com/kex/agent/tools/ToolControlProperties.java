// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.tools;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Règles administrateur : ni le prompt ni les métadonnées d'un serveur ne les modifient. */
@ConfigurationProperties("kex.agent.tools")
@Validated
public record ToolControlProperties(
        @DefaultValue("false") boolean selectionEnabled,
        @DefaultValue("12") @Positive int maxSelectedTools,
        @DefaultValue("12000") @Min(128) int maxResultCharacters,
        @DefaultValue("32000") @Positive int maxInputCharacters,
        @DefaultValue Map<String, Rule> rules,
        @DefaultValue List<String> deniedOutputPatterns,
        @DefaultValue List<String> deniedInputPatterns) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ToolControlProperties {
        rules = rules == null ? Map.of() : Map.copyOf(rules);
        deniedOutputPatterns = deniedOutputPatterns == null ? List.of() : List.copyOf(deniedOutputPatterns);
        deniedInputPatterns = deniedInputPatterns == null ? List.of() : List.copyOf(deniedInputPatterns);
        if (maxSelectedTools < 1 || maxResultCharacters < 128 || maxInputCharacters < 1) {
            throw new IllegalArgumentException("Les plafonds d'outils et de contenu doivent être positifs");
        }
    }

    public ToolControlProperties(boolean selectionEnabled, int maxSelectedTools, int maxResultCharacters,
                                 int maxInputCharacters, Map<String, Rule> rules, List<String> deniedOutputPatterns) {
        this(selectionEnabled, maxSelectedTools, maxResultCharacters, maxInputCharacters, rules, deniedOutputPatterns, List.of());
    }

    public static ToolControlProperties defaults() {
        return new ToolControlProperties(false, 12, 12000, 32000, Map.of(), List.of());
    }

    /** Les clés d'arguments et de destinations sont des JSON Pointers exacts et obligatoires. */
    public record Rule(@DefaultValue("false") boolean denied,
                       @DefaultValue("false") boolean readOnly,
                       @DefaultValue Map<String, Set<String>> allowedArguments,
                       @DefaultValue Map<String, Destination> destinations) {
        public Rule {
            allowedArguments = allowedArguments == null ? Map.of() : allowedArguments.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                            entry -> Set.copyOf(entry.getValue())));
            destinations = destinations == null ? Map.of() : Map.copyOf(destinations);
        }
    }

    public record Destination(@DefaultValue Set<String> hosts,
                              @DefaultValue("https") Set<String> schemes) {
        public Destination {
            hosts = hosts == null ? Set.of() : Set.copyOf(hosts);
            schemes = schemes == null ? Set.of("https") : Set.copyOf(schemes);
        }
    }
}
