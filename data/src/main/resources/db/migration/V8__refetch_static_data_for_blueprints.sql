-- Static data imported before the blueprint table existed has no blueprints; forget the saved
-- ETag/Last-Modified so the next import downloads the archive again instead of reporting "up to date".
delete from static_data_import;
