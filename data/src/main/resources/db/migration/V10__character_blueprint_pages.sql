create table character_blueprint_pages (
    character_id bigint not null references characters(id) on delete cascade,
    page integer not null,
    body clob not null,
    etag varchar(1024),
    expires_at timestamp with time zone,
    page_count integer not null,
    constraint pk_character_blueprint_pages primary key (character_id, page)
);
