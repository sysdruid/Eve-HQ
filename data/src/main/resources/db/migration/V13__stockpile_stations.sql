-- A stockpile is an item anywhere (station_id 0) or at one station or structure. 0 stands for "anywhere" because
-- a primary key cannot hold null; the repository maps it to and from null.
alter table stockpiles add column station_id bigint not null default 0;
alter table stockpiles add column station_name varchar(255);
alter table stockpiles drop primary key;
alter table stockpiles add primary key (type_id, station_id);
