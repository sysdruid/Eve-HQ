create table character_asset_snapshots (
    character_id bigint primary key references characters(id) on delete cascade,
    updated_at timestamp with time zone not null
);

create table character_asset_snapshot_items (
    character_id bigint not null references character_asset_snapshots(character_id) on delete cascade,
    item_id bigint not null,
    type_id bigint not null,
    type_name varchar(255),
    custom_name varchar(255),
    quantity integer not null,
    location_id bigint not null,
    location_name varchar(255),
    constraint pk_character_asset_snapshot_items primary key (character_id, item_id)
);

create table character_asset_pages (
    character_id bigint not null references characters(id) on delete cascade,
    page integer not null,
    body clob not null,
    etag varchar(1024),
    expires_at timestamp with time zone,
    page_count integer not null,
    constraint pk_character_asset_pages primary key (character_id, page)
);
