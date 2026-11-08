create table location_names (
    id bigint primary key,
    -- null: the lookup was refused or unknown (for example a structure we may not dock at)
    name varchar(255),
    checked_at timestamp with time zone not null
);
