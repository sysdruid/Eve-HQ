# Importing static type data

From the project root, download and import CCP's latest JSONL archive:

```shell
./gradlew :app:run --args='import-static-data'
```

The command follows the latest JSONL link from [CCP's static data page](https://developers.eveonline.com/docs/services/static-data/).
On subsequent runs it sends the saved `ETag` and `Last-Modified` validators and skips the download and import
if the archive is unchanged. CCP notes that non-static files may be cached for five minutes. The download is
stored temporarily in the application data directory and removed afterwards.

To import a local JSONL ZIP instead, or to download a specific HTTP(S) URL, pass it as an argument:

```shell
./gradlew :app:run --args='import-static-data /path/to/sde.zip'
./gradlew :app:run --args='import-static-data https://example.com/sde.zip'
```

The archive must contain these files at its root:

- `categories.jsonl`
- `groups.jsonl`
- `types.jsonl`
- `blueprints.jsonl`
- `typeDogma.jsonl`

The command opens Eve-HQ's configured database and imports the English category, group, and type names, plus each blueprint's maximum production limit and its manufacturing and reaction recipes (build time, materials, products, required skills), and the few dogma attributes that shorten jobs (skills, implants and structures; see `TIME_BONUS_ATTRIBUTES` in `StaticDataImporter`). Material and product type ids are stored as given and are not checked against the type list, because the archive references a few legacy types it does not define. It does not start the GUI or use EVE SSO. Set `EVE_HQ_DATA_DIR` to select a non-default application data directory.

A successful import replaces the previous static dataset. Types marked unpublished are also imported so owned assets can still be named. If a download fails, a required archive entry or English name is missing, a record is malformed, or a parent relationship is invalid, the import fails and the previous dataset and download validators are retained. Importing a local file clears the validators, so the next remote import downloads afresh.

### Adding imported data

Start with `StaticDataImporter` and its tests. Add a migration for new tables or columns, import parent records
before dependent records, and keep replacement transactional and archive reading streamed. Require new ZIP
entries only when the feature needs them, and test both a successful import and failure rollback.