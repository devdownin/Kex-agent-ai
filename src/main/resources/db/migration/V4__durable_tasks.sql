CREATE TABLE IF NOT EXISTS kex_task (
    id VARCHAR(36) PRIMARY KEY,
    owner VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS kex_task_owner_created ON kex_task(owner, created_at);
