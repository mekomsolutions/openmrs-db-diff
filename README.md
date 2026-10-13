# openmrs-data-sync

Finds patient data that exists in one OpenMRS database but is missing from another, reports it, and copies it
across, one patient at a time.

- **source**: the database with the extra data
- **target**: the source of truth that receives the missing rows

Every table is in scope except those listed in `config/excluded-tables.txt` (metadata, logs, Liquibase).

## How it works

The tool takes a list of patients (person uuids) and runs each one through the same steps. A patient that fails
is reported and nothing is written for it; the run carries on with the next one.

1. **Collect.** Starting from the patient's `person` row, follow every foreign key that points back at it, level
   by level: names, addresses, the `patient` row, identifiers, visits, encounters, obs, orders, programs, and so
   on. `users` and `provider` rows are never part of a patient's record, since they record who entered the data.
2. **Compare.** Rows are matched by `uuid`. Tables without a uuid column are matched by their primary key with
   foreign keys translated to the uuid of the row they point at (for example `patient` matches through
   `person.uuid`, `drug_order` through `orders.uuid`, `user_role` through `users.uuid` + role).
3. **Plan.** A missing row can be copied when every foreign key it holds points at a row that is already in the
   target (matched by uuid) or is copied with it. Rows the patient's data points at that the target lacks, such
   as the user who registered the patient, are pulled in from the source and copied too, together with the rows
   that extend them (`user_role` for a user, `patient` for a person). Anything else is *blocked* with a reason,
   for example an obs on a concept the target does not have. Rows that depend on a blocked row are blocked too.
4. **Sync.** Rows are inserted parents first. Auto-increment ids are assigned by the target, and every foreign
   key (person_id, encounter_id, concept_id, creator, obs_group_id, ...) is rewritten to the target's id for the
   same uuid. Foreign-key cycles (person ↔ users) and obs groups are handled with a temporary value that is fixed
   once the parent row exists. Each patient is one transaction: any error rolls back that patient only.

## Code map

| Class | Role |
| --- | --- |
| `Main`, `Options` | Command line |
| `SyncRun` | Runs every selected patient through `PatientSyncer`, keeps going on failure, writes the report |
| `PatientList` | Patients to process: from `--patient`, `--patients-file`, or every patient in the source |
| `PatientSyncer` | One patient: collect, compare, plan, sync |
| `PatientRecordCollector` | Reads a patient's rows from the source, following `TableLinks` |
| `TableLinks` | Which foreign keys make a row part of a patient's record, and which rows extend another |
| `SyncPlanner`, `SyncPlan`, `TableOrder` | What can be copied, what is blocked and why, and in which table order |
| `SyncExecutor` | Inserts a plan in one transaction, remapping ids; rolls back on dry run or error |
| `IdentityResolver` | Matches rows across the two databases and caches the lookups |
| `ReportWriter`, `CsvWriter` | The CSV report, written patient by patient |
| `Databases`, `Schema`, `SyncScope`, `SyncConfig`, `SourceReader`, `Db`, `Log` | Plumbing |

## Build

Requires Java 17+ and Maven.

```bash
mvn clean package
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

# 2. Dry run: performs every patient's inserts inside a transaction, then rolls back
java -jar target/openmrs-data-sync.jar sync

# 3. Back up the target first, then apply (asks you to type APPLY)
docker exec <container name> sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction openmrs' > backup.sql
java -jar target/openmrs-data-sync.jar sync --apply

# 4. Confirm: only blocked rows should remain
java -jar target/openmrs-data-sync.jar report
```

### Choosing patients

Without patient options, every patient in the source is processed, in `patient_id` order. To process only some:

```bash
# One or more patients (person uuids), comma-separated or repeated
java -jar target/openmrs-data-sync.jar sync --patient 0b2e...,7f41... --patient 9c3d...

# A file with one uuid per line; blank lines and anything after # are ignored
java -jar target/openmrs-data-sync.jar sync --patients-file patients.txt
```

Both options can be combined. A good way to start is a report on all patients, then a dry run and apply on a
handful, then the rest.

Other options: `--config path/to/file.properties` points at another config file, and `--yes` skips the APPLY
prompt. The exit code is 0 when every patient went through, 1 when at least one failed, and 2 for bad arguments.

### Output

Each run prints one line per patient and writes a timestamped folder under `output/`:

| File | Contents |
| --- | --- |
| `patients.csv` | Per patient: status, rows in the source, missing from the target, syncable, blocked, inserted, error |
| `summary.csv` | Per table totals over all patients |
| `missing/<table>.csv` | Every missing row exactly as it is in the source, with its patient and whether it can be synced |
| `blocked.csv` | Missing rows that cannot be synced, and why |

Patient statuses:

| Status | Meaning |
| --- | --- |
| `IN_SYNC` | Nothing is missing from the target |
| `MISSING` | Rows are missing (report mode) |
| `DRY_RUN_OK` | The dry run inserted the patient's rows without errors and rolled back |
| `SYNCED` | The patient's rows were inserted and committed |
| `BLOCKED` | Rows are missing but none can be copied; see `blocked.csv` |
| `NOT_FOUND` | The source has no patient with this uuid |
| `FAILED` | Something went wrong; nothing was written for this patient. The error is in `patients.csv` |

In a dry run, a row needed by several patients (a user who registered all of them, for example) is counted for
each of them, since every patient's inserts are rolled back before the next one starts.

## After applying

OpenMRS caches data and keeps a search index, and neither sees rows written straight to the database. After a
sync, restart OpenMRS (or clear its cache) and rebuild the search index from **Administration → Search Index**,
otherwise new patients will not show up in patient search.

## Things to know

- Only **missing** rows are copied. Rows that exist in both databases but differ are not compared or updated.
- Only data reachable from a patient is compared. Rows that belong to no patient (a user who never touched a
  selected patient's data, for example) are not reported.
- Metadata in excluded tables (concepts, locations, encounter types, identifier types, ...) must already exist in
  the target with the same uuid. Rows that need metadata the target lacks end up in `blocked.csv`.
- `users` and `provider` are not excluded, so users missing from the target are copied along with the first
  patient whose data needs them.
- Rows linking two patients (a `relationship`, for example) belong to both. The other person is copied along if
  the target lacks them, but only their person and patient rows; the rest of their record comes with their own
  turn.
- If both databases generated patient identifiers from the same idgen sequence, copied identifiers may later
  collide with ones the target generates. Check `missing/patient_identifier.csv` before applying.
- If the connection to either database is lost, the run stops instead of failing every remaining patient. The
  report covers the patients processed so far; patients already applied stay applied, so it is safe to re-run.
- Rows with a NULL uuid are ignored.

## Self-test

`scripts/local-test.sh` starts two throwaway MySQL 5.7 containers, loads a small OpenMRS-shaped schema with
tricky cases (person ↔ users cycle, obs groups, drug_order, user_role, an obs on a concept the target does not
have, a patient whose copy fails on a duplicate username), runs report, dry run and apply, and checks the
remapped ids, that a failing patient leaves nothing behind and does not stop the others, and that a second apply
inserts nothing.

```bash
./scripts/local-test.sh
```
