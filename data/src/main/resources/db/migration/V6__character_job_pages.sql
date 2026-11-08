create table character_job_pages (
    character_id bigint primary key references characters(id) on delete cascade,
    body clob not null,
    etag varchar(1024),
    expires_at timestamp with time zone
);
