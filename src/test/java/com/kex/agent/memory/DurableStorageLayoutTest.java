// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.kex.agent.mcp.McpRuntimeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tout ce que l'agent retient hors base vivait dans la couche d'écriture du conteneur : souvenirs
 * long terme, compétences approuvées par un humain, charte, et connexions MCP chiffrées. Un
 * {@code docker compose up --force-recreate} les emportait — et rien ne le disait, puisque
 * l'agent redémarrait très bien, simplement amnésique.
 *
 * <p>Ce test tient sur deux choses qu'aucune relecture du {@code Dockerfile} ne vérifie seule :
 * que les variables déclarées portent bien les noms que Spring traduit vers les propriétés que le
 * code lit — une faute de frappe dans un {@code ENV} ne fait rien échouer au démarrage — et que
 * les chemins tombent sous le volume. {@code user.home} ne suffisait pas : l'uid {@code 10001} n'a
 * pas d'entrée dans {@code /etc/passwd}, {@code getpwuid} n'a donc rien à rendre, et la valeur de
 * repli dépend de la JVM.
 */
class DurableStorageLayoutTest {

    private static final Pattern ENV = Pattern.compile("^\\s*(?:ENV\\s+)?(KEX_[A-Z0-9_]+)=(\\S+?)\\s*\\\\?$",
            Pattern.MULTILINE);
    private static final Pattern VOLUME = Pattern.compile("^VOLUME\\s+(\\S+)\\s*$", Pattern.MULTILINE);

    private final String dockerfile = read();
    private final StandardEnvironment environment = environment();

    @Test
    void le_dockerfile_declare_un_volume_pour_l_etat() {
        Matcher volume = VOLUME.matcher(dockerfile);

        assertThat(volume.find()).as("aucun VOLUME dans le Dockerfile").isTrue();
        assertThat(volume.group(1)).isEqualTo("/var/lib/kex");
    }

    /**
     * Le nom du {@code ENV} est traduit par {@code SystemEnvironmentPropertySource} — la
     * traduction réelle de Spring, pas une règle réécrite ici : c'est elle qui décide qu'un
     * {@code _} devient tantôt un point, tantôt un tiret.
     */
    @Test
    void les_variables_du_dockerfile_alimentent_les_proprietes_que_le_code_lit() {
        assertThat(environment.getProperty("kex.agent.memory.storage-directory"))
                .isEqualTo("/var/lib/kex/memory");
        // Côté MCP la propriété est liée, pas seulement lue : c'est le type du code qui répond.
        McpRuntimeProperties mcp = Binder.get(environment)
                .bind("kex.mcp.runtime", McpRuntimeProperties.class).get();
        assertThat(mcp.storagePath()).isEqualTo("/var/lib/kex/mcp-servers.enc");
    }

    @Test
    void chaque_chemin_declare_est_absolu_et_sous_le_volume() {
        Map<String, String> declared = envPairs();

        assertThat(declared).isNotEmpty();
        assertThat(declared.values()).allSatisfy(value ->
                assertThat(Path.of(value)).isAbsolute().startsWithRaw(Path.of("/var/lib/kex")));
    }

    private StandardEnvironment environment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("dockerfile", Map.copyOf(envPairs())));
        return environment;
    }

    private Map<String, String> envPairs() {
        Map<String, String> pairs = new LinkedHashMap<>();
        Matcher matcher = ENV.matcher(dockerfile);
        while (matcher.find()) {
            pairs.put(matcher.group(1), matcher.group(2));
        }
        return pairs;
    }

    private static String read() {
        try {
            return Files.readString(Path.of("Dockerfile"));
        }
        catch (IOException ex) {
            throw new IllegalStateException("Dockerfile illisible depuis la racine du projet", ex);
        }
    }
}
