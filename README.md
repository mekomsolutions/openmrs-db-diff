# openmrs-db-diff

Finds patient data that exists in one OpenMRS database but is missing from another, reports it, and copies it
across, one patient at a time.

- **source**: the database with the extra data
- **target**: the source of truth that receives the missing rows

Every table is in scope except those listed in `config/excluded-tables.txt` (metadata, logs, Liquibase).

## How it works

Before the patients, reconcile the metadata (see [Reconciling metadata](#reconciling-metadata)): rows can only be
copied when the concepts, locations, attribute types and other metadata they point at are in the target.

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
   that extend them (`user_role` for a user, `patient` for a person). Missing metadata is mapped or copied as the
   metadata decisions say. Anything else is *blocked* with a reason, for example an obs on a concept the target
   does not have. Rows that depend on a blocked row are blocked too.
4. **Sync.** Rows are inserted parents first. Auto-increment ids are assigned by the target, and every foreign
   key (person_id, encounter_id, concept_id, creator, obs_group_id, ...) is rewritten to the target's id for the
   same uuid. Foreign-key cycles (person ↔ users) and obs groups are handled with a temporary value that is fixed
   once the parent row exists. Each patient is one transaction: any error rolls back that patient only.

## Build

Requires Java 17+ and Maven.

```bash
mvn clean package
```

This produces `target/openmrs-db-diff.jar`.

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
# 0. Metadata the target lacks, and suggested decisions (read-only); see "Reconciling metadata"
java -jar target/openmrs-db-diff.jar metadata

# 1. Report only (read-only on both databases)
java -jar target/openmrs-db-diff.jar report

# 2. Dry run: performs every patient's inserts inside a transaction, then rolls back
java -jar target/openmrs-db-diff.jar sync

# 3. Back up the target first, then apply (asks you to type APPLY)
docker exec <container name> sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction openmrs' > backup.sql
java -jar target/openmrs-db-diff.jar sync --apply

# 4. Confirm: only blocked rows should remain
java -jar target/openmrs-db-diff.jar report
```

### Reconciling metadata

Metadata tables are the excluded tables listed in `config/metadata-tables.txt`: concepts, locations, encounter
types, attribute types and so on. Other excluded tables, such as Liquibase's, logs, event queues and audit tables,
are not metadata and are left alone. Edit the list to match your databases; every table in it must also be
excluded, and must have a uuid column in both databases. Metadata tables are never compared patient by patient. When a patient's rows point at metadata the target
does not have under the same uuid, those rows are blocked, and so is everything that depends on them. One missing
location can block every obs recorded there.

```bash
java -jar target/openmrs-db-diff.jar metadata
```

This compares every listed metadata table and writes two files to a new folder under `output/`:

| File | Contents |
| --- | --- |
| `metadata.csv` | Every metadata row the source has and the target lacks: name, how many synced rows use it and in which tables, which other missing metadata needs it (a location's parent), target rows with the same name, and the suggestion |
| `metadata-decisions.csv` | A suggested decision for each of those rows that patient data needs |

The decisions are:

| Action | When | What the sync does |
| --- | --- | --- |
| `MAP` | The target has it under another uuid (suggested when exactly one target row has the same name) | Rows that point at it are copied pointing at the target's row given in `target_uuid` |
| `COPY` | The target does not have it (suggested when no target row has the same name) | The row is copied along with the first patient that needs it |
| `SKIP` | Leave it alone (suggested when several target rows have the same name, so a person has to choose) | Rows that need it stay blocked |

Review the file, change any decision you disagree with (for example turn a `SKIP` into a `MAP` by filling in the
`target_uuid` you want), and save it as `output/20261022-234928/metadata-decisions.csv`, next to `sync.properties` (another path
can be set with `metadataDecisionsFile` in `sync.properties`). From then on `report` and `sync` follow it; a dry
run shows the effect before anything is written. Metadata that is not in the file is treated as `SKIP`, and only
rows of listed metadata tables are ever mapped or copied.

Names are matched exactly (case-insensitively on MySQL): the `name` column, a primary key that is the name itself
(`role`, `privilege`), or a concept's fully specified name.
A copied row only brings its own columns: a copied location does not bring its tags or attributes.

### Choosing patients

Without patient options, every patient in the source is processed, in `patient_id` order. To process only some:

```bash
# One or more patients (person uuids), comma-separated or repeated
java -jar target/openmrs-db-diff.jar sync --patient 0b2e...,7f41... --patient 9c3d...

# A file with one uuid per line; blank lines and anything after # are ignored
java -jar target/openmrs-db-diff.jar sync --patients-file patients.txt
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

## Fixing historical times (timezone)

Sometimes part of a database's history was stored in a different time zone convention from the rest. For
example, a server once wrote local wall-clock time and a later one writes UTC, or the other way round, and after a
migration or merge the older rows read a few hours off. The `timezone` command converts the datetimes of those
rows, patient by patient, on one database: the target by default, or the source with `--db source`.

```bash
cp config/timezone-cutoffs.csv.example config/timezone-cutoffs.csv   # set your locations and windows

# Dry run (read-only): lists every value it would change
java -jar target/openmrs-db-diff.jar timezone --zone America/New_York --to utc \
    --cutoffs config/timezone-cutoffs.csv --patient <uuid>

# Back up, then apply (asks you to type APPLY)
docker exec <container name> sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction openmrs' > backup.sql
java -jar target/openmrs-db-diff.jar timezone --zone America/New_York --to utc \
    --cutoffs config/timezone-cutoffs.csv --patient <uuid> --apply
```

**Direction.** `--zone` is the local time zone. `--to utc` treats the old values as local wall-clock time and
turns them into UTC. `--to local` does the reverse, treating them as UTC and turning them into local time. Daylight
saving time comes from the Java zone rules, so MySQL's time zone tables are not needed. In the hour skipped when
clocks go forward, a local time is read as if the clock had not moved yet. In the hour that repeats, the first
occurrence is used.

**Which rows.** A row is converted when its `date_created` falls in a window: from (inclusive) to before
(exclusive), compared with the value as stored. When the convention changed on different dates at different
locations, `--cutoffs` gives each location its own window (CSV `location,from,before`, the location by name, uuid
or id). A patient gets the window of the location their encounters, visits and identifiers are at. Patients seen
at several locations are skipped, and so are patients at an unlisted location. Patients seen at no location get
the `*` row, or `--from` and `--before` when there is no such row. With a single window for everyone, give only
`--from` and `--before`. Start the window after any bulk import whose times already follow the new convention.

**Which values.** In a row inside the window, every DATETIME column is converted. These are left alone:

- `date_changed`, `date_voided` and `date_retired` values from after the window, which already follow the new convention
- DATE, TIME and TIMESTAMP columns, NULLs, and the `1970-01-01 00:00:00` placeholder
- columns given with `--skip-columns table.column,...`. Use this for columns that were written without a time
  zone. Date-only answers kept at midnight in `obs.value_datetime` are a common case.

Before choosing the window and the skipped columns, compare the hour-of-day spread of each column before and
after the suspected change. Clinic hours that move by the zone's offset show which rows and columns need
converting.

The same rows are in scope as for `sync`: everything reachable from the patient except excluded tables, `users`
and `provider`.

**Output.** Each run writes `output/<time>-timezone/timezone-patients.csv` (status and counts per patient) and
`timezone-changes.csv` (table, row key, uuid, column, old value and new value for every value converted, or that
would be). The statuses are `WOULD_SHIFT`, `SHIFTED`, `NOTHING_TO_SHIFT`, `SKIPPED`, `NOT_FOUND` and `FAILED`.

**Safety.** Each patient is one transaction. An update only matches the row if it still holds the values that were
read. Every converted row goes into a `dbdiff_timezone_shift` table, created on the first apply, in the same
transaction, with its old and new values. A row is never converted twice: not on a second run, and not when two
patients share it. That table is also how to undo a run. Restart OpenMRS afterwards so its cache picks up the new
values.

## Things to know

- Only **missing** rows are copied. Rows that exist in both databases but differ are not compared or updated.
- Only data reachable from a patient is compared. Rows that belong to no patient (a user who never touched a
  selected patient's data, for example) are not reported.
- Metadata in excluded tables (concepts, locations, encounter types, identifier types, ...) must be in the target
  with the same uuid, or be mapped or copied by the metadata decisions. Rows that need other metadata the target
  lacks end up in `blocked.csv`.
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
