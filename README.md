# openmrs-db-diff
A tool that finds patient data that exists in one OpenMRS database but is missing from another, reports it, and copies it across, one patient at a time.

- **source**: the database with the extra data
- **target**: the source of truth that receives the missing rows

Every table is compared except those listed in `config/excluded-tables.txt` (metadata, logs, Liquibase).

## How it works

1. **Compare.** Rows are matched by `uuid`. Tables without a uuid column are matched by their primary key with
   foreign keys translated to the uuid of the row they point at (for example `patient` matches through
   `person.uuid`, `drug_order` through `orders.uuid`, `user_role` through `users.uuid` + role).
2. **Plan.** A missing row can be copied when every foreign key it holds points at a row that is already in the
   target (matched by uuid) or is being copied in the same run. Anything else is *blocked* with a reason, for
   example an obs on a concept the target does not have. Rows that depend on a blocked row are blocked too.
3. **Sync.** Rows are inserted parents first. Auto-increment ids are assigned by the target, and every foreign
   key (person_id, encounter_id, concept_id, creator, obs_group_id, ...) is rewritten to the target's id for the
   same uuid. Foreign-key cycles (person ↔ users) and obs groups are handled with a temporary value that is fixed
   once the parent row exists. Everything runs in one transaction: any error rolls the whole sync back.

## Build

Requires Java 17+ and Maven.

```bash
mvn package
```

This produces `target/openmrs-data-sync.jar`.

## Configure

```bash
cp config/sync.properties.example config/sync.properties
```

Set the JDBC URLs, user and password of both databases. To find the host port each container publishes MySQL on:

```bash
docker port <container name> 3306
```

If the containers do not publish port 3306, either add a port mapping or run the jar from a container on the same
Docker network and use the container names as hosts (`jdbc:mysql://<container name>:3306/openmrs`).

## Run

```bash
# 1. Report only (read-only on both databases)
java -jar target/openmrs-data-sync.jar report

# 2. Dry run: performs every insert inside a transaction, then rolls back
java -jar target/openmrs-data-sync.jar sync

# 3. Back up the target first, then apply (asks you to type APPLY)
docker exec <container name> sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction openmrs' > backup.sql
java -jar target/openmrs-data-sync.jar sync --apply

# 4. Confirm: only blocked rows should remain
java -jar target/openmrs-data-sync.jar report
```

Use `--config path/to/file.properties` to point at another config file. Add `--yes` to skip the APPLY prompt.

Each run writes a timestamped folder under `output/`:

| File | Contents |
| --- | --- |
| `summary.csv` | Per table: rows in source, missing from target, syncable, blocked |
| `missing/<table>.csv` | Every missing row, exactly as it is in the source |
| `blocked.csv` | Missing rows that cannot be synced, and why |

## After applying

OpenMRS caches data and keeps a search index, and neither sees rows written straight to the database. After a
sync, restart OpenMRS (or clear its cache) and rebuild the search index from **Administration → Search Index**,
otherwise new patients will not show up in patient search.

## Things to know

- Only **missing** rows are copied. Rows that exist in both databases but differ are not compared or updated.
- Metadata in excluded tables (concepts, locations, encounter types, identifier types, ...) must already exist in
  the target with the same uuid. Rows that need metadata the target lacks end up in `blocked.csv`.
- `users` and `provider` are not excluded, so users missing from the target are copied along with the patients
  they created.
- If both databases generated patient identifiers from the same idgen sequence, copied identifiers may later
  collide with ones the target generates. Check `missing/patient_identifier.csv` before applying.
- Missing rows are held in memory during a run. For very large differences, give the JVM more memory, for example
  `java -Xmx4g -jar ...`.
- Rows with a NULL uuid are ignored.

## Self-test

`scripts/local-test.sh` starts two throwaway MySQL 5.7 containers, loads a small
OpenMRS-shaped schema with tricky cases (person ↔ users cycle, obs groups, drug_order, user_role, an obs on a
concept the target does not have), runs report, dry run and apply, and checks the remapped ids.

```bash
./scripts/local-test.sh
```
