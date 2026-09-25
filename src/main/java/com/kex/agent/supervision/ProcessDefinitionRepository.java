// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.List;

/** Déclarations ajoutées depuis la console, indépendantes de celles fournies au démarrage. */
interface ProcessDefinitionRepository {

    List<MonitoredProcess> all();

    /** Création atomique : un identifiant existant ne peut pas être remplacé par accident. */
    void create(MonitoredProcess process);
}
