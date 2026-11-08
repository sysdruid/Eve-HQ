create table blueprints (
    type_id bigint primary key references types(id),
    max_production_limit integer not null
);
