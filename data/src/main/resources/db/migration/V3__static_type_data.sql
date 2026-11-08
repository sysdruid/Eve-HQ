CREATE TABLE categories (
    id   BIGINT       NOT NULL,
    name VARCHAR(512) NOT NULL,
    CONSTRAINT pk_categories PRIMARY KEY (id)
);

CREATE TABLE groups (
    id          BIGINT       NOT NULL,
    category_id BIGINT       NOT NULL,
    name        VARCHAR(512) NOT NULL,
    CONSTRAINT pk_groups PRIMARY KEY (id),
    CONSTRAINT fk_groups_category FOREIGN KEY (category_id) REFERENCES categories (id)
);

CREATE TABLE types (
    id         BIGINT       NOT NULL,
    group_id   BIGINT       NOT NULL,
    name       VARCHAR(512) NOT NULL,
    published  BOOLEAN      NOT NULL,
    CONSTRAINT pk_types PRIMARY KEY (id),
    CONSTRAINT fk_types_group FOREIGN KEY (group_id) REFERENCES groups (id)
);