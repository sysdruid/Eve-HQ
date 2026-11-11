-- Recipe tables (V11) were added after some databases had already imported blueprints. Their saved ETag would make
-- the next import report "already up to date" and leave recipes empty, so forget it and refetch once.
delete from static_data_import;
