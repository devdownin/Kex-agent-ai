// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.knowledge;

import java.util.Set;

import com.kex.agent.config.ActorIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** ACL is captured at the authenticated boundary, never supplied by the model or request body. */
public record KnowledgeAccess(String owner, Set<String> roles) {
    public KnowledgeAccess {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("Locataire requis");
        roles = Set.copyOf(roles);
    }
    public static KnowledgeAccess current(String owner) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !owner.equals(ActorIdentity.tenantOf(auth))) {
            return new KnowledgeAccess(owner, Set.of("INTERNAL"));
        }
        return new KnowledgeAccess(owner, auth.getAuthorities().stream().map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .collect(java.util.stream.Collectors.toSet()));
    }
}
