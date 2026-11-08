create table static_data_import (
    source varchar(2048) primary key,
    etag varchar(1024),
    last_modified varchar(1024)
);