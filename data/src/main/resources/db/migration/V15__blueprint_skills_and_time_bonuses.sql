-- The skills a blueprint requires for an activity: skills that name a per-level time bonus in type_time_bonuses (the
-- engineering skills) shorten the job by that much per level the character has.
-- Skill type ids are deliberately not foreign keys, as for materials and products.
create table blueprint_skills (
    blueprint_type_id bigint not null,
    activity varchar(32) not null,
    skill_type_id bigint not null,
    level integer not null,
    constraint pk_blueprint_skills primary key (blueprint_type_id, activity, skill_type_id),
    constraint fk_blueprint_skills_activity foreign key (blueprint_type_id, activity)
        references blueprint_activities (blueprint_type_id, activity)
);

-- The few dogma attributes that shorten jobs (for example 440 manufacturingTimeBonus of Industry and the industry
-- implants, 1982 manufactureTimePerLevel of the engineering skills, 2602 strEngTimeBonus of engineering structures),
-- kept as the archive gives them, per type.
create table type_time_bonuses (
    type_id bigint not null,
    attribute_id integer not null,
    amount double precision not null,
    constraint pk_type_time_bonuses primary key (type_id, attribute_id)
);

-- Forget the saved ETag/Last-Modified so the next import downloads the archive again and fills the new tables.
delete from static_data_import;
