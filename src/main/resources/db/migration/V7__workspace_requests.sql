CREATE TABLE kex_workspace_request (
    id VARCHAR(36) PRIMARY KEY,
    owner VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    payload TEXT NOT NULL
);
CREATE INDEX kex_workspace_owner_updated ON kex_workspace_request(owner, updated_at);
