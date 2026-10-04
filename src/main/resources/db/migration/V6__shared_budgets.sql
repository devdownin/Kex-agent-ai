CREATE TABLE kex_budget_counter (
  scope VARCHAR(160) PRIMARY KEY,
  tokens BIGINT NOT NULL,
  cost_micros BIGINT NOT NULL
);
CREATE TABLE kex_budget_reservation (
  id VARCHAR(36) PRIMARY KEY,
  scopes VARCHAR(512) NOT NULL,
  tokens BIGINT NOT NULL,
  cost_micros BIGINT NOT NULL
);
