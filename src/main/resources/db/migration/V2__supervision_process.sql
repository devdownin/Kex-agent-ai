CREATE TABLE kex_supervision_process (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  description VARCHAR(1000),
  hint VARCHAR(2000) NOT NULL
);
