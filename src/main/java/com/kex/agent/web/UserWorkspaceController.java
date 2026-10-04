// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** La console historique garde son URL ; l'espace utilisateur dispose d'une entrée distincte. */
@Controller
class UserWorkspaceController {
    @GetMapping({"/app", "/app/"})
    String workspace() {
        return "forward:/app/index.html";
    }
}
