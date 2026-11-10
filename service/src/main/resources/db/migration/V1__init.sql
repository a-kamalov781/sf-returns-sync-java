CREATE TABLE processed_returns (
    external_id  VARCHAR(255) PRIMARY KEY,
    event_id     VARCHAR(64),
    status       VARCHAR(32)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE replay_state (
    topic      VARCHAR(255) PRIMARY KEY,
    replay_id  BYTEA        NOT NULL,          -- replay ID is opaque: stored as bytes, never converted
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE dead_letters (
    id          BIGSERIAL    PRIMARY KEY,
    external_id VARCHAR(255),
    payload     TEXT,
    reason      TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
