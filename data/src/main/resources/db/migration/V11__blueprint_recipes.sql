-- Material and product type ids are deliberately not foreign keys: the SDE references a few types that
-- are not in its own type list (removed Standup rigs, the old Clones blueprint).
create table blueprint_activities (
    blueprint_type_id bigint not null references blueprints(type_id),
    activity varchar(32) not null,
    time_seconds integer not null,
    constraint pk_blueprint_activities primary key (blueprint_type_id, activity)
);

create table blueprint_materials (
    blueprint_type_id bigint not null,
    activity varchar(32) not null,
    type_id bigint not null,
    quantity integer not null,
    constraint pk_blueprint_materials primary key (blueprint_type_id, activity, type_id),
    constraint fk_blueprint_materials_activity foreign key (blueprint_type_id, activity)
        references blueprint_activities (blueprint_type_id, activity)
);

create table blueprint_products (
    blueprint_type_id bigint not null,
    activity varchar(32) not null,
    type_id bigint not null,
    quantity integer not null,
    constraint pk_blueprint_products primary key (blueprint_type_id, activity, type_id),
    constraint fk_blueprint_products_activity foreign key (blueprint_type_id, activity)
        references blueprint_activities (blueprint_type_id, activity)
);
