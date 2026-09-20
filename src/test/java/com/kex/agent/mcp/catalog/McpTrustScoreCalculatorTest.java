// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpTrustScoreCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("UTC"));
    private final McpCatalogTrustProperties properties = new McpCatalogTrustProperties(Duration.ofDays(180), 2, 5,
            List.of("ROOT", "ADMIN", "SUDO"), 40, 1000, Set.of("https://github.com/brave/"));
    private final McpTrustScoreCalculator calculator = new McpTrustScoreCalculator(properties, clock);

    // ---- Éditeur officiel / identité vérifiée ------------------------------------------------

    @Test
    void official_publisher_is_unknown_without_a_repository() {
        var result = criterion(calculator.score(candidate(base().repositoryUrl(null))), McpTrustCriterion.OFFICIAL_PUBLISHER);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.UNKNOWN);
        assertThat(result.awardedPoints()).isZero();
    }

    @Test
    void official_publisher_is_not_met_for_an_untrusted_repository() {
        var result = criterion(calculator.score(candidate(base().repositoryUrl("https://github.com/unknown/x"))),
                McpTrustCriterion.OFFICIAL_PUBLISHER);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.NOT_MET);
    }

    @Test
    void official_publisher_is_met_for_an_operator_trusted_repository() {
        var result = criterion(calculator.score(candidate(base().repositoryUrl("https://github.com/brave/search"))),
                McpTrustCriterion.OFFICIAL_PUBLISHER);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.MET);
        assertThat(result.awardedPoints()).isEqualTo(20);
    }

    // ---- Provenance source → build vérifiable ----------------------------------------------

    @Test
    void verifiable_provenance_is_met_for_any_docker_source_candidate() {
        var result = criterion(calculator.score(candidate(base().sourceId("docker").repositoryUrl(null))),
                McpTrustCriterion.VERIFIABLE_PROVENANCE);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.MET);
    }

    @Test
    void verifiable_provenance_is_unknown_without_a_repository_outside_docker() {
        var result = criterion(calculator.score(candidate(base().sourceId("official-registry").repositoryUrl(null))),
                McpTrustCriterion.VERIFIABLE_PROVENANCE);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.UNKNOWN);
    }

    @Test
    void verifiable_provenance_is_not_met_when_the_repository_publishes_nothing_installable() {
        var result = criterion(calculator.score(candidate(base().repositoryUrl("https://github.com/x/y")
                        .packages(List.of()).remotes(List.of()))),
                McpTrustCriterion.VERIFIABLE_PROVENANCE);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.NOT_MET);
    }

    @Test
    void verifiable_provenance_is_met_with_a_repository_and_a_published_package() {
        var result = criterion(calculator.score(candidate(base().repositoryUrl("https://github.com/x/y")
                        .packages(List.of(pkg("npm", "x-server", List.of()))))),
                McpTrustCriterion.VERIFIABLE_PROVENANCE);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.MET);
    }

    // ---- Signature / SBOM / CVE : toujours non mesurés aujourd'hui --------------------------

    @Test
    void signature_sbom_and_cve_criteria_are_always_unknown() {
        var score = calculator.score(candidate(base()));
        assertThat(criterion(score, McpTrustCriterion.ARTIFACT_SIGNATURE).state()).isEqualTo(McpTrustCriterionState.UNKNOWN);
        assertThat(criterion(score, McpTrustCriterion.SBOM_AVAILABLE).state()).isEqualTo(McpTrustCriterionState.UNKNOWN);
        assertThat(criterion(score, McpTrustCriterion.DEPENDENCY_CVE_SCAN).state()).isEqualTo(McpTrustCriterionState.UNKNOWN);
    }

    // ---- Projet activement maintenu ----------------------------------------------------------

    @Test
    void actively_maintained_is_unknown_without_a_measured_date() {
        var result = criterion(calculator.score(candidate(base().updatedAt(null))), McpTrustCriterion.ACTIVELY_MAINTAINED);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.UNKNOWN);
    }

    @Test
    void actively_maintained_is_met_within_the_maintenance_window() {
        var result = criterion(calculator.score(candidate(base().updatedAt(NOW.minus(Duration.ofDays(10))))),
                McpTrustCriterion.ACTIVELY_MAINTAINED);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.MET);
    }

    @Test
    void actively_maintained_is_not_met_beyond_the_maintenance_window() {
        var result = criterion(calculator.score(candidate(base().updatedAt(NOW.minus(Duration.ofDays(400))))),
                McpTrustCriterion.ACTIVELY_MAINTAINED);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.NOT_MET);
    }

    // ---- Permissions minimales ----------------------------------------------------------------

    @Test
    void minimal_permissions_is_unknown_without_any_package() {
        var result = criterion(calculator.score(candidate(base().packages(List.of()))),
                McpTrustCriterion.MINIMAL_PERMISSIONS);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.UNKNOWN);
    }

    @Test
    void minimal_permissions_is_met_at_or_below_the_threshold() {
        var result = criterion(calculator.score(candidate(base().packages(List.of(
                        pkg("npm", "x", List.of(requiredSecret("TOKEN"), requiredSecret("KEY"))))))),
                McpTrustCriterion.MINIMAL_PERMISSIONS);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.MET);
    }

    @Test
    void minimal_permissions_is_not_met_above_the_threshold() {
        var result = criterion(calculator.score(candidate(base().packages(List.of(
                        pkg("npm", "x", List.of(requiredSecret("A"), requiredSecret("B"), requiredSecret("C"))))))),
                McpTrustCriterion.MINIMAL_PERMISSIONS);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.NOT_MET);
    }

    // ---- Outils et effets de bord documentés --------------------------------------------------

    @Test
    void documented_tools_is_unknown_without_a_description() {
        var result = criterion(calculator.score(candidate(base().description(null))), McpTrustCriterion.DOCUMENTED_TOOLS);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.UNKNOWN);
    }

    @Test
    void documented_tools_is_not_met_below_the_length_threshold() {
        var result = criterion(calculator.score(candidate(base().description("trop court"))),
                McpTrustCriterion.DOCUMENTED_TOOLS);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.NOT_MET);
    }

    @Test
    void documented_tools_is_met_above_the_length_threshold() {
        var result = criterion(calculator.score(candidate(base()
                        .description("Une description suffisamment longue pour être jugée exploitable."))),
                McpTrustCriterion.DOCUMENTED_TOOLS);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.MET);
    }

    // ---- Isolation / conteneur disponible ------------------------------------------------------

    @Test
    void container_isolation_is_met_for_docker_source_candidates() {
        var result = criterion(calculator.score(candidate(base().sourceId("docker"))),
                McpTrustCriterion.CONTAINER_ISOLATION);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.MET);
    }

    @Test
    void container_isolation_is_met_with_an_oci_package() {
        var result = criterion(calculator.score(candidate(base().sourceId("official-registry")
                        .packages(List.of(pkg("oci", "ghcr.io/x/y", List.of()))))),
                McpTrustCriterion.CONTAINER_ISOLATION);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.MET);
    }

    @Test
    void container_isolation_is_not_met_without_an_oci_package() {
        var result = criterion(calculator.score(candidate(base().sourceId("official-registry")
                        .packages(List.of(pkg("npm", "x", List.of()))))),
                McpTrustCriterion.CONTAINER_ISOLATION);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.NOT_MET);
    }

    // ---- Réputation / adoption ------------------------------------------------------------------

    @Test
    void adoption_reputation_is_unknown_without_any_measurement() {
        var result = criterion(calculator.score(candidate(base().pullCount(null))), McpTrustCriterion.ADOPTION_REPUTATION);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.UNKNOWN);
    }

    @Test
    void adoption_reputation_is_met_above_the_pull_count_threshold() {
        var result = criterion(calculator.score(candidate(base().pullCount(5_000L))), McpTrustCriterion.ADOPTION_REPUTATION);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.MET);
    }

    @Test
    void adoption_reputation_is_not_met_below_the_pull_count_threshold() {
        var result = criterion(calculator.score(candidate(base().pullCount(5L))), McpTrustCriterion.ADOPTION_REPUTATION);
        assertThat(result.state()).isEqualTo(McpTrustCriterionState.NOT_MET);
    }

    // ---- Total et éligibilité -------------------------------------------------------------------

    @Test
    void total_sums_only_the_awarded_points() {
        var score = calculator.score(candidate(base().sourceId("docker").repositoryUrl("https://github.com/brave/x")
                .updatedAt(NOW.minus(Duration.ofDays(1))).pullCount(5_000L)
                .description("Une description suffisamment longue pour être jugée exploitable.")));
        // docker => official(20, dépôt confiance) + provenance(15, source docker) + maintenu(10)
        // + permissions(10, un paquet sans secret requis) + documenté(5) + isolation(5, docker)
        // + adoption(5) = 70 (signature/sbom/cve toujours 0)
        assertThat(score.total()).isEqualTo(20 + 15 + 10 + 10 + 5 + 5 + 5);
        assertThat(score.eligible()).isTrue();
    }

    @Test
    void a_disqualified_candidate_keeps_its_total_but_is_not_eligible() {
        var score = calculator.score(candidate(base().repositoryUrl(null)
                .packages(List.of(pkg("", "mystery-binary", List.of())))));
        assertThat(score.total()).isGreaterThan(0);
        assertThat(score.eligible()).isFalse();
        assertThat(score.disqualifiers()).isNotEmpty();
    }

    // ---- Critères éliminatoires -------------------------------------------------------------------

    @Test
    void disqualifies_a_binary_without_identifiable_provenance() {
        var score = calculator.score(candidate(base().repositoryUrl(null)
                .packages(List.of(pkg("", "mystery-binary", List.of())))));
        assertThat(score.disqualifiers()).anyMatch(reason -> reason.contains("provenance identifiable"));
    }

    @Test
    void does_not_disqualify_a_package_backed_by_a_public_registry() {
        var score = calculator.score(candidate(base().repositoryUrl(null)
                .packages(List.of(pkg("npm", "known-package", List.of())))));
        assertThat(score.disqualifiers()).noneMatch(reason -> reason.contains("provenance identifiable"));
    }

    @Test
    void disqualifies_too_many_required_secrets() {
        var score = calculator.score(candidate(base().packages(List.of(pkg("npm", "x",
                List.of(requiredSecret("A"), requiredSecret("B"), requiredSecret("C"), requiredSecret("D"),
                        requiredSecret("E"), requiredSecret("F")))))));
        assertThat(score.disqualifiers()).anyMatch(reason -> reason.contains("secrets requis"));
    }

    @Test
    void disqualifies_a_broad_credential_keyword() {
        var score = calculator.score(candidate(base().packages(List.of(
                pkg("npm", "x", List.of(requiredSecret("AWS_ROOT_ACCESS_KEY")))))));
        assertThat(score.disqualifiers()).anyMatch(reason -> reason.contains("disproportionné"));
    }

    @Test
    void does_not_disqualify_a_reasonable_number_of_narrow_secrets() {
        var score = calculator.score(candidate(base().packages(List.of(
                pkg("npm", "x", List.of(requiredSecret("API_KEY")))))));
        assertThat(score.disqualifiers()).isEmpty();
    }

    @Test
    void disqualifies_a_global_filesystem_default_value() {
        var variable = new McpCatalogEnvironmentVariable("MOUNT", true, false, "/");
        var score = calculator.score(candidate(base().packages(List.of(
                new McpCatalogPackage("npm", "x", null, null, List.of(), List.of(), List.of(variable))))));
        assertThat(score.disqualifiers()).anyMatch(reason -> reason.contains("filesystem global"));
    }

    @Test
    void does_not_disqualify_a_specific_filesystem_default_value() {
        var variable = new McpCatalogEnvironmentVariable("MOUNT", true, false, "/tmp/kex");
        var score = calculator.score(candidate(base().packages(List.of(
                new McpCatalogPackage("npm", "x", null, null, List.of(), List.of(), List.of(variable))))));
        assertThat(score.disqualifiers()).noneMatch(reason -> reason.contains("filesystem global"));
    }

    @Test
    void disqualifies_an_unmanaged_shell_invocation() {
        var score = calculator.score(candidate(base().packages(List.of(
                new McpCatalogPackage("", "custom", null, "bash", List.of(), List.of(), List.of())))));
        assertThat(score.disqualifiers()).anyMatch(reason -> reason.contains("shell non justifiée"));
    }

    @Test
    void does_not_disqualify_a_managed_runtime_hint() {
        var score = calculator.score(candidate(base().packages(List.of(
                new McpCatalogPackage("npm", "x", null, "npx", List.of(), List.of(), List.of())))));
        assertThat(score.disqualifiers()).noneMatch(reason -> reason.contains("shell non justifiée"));
    }

    @Test
    void disqualifies_a_remote_without_declared_headers() {
        var score = calculator.score(candidate(base()
                .remotes(List.of(new McpCatalogRemote("streamable-http", "https://x.example.com/mcp", List.of())))
                .description("Une description suffisamment longue pour être jugée exploitable.")));
        assertThat(score.disqualifiers()).anyMatch(reason -> reason.contains("authentification"));
    }

    @Test
    void does_not_disqualify_a_remote_with_declared_headers() {
        var score = calculator.score(candidate(base()
                .remotes(List.of(new McpCatalogRemote("streamable-http", "https://x.example.com/mcp",
                        List.of("Authorization"))))
                .description("Une description suffisamment longue pour être jugée exploitable.")));
        assertThat(score.disqualifiers()).noneMatch(reason -> reason.contains("authentification"));
    }

    @Test
    void disqualifies_an_undocumented_remote() {
        var score = calculator.score(candidate(base()
                .remotes(List.of(new McpCatalogRemote("streamable-http", "https://x.example.com/mcp",
                        List.of("Authorization"))))
                .description(null)));
        assertThat(score.disqualifiers()).anyMatch(reason -> reason.contains("réseau non documenté"));
    }

    // ---- Fixtures ---------------------------------------------------------------------------

    private static Fixture base() {
        return new Fixture("official-registry", "https://github.com/x/y",
                List.of(pkg("npm", "x-server", List.of())), List.of(), NOW.minus(Duration.ofDays(1)), "active",
                "Une description suffisamment longue pour être jugée exploitable.", 5_000L);
    }

    private static McpCatalogCandidate candidate(Fixture fixture) {
        return new McpCatalogCandidate(fixture.sourceId, "candidate", "candidate", "Candidate", fixture.description,
                fixture.repositoryUrl, fixture.repositoryUrl == null ? null : "github", fixture.packages,
                fixture.remotes, fixture.updatedAt, fixture.status, fixture.pullCount, null);
    }

    private static McpCatalogPackage pkg(String registryType, String identifier,
                                         List<McpCatalogEnvironmentVariable> variables) {
        return new McpCatalogPackage(registryType, identifier, null, null, List.of(), List.of(), variables);
    }

    private static McpCatalogEnvironmentVariable requiredSecret(String name) {
        return new McpCatalogEnvironmentVariable(name, true, true, null);
    }

    private static McpTrustCriterionResult criterion(McpTrustScore score, McpTrustCriterion criterion) {
        return score.criteria().stream().filter(result -> result.criterion() == criterion).findFirst().orElseThrow();
    }

    /** Fixture mutable, uniquement pour composer des variantes lisibles dans chaque test. */
    private static final class Fixture {
        String sourceId;
        String repositoryUrl;
        List<McpCatalogPackage> packages;
        List<McpCatalogRemote> remotes;
        Instant updatedAt;
        String status;
        String description;
        Long pullCount;

        Fixture(String sourceId, String repositoryUrl, List<McpCatalogPackage> packages,
               List<McpCatalogRemote> remotes, Instant updatedAt, String status, String description, Long pullCount) {
            this.sourceId = sourceId;
            this.repositoryUrl = repositoryUrl;
            this.packages = packages;
            this.remotes = remotes;
            this.updatedAt = updatedAt;
            this.status = status;
            this.description = description;
            this.pullCount = pullCount;
        }

        Fixture sourceId(String value) {
            this.sourceId = value;
            return this;
        }

        Fixture repositoryUrl(String value) {
            this.repositoryUrl = value;
            return this;
        }

        Fixture packages(List<McpCatalogPackage> value) {
            this.packages = value;
            return this;
        }

        Fixture remotes(List<McpCatalogRemote> value) {
            this.remotes = value;
            return this;
        }

        Fixture updatedAt(Instant value) {
            this.updatedAt = value;
            return this;
        }

        Fixture description(String value) {
            this.description = value;
            return this;
        }

        Fixture pullCount(Long value) {
            this.pullCount = value;
            return this;
        }
    }
}
