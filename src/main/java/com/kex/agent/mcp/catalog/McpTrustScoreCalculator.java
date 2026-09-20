// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Calcul déterministe, jamais soumis au modèle : une décision qui conditionne l'installation d'un
 * serveur MCP doit être reproductible et testable comme n'importe quelle autre règle de sécurité du
 * projet, pas laissée au jugement du modèle — même doctrine que {@code SupervisionPolicy}, dont le
 * seuil de confiance est un nombre fixé par la configuration, jamais une opinion recalculée par le
 * modèle à chaque cycle.
 *
 * <p>Trois critères (signature, SBOM, analyse CVE — 30 points sur 100) restent {@code UNKNOWN} pour
 * tout candidat aujourd'hui : ni le Docker MCP Catalog ni le registre officiel MCP, les deux seules
 * sources branchées, ne publient ce signal dans les champs consultés ici. Les inventer ferait
 * passer une absence de mesure pour une garantie — écrit ici plutôt que masqué, à réviser le jour où
 * une source qui les porte réellement (ex. Docker Scout) sera branchée.
 */
@Service
public class McpTrustScoreCalculator {

    private static final Set<String> RESOLVABLE_PACKAGE_REGISTRIES =
            Set.of("npm", "pypi", "cargo", "oci", "nuget", "mcpb");
    private static final Set<String> SHELL_RUNTIME_HINTS = Set.of("sh", "bash", "zsh", "cmd", "powershell");
    private static final Set<String> GLOBAL_FILESYSTEM_PATHS = Set.of("/", "~");

    private final McpCatalogTrustProperties properties;
    private final Clock clock;

    public McpTrustScoreCalculator(McpCatalogTrustProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public McpTrustScore score(McpCatalogCandidate candidate) {
        List<McpTrustCriterionResult> criteria = List.of(
                officialPublisher(candidate),
                verifiableProvenance(candidate),
                unmeasured(McpTrustCriterion.ARTIFACT_SIGNATURE),
                unmeasured(McpTrustCriterion.SBOM_AVAILABLE),
                unmeasured(McpTrustCriterion.DEPENDENCY_CVE_SCAN),
                activelyMaintained(candidate),
                minimalPermissions(candidate),
                documentedTools(candidate),
                containerIsolation(candidate),
                adoptionReputation(candidate));
        return McpTrustScore.of(criteria, disqualifiers(candidate));
    }

    private McpTrustCriterionResult officialPublisher(McpCatalogCandidate candidate) {
        McpTrustCriterion criterion = McpTrustCriterion.OFFICIAL_PUBLISHER;
        if (!StringUtils.hasText(candidate.repositoryUrl())) {
            return McpTrustCriterionResult.unknown(criterion, "Aucun dépôt source déclaré par la source");
        }
        boolean trusted = properties.trustedPublisherRepositories().stream()
                .anyMatch(prefix -> candidate.repositoryUrl().startsWith(prefix));
        return trusted
                ? McpTrustCriterionResult.met(criterion, "Dépôt listé dans kex.mcp.catalog.trust.trusted-publisher-repositories")
                : McpTrustCriterionResult.notMet(criterion,
                        "Dépôt " + candidate.repositoryUrl() + " absent de la liste d'éditeurs vérifiés par l'opérateur");
    }

    private McpTrustCriterionResult verifiableProvenance(McpCatalogCandidate candidate) {
        McpTrustCriterion criterion = McpTrustCriterion.VERIFIABLE_PROVENANCE;
        // Docker construit lui-même chaque image de l'espace de nommage `mcp/`, à partir du dépôt
        // source soumis — signal propre à cette source, indépendant du champ repositoryUrl que
        // l'API Docker Hub elle-même n'expose pas dans le relevé massif consulté ici.
        if ("docker".equals(candidate.sourceId())) {
            return McpTrustCriterionResult.met(criterion,
                    "Image construite par Docker dans l'espace de nommage mcp/ à partir du dépôt soumis");
        }
        if (!StringUtils.hasText(candidate.repositoryUrl())) {
            return McpTrustCriterionResult.unknown(criterion, "Aucun dépôt source déclaré par la source");
        }
        if (candidate.packages().isEmpty() && candidate.remotes().isEmpty()) {
            return McpTrustCriterionResult.notMet(criterion,
                    "Dépôt déclaré mais aucun paquet ni point d'accès publié à partir de ce dépôt");
        }
        return McpTrustCriterionResult.met(criterion, "Dépôt source public : " + candidate.repositoryUrl());
    }

    private static McpTrustCriterionResult unmeasured(McpTrustCriterion criterion) {
        return McpTrustCriterionResult.unknown(criterion,
                "Aucune source branchée ne publie ce signal pour l'instant");
    }

    private McpTrustCriterionResult activelyMaintained(McpCatalogCandidate candidate) {
        McpTrustCriterion criterion = McpTrustCriterion.ACTIVELY_MAINTAINED;
        Instant updatedAt = candidate.updatedAt();
        if (updatedAt == null) {
            return McpTrustCriterionResult.unknown(criterion, "Aucune date de mise à jour mesurée");
        }
        Instant threshold = clock.instant().minus(properties.maintenanceWindow());
        return updatedAt.isAfter(threshold)
                ? McpTrustCriterionResult.met(criterion, "Mis à jour le " + updatedAt)
                : McpTrustCriterionResult.notMet(criterion,
                        "Dernière mise à jour le " + updatedAt + ", au-delà de " + properties.maintenanceWindow());
    }

    private McpTrustCriterionResult minimalPermissions(McpCatalogCandidate candidate) {
        McpTrustCriterion criterion = McpTrustCriterion.MINIMAL_PERMISSIONS;
        if (candidate.packages().isEmpty()) {
            return McpTrustCriterionResult.unknown(criterion, "Aucun paquet déclaré : permissions non observables");
        }
        int required = requiredSecrets(candidate).size();
        return required <= properties.minimalPermissionsSecretThreshold()
                ? McpTrustCriterionResult.met(criterion, required + " secret(s) requis")
                : McpTrustCriterionResult.notMet(criterion,
                        required + " secrets requis, au-delà de " + properties.minimalPermissionsSecretThreshold());
    }

    private McpTrustCriterionResult documentedTools(McpCatalogCandidate candidate) {
        McpTrustCriterion criterion = McpTrustCriterion.DOCUMENTED_TOOLS;
        if (candidate.description() == null) {
            return McpTrustCriterionResult.unknown(criterion, "Aucune description mesurée");
        }
        int length = candidate.description().strip().length();
        // Une longueur de description est un indice mécanique, pas un jugement de qualité : elle ne
        // dit rien de la justesse de ce qui est décrit, seulement qu'il y a matière à lire.
        return length >= properties.minDocumentedDescriptionLength()
                ? McpTrustCriterionResult.met(criterion, "Description de " + length + " caractères")
                : McpTrustCriterionResult.notMet(criterion,
                        "Description de " + length + " caractères, en deçà de "
                                + properties.minDocumentedDescriptionLength());
    }

    private McpTrustCriterionResult containerIsolation(McpCatalogCandidate candidate) {
        McpTrustCriterion criterion = McpTrustCriterion.CONTAINER_ISOLATION;
        if ("docker".equals(candidate.sourceId())
                || candidate.packages().stream().anyMatch(p -> "oci".equalsIgnoreCase(p.registryType()))) {
            return McpTrustCriterionResult.met(criterion, "Disponible en image de conteneur (OCI)");
        }
        if (candidate.packages().isEmpty()) {
            return McpTrustCriterionResult.unknown(criterion, "Aucun paquet déclaré : isolation non observable");
        }
        return McpTrustCriterionResult.notMet(criterion, "Aucun paquet de type OCI déclaré");
    }

    private McpTrustCriterionResult adoptionReputation(McpCatalogCandidate candidate) {
        McpTrustCriterion criterion = McpTrustCriterion.ADOPTION_REPUTATION;
        if (candidate.pullCount() == null && candidate.starCount() == null) {
            return McpTrustCriterionResult.unknown(criterion, "Aucune mesure d'adoption disponible");
        }
        if (candidate.pullCount() != null && candidate.pullCount() >= properties.minAdoptionPullCount()) {
            return McpTrustCriterionResult.met(criterion, candidate.pullCount() + " tirages");
        }
        return McpTrustCriterionResult.notMet(criterion,
                (candidate.pullCount() == null ? "0" : candidate.pullCount()) + " tirages, en deçà de "
                        + properties.minAdoptionPullCount());
    }

    private List<String> disqualifiers(McpCatalogCandidate candidate) {
        List<String> reasons = new ArrayList<>();
        noIdentifiableProvenance(candidate).ifPresent(reasons::add);
        disproportionateCredentials(candidate).ifPresent(reasons::add);
        globalFilesystemAccess(candidate).ifPresent(reasons::add);
        unjustifiedShellExecution(candidate).ifPresent(reasons::add);
        unauthenticatedSensitiveRemote(candidate).ifPresent(reasons::add);
        undocumentedNetworkBehavior(candidate).ifPresent(reasons::add);
        return List.copyOf(reasons);
    }

    /** S'applique à ce qui s'exécute localement : un candidat uniquement distant n'a pas de « binaire ». */
    private static Optional<String> noIdentifiableProvenance(McpCatalogCandidate candidate) {
        if (candidate.packages().isEmpty()) return Optional.empty();
        boolean resolvable = candidate.packages().stream()
                .anyMatch(p -> RESOLVABLE_PACKAGE_REGISTRIES.contains(lower(p.registryType()))
                        && StringUtils.hasText(p.identifier()));
        if (resolvable || StringUtils.hasText(candidate.repositoryUrl())) return Optional.empty();
        return Optional.of("Binaire sans provenance identifiable : ni dépôt source, ni paquet issu "
                + "d'un registre public reconnu (npm, pypi, cargo, oci, nuget, mcpb)");
    }

    private Optional<String> disproportionateCredentials(McpCatalogCandidate candidate) {
        Set<String> required = requiredSecrets(candidate);
        if (required.size() > properties.maxRequiredSecrets()) {
            return Optional.of("Demande de " + required.size() + " secrets requis, au-delà du plafond "
                    + properties.maxRequiredSecrets());
        }
        return required.stream()
                .filter(name -> properties.broadCredentialKeywords().stream()
                        .anyMatch(keyword -> lower(name).contains(lower(keyword))))
                .findFirst()
                .map(name -> "Secret requis au nom disproportionné : " + name);
    }

    private static Optional<String> globalFilesystemAccess(McpCatalogCandidate candidate) {
        for (McpCatalogPackage pkg : candidate.packages()) {
            boolean matches = concat(pkg.runtimeArguments(), pkg.packageArguments()).stream()
                    .anyMatch(McpTrustScoreCalculator::isGlobalFilesystemPath)
                    || pkg.environmentVariables().stream().map(McpCatalogEnvironmentVariable::defaultValue)
                            .filter(StringUtils::hasText).anyMatch(McpTrustScoreCalculator::isGlobalFilesystemPath);
            if (matches) return Optional.of("Accès filesystem global : un argument ou une valeur par défaut "
                    + "désigne la racine du système de fichiers");
        }
        return Optional.empty();
    }

    private static Optional<String> unjustifiedShellExecution(McpCatalogCandidate candidate) {
        for (McpCatalogPackage pkg : candidate.packages()) {
            boolean unmanaged = !RESOLVABLE_PACKAGE_REGISTRIES.contains(lower(pkg.registryType()));
            boolean shellHint = SHELL_RUNTIME_HINTS.contains(lower(pkg.runtimeHint()));
            if (unmanaged && shellHint) {
                return Optional.of("Exécution shell non justifiée : commande '" + pkg.runtimeHint()
                        + "' sans paquet de registre public identifié derrière");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> unauthenticatedSensitiveRemote(McpCatalogCandidate candidate) {
        return candidate.remotes().stream().filter(remote -> remote.headerNames().isEmpty()).findFirst()
                .map(remote -> "Absence d'authentification pour un MCP distant : " + remote.url()
                        + " ne déclare aucun en-tête");
    }

    private Optional<String> undocumentedNetworkBehavior(McpCatalogCandidate candidate) {
        if (candidate.remotes().isEmpty()) return Optional.empty();
        int length = candidate.description() == null ? 0 : candidate.description().strip().length();
        if (length < properties.minDocumentedDescriptionLength()) {
            return Optional.of("Comportement réseau non documenté : point d'accès distant déclaré sans "
                    + "description exploitable (" + length + " caractères)");
        }
        return Optional.empty();
    }

    private static Set<String> requiredSecrets(McpCatalogCandidate candidate) {
        Set<String> names = new LinkedHashSet<>();
        for (McpCatalogPackage pkg : candidate.packages()) {
            for (McpCatalogEnvironmentVariable variable : pkg.environmentVariables()) {
                if (variable.required() && variable.secret()) names.add(variable.name());
            }
        }
        return names;
    }

    private static boolean isGlobalFilesystemPath(String value) {
        return GLOBAL_FILESYSTEM_PATHS.contains(value.strip()) || value.strip().matches("^[A-Za-z]:\\\\?$");
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> combined = new ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
