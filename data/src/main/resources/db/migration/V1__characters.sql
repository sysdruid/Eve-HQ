-- Plain, portable SQL only (no H2-only features), so other databases stay an option.
CREATE TABLE characters (
    id               BIGINT                   NOT NULL,
    name             VARCHAR(100)             NOT NULL,
    access_token     VARCHAR(4096)            NOT NULL,
    refresh_token    VARCHAR(512)             NOT NULL,
    token_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status           VARCHAR(16)              NOT NULL,
    invalid_reason   VARCHAR(500),
    CONSTRAINT pk_characters PRIMARY KEY (id),
    CONSTRAINT chk_characters_status CHECK (status IN ('VALID', 'INVALID'))
);
