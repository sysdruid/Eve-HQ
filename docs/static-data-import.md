# Importing static type data

Download the CCP Static Data Export in JSONL ZIP format. The archive must contain these files at its root:

- `categories.jsonl`
- `groups.jsonl`
- `types.jsonl`

From the project root, run:

```shell
./gradlew :app:run --args='import-static-data /path/to/sde.zip'
```

The command opens Eve-HQ's configured database and imports the English category, group, and type names. It does not start the GUI or use EVE SSO. Set `EVE_HQ_DATA_DIR` to select a non-default application data directory.

A successful import replaces the previous static dataset. Types marked unpublished are also imported so owned assets can still be named. If a required archive entry or English name is missing, a record is malformed, or a parent relationship is invalid, the import fails and the previous dataset is retained.

### Adding imported data

Start with `StaticDataImporter` and its tests. Add a migration for new tables or columns, import parent records
before dependent records, and keep replacement transactional and archive reading streamed. Require new ZIP
entries only when the feature needs them, and test both a successful import and failure rollback.