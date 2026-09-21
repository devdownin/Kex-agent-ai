-- Schéma des tables propres à l'agent, tel qu'il existait déjà en production sous forme de
-- `CREATE TABLE IF NOT EXISTS` posés à la construction de chaque dépôt, avant que ces
-- constructions ne soient reprises dans cette migration.
--
-- `IF NOT EXISTS` est gardé ici, exceptionnellement pour cette seule migration : elle doit
-- pouvoir s'exécuter aussi bien sur une base neuve (elle crée tout) que sur une base qui tourne
-- déjà sous une version antérieure (elle ne crée que ce qui manque, sans échouer sur ce qui
-- existe). `spring.flyway.baseline-version: 0` place le curseur de Flyway avant cette version,
-- pour qu'elle s'exécute réellement dans les deux cas plutôt que d'être sautée par une base non
-- vide. Une migration future n'a pas cette contrainte : Flyway garantit alors lui-même qu'elle ne
-- s'exécute qu'une fois, et `IF NOT EXISTS` n'y aurait plus sa place.
--
-- La mémoire de conversation (tables de Spring AI) n'est pas ici : son schéma reste posé par
-- `spring.ai.chat.memory.repository.jdbc.initialize-schema`, propriété d'un autre composant que
-- cette migration ne doit pas dupliquer.

CREATE TABLE IF NOT EXISTS kex_supervision_audit (
  id VARCHAR(64) PRIMARY KEY,
  occurred_at TIMESTAMP NOT NULL,
  actor VARCHAR(255),
  action VARCHAR(255),
  process_id VARCHAR(255),
  decision_id VARCHAR(64),
  reason VARCHAR(2000),
  policy_version VARCHAR(64),
  result VARCHAR(2000),
  correlation_id VARCHAR(64),
  trace_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS kex_agent_memory (
  id VARCHAR(64) PRIMARY KEY,
  content VARCHAR(2000) NOT NULL,
  conversation_id VARCHAR(64),
  created_at TIMESTAMP NOT NULL,
  superseded_by VARCHAR(64),
  owner VARCHAR(255) NOT NULL DEFAULT 'kex-internal'
);
ALTER TABLE kex_agent_memory ADD COLUMN IF NOT EXISTS owner VARCHAR(255) NOT NULL DEFAULT 'kex-internal';

CREATE TABLE IF NOT EXISTS kex_agent_learning (
  id VARCHAR(64) PRIMARY KEY,
  owner VARCHAR(255) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  title VARCHAR(200) NOT NULL,
  markdown TEXT NOT NULL,
  evidence TEXT NOT NULL,
  conversation_id VARCHAR(255),
  created_at TIMESTAMP NOT NULL,
  status VARCHAR(16) NOT NULL,
  reviewed_by VARCHAR(255),
  reviewed_at TIMESTAMP,
  review_reason VARCHAR(2000)
);
CREATE INDEX IF NOT EXISTS kex_learning_owner_kind ON kex_agent_learning(owner, kind, created_at);

CREATE TABLE IF NOT EXISTS kex_supervision_decision (
  id VARCHAR(64) PRIMARY KEY,
  decided_at TIMESTAMP NOT NULL,
  status VARCHAR(32) NOT NULL,
  payload TEXT NOT NULL,
  claimed_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS kex_supervision_maintenance (
  process_id VARCHAR(255) PRIMARY KEY,
  process_name VARCHAR(255),
  until_at TIMESTAMP NOT NULL,
  reason VARCHAR(2000),
  declared_by VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS kex_supervision_flag (
  name VARCHAR(64) PRIMARY KEY,
  enabled BOOLEAN NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS kex_supervision_lock (
  name VARCHAR(64) PRIMARY KEY,
  locked_until TIMESTAMP NOT NULL,
  locked_by VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS kex_rate_limit (
  principal VARCHAR(255) PRIMARY KEY,
  tokens BIGINT NOT NULL,
  refilled_at TIMESTAMP NOT NULL,
  revision BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS kex_automation (
  id VARCHAR(36) PRIMARY KEY,
  owner VARCHAR(160) NOT NULL,
  name VARCHAR(160) NOT NULL,
  prompt TEXT NOT NULL,
  cron VARCHAR(120) NOT NULL,
  zone VARCHAR(80) NOT NULL,
  enabled BOOLEAN NOT NULL,
  next_run TIMESTAMP NOT NULL,
  last_run TIMESTAMP,
  status VARCHAR(32) NOT NULL,
  result TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  locked_until TIMESTAMP NOT NULL,
  claim_id VARCHAR(36) NOT NULL,
  revision BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS kex_automation_due ON kex_automation (enabled, next_run);

CREATE TABLE IF NOT EXISTS kex_automation_audit (
  id VARCHAR(36) PRIMARY KEY,
  automation_id VARCHAR(36) NOT NULL,
  owner VARCHAR(160) NOT NULL,
  at TIMESTAMP NOT NULL,
  actor VARCHAR(160) NOT NULL,
  action VARCHAR(32) NOT NULL,
  result TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS kex_automation_audit_owner ON kex_automation_audit (owner, at);
