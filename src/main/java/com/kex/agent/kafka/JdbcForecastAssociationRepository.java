// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcForecastAssociationRepository implements ForecastAssociationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcForecastAssociationRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public Map<String, List<ForecastAssociation>> all() {
        Map<String, List<ForecastAssociation>> result = new LinkedHashMap<>();
        jdbc.query("SELECT process_id, associations FROM kex_forecast_association", rs -> {
            try {
                result.put(rs.getString(1), json.readValue(rs.getString(2), new TypeReference<List<ForecastAssociation>>() { }));
            } catch (java.io.IOException ex) { throw new IllegalStateException("Associations TimesFM invalides", ex); }
        });
        return Map.copyOf(result);
    }

    public void replace(String id, List<ForecastAssociation> associations) {
        try {
            String value = json.writeValueAsString(associations);
            if (jdbc.update("UPDATE kex_forecast_association SET associations = ? WHERE process_id = ?", value, id) == 0) {
                try {
                    jdbc.update("INSERT INTO kex_forecast_association (process_id, associations) VALUES (?, ?)", id, value);
                } catch (DuplicateKeyException ex) {
                    // A concurrent replica created this row; replacement still remains atomic per process.
                    jdbc.update("UPDATE kex_forecast_association SET associations = ? WHERE process_id = ?", value, id);
                }
            }
        } catch (java.io.IOException ex) { throw new IllegalStateException("Associations TimesFM invalides", ex); }
    }
}
