// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

/**
 * Une valeur qui peut légitimement ne pas avoir été mesurée. Miroir du contrat {@code Measured} de
 * Kafka SQL Explorer, et la raison d'être de ce paquet tient dans cette distinction : un lag à
 * {@code 0} affirme « rattrapé », une mesure absente n'affirme rien. Les confondre fait lire un
 * consumer à l'arrêt comme un consumer à jour.
 *
 * @param reason pourquoi la mesure manque ; toujours renseigné quand {@code measured} est faux
 */
public record MeasuredValue(Object value, boolean measured, String reason) {

    public static MeasuredValue unmeasured(String why) {
        return new MeasuredValue(null, false, why);
    }

    /** Lit la valeur telle qu'elle arrive. Une forme inattendue devient « non mesurée », pas zéro. */
    public static MeasuredValue from(Object candidate) {
        if (!(candidate instanceof java.util.Map<?, ?> map)) {
            return unmeasured("aucune mesure rendue");
        }
        boolean measured = map.get("measured") instanceof Boolean flag && flag;
        Object value = map.get("value");
        if (!measured || value == null) {
            Object why = map.get("reason");
            return unmeasured(why instanceof String text && !text.isBlank() ? text : "mesure absente, sans motif");
        }
        return new MeasuredValue(value, true, null);
    }
}
