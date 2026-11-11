create table stockpiles (
    type_id bigint primary key,
    name varchar(255) not null,
    required bigint not null,
    preferred bigint,
    overflow bigint
);
