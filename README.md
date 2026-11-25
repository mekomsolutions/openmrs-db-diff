# openmrs-db-diff

Finds patient data that exists in one OpenMRS database but is missing from another, reports it, and copies it
across, one patient at a time. It can also fix datetime(s) that were saved in the wrong time zone.

- **source**: the database with the extra data
- **target**: the database you trust, which receives the missing rows

Every table is compared except the ones in `config/excluded-tables.txt` (metadata, logs, Liquibase).

## Quick start

You need Java 17+ and Maven.

```bash
mvn clean package
cp config/sync.properties.example config/sync.properties   # fill in both databases
```

`docker port <container> 3306` tells you which host port a container uses for MySQL.

Then work through it step by step. Only `sync --apply` writes anything:

```bash
java -jar target/openmrs-db-diff.jar metadata       # 1. metadata the target lacks (read-only)
java -jar target/openmrs-db-diff.jar report         # 2. what's missing (read-only)
java -jar target/openmrs-db-diff.jar sync           # 3. dry run: insert, then roll back

# 4. back up the target, then apply (you'll be asked to type APPLY)
docker exec <container> sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction openmrs' > backup.sql
java -jar target/openmrs-db-diff.jar sync --apply

java -jar target/openmrs-db-diff.jar report         # 5. only blocked rows should be left
```

Afterwards, restart OpenMRS and rebuild the search index (**Administration → Search Index**). Otherwise the new
patients won't show up in search.

## How a patient is synced

1. **Collect** everything that hangs off the patient's `person` row: names, identifiers, visits, encounters, obs,
   orders, programs and so on. `users` and `provider` rows don't belong to any one patient.
2. **Compare** rows by `uuid`. Tables without a uuid are matched through their keys, so `patient` matches through
   `person.uuid`, for example. If the target has the row but attached to a different patient, visit or encounter,
   the patient is a **conflict** and nothing is copied. Legacy imports sometimes reused uuids, so this catches
   that.
3. **Plan** which missing rows can go in. A row is **blocked** if it points at something the target doesn't have,
   such as a concept, and so is anything that depends on it.
4. **Insert** parents first, letting the target hand out new ids and rewriting every foreign key to match. Each
   patient is one transaction, so a failure only rolls back that patient.

**Placeholders.** When a database merge didn't know a row's real parent yet, it pointed the row at a placeholder:
a person, visit, user and so on with uuid `[PHANTOM_UUID]`. The sync writes the real source row over the
placeholder, keeping its id. Set `placeholderUuid` in `sync.properties` if your merge used another marker, or leave
it empty to turn this off.

## Metadata first

Patient rows can only be copied if the concepts, locations, attribute types and other metadata they point at exist
in the target. `metadata` compares the tables listed in `config/metadata-tables.txt` and writes:

- `metadata.csv`: every metadata row the target lacks, and how many patient rows need it
- `metadata-decisions.csv`: a suggested decision for each

Review the decisions and save the file as `config/metadata-decisions.csv`. `report` and `sync` follow it from then
on:

| Decision | Use it when | What happens |
| --- | --- | --- |
| `MAP` | the target has it under another uuid | rows point at the target's row (`target_uuid`) |
| `COPY` | the target doesn't have it | it's copied with the first patient that needs it |
| `SKIP` | you're not sure yet | rows that need it stay blocked |

## Choosing patients

By default every patient in the source is processed. To process only some:

```bash
java -jar target/openmrs-db-diff.jar sync --patient <uuid>,<uuid>
java -jar target/openmrs-db-diff.jar sync --patients-file patients.txt   # one uuid per line, # for comments
```

A good rhythm is to report on everyone, then dry-run and apply a handful, then do the rest. `--config` points at
another config file and `--yes` skips the APPLY prompt.

## What you get

Each run writes a timestamped folder under `output/`:

- `patients.csv`: one line per patient with its status and counts
- `summary.csv`: totals per table
- `missing/<table>.csv`: every missing row, and whether it can be synced
- `blocked.csv`: rows that can't be synced, and why
- `conflicts.csv`: rows attached to a different parent in the target

Statuses: `IN_SYNC`, `MISSING` (report), `DRY_RUN_OK`, `SYNCED`, `BLOCKED`, `CONFLICT`, `NOT_FOUND`, `FAILED`.
A failed patient has nothing written, and the run moves on to the next one.

## Fixing time zones

Sometimes part of a database was saved in local time and the rest in UTC, so old rows read a few hours off. The
`timezone` command fixes them table by table. Like everything else, it's a dry run until you add `--apply`.

```bash
# Optional: find the key ranges of rows to leave alone, e.g. one site's own rows in a merged database (read-only)
java -jar target/openmrs-db-diff.jar timezone --suggest-site-keys --site-location "Main Clinic" --before 2024-06-01

# Dry run, check the output, then back up and add --apply
java -jar target/openmrs-db-diff.jar timezone --zone America/New_York --to utc \
    --from 2021-01-01 --before 2024-06-01 --table visit --exclude-keys site-keys-suggested.csv

# Changed your mind? Undo a run
java -jar target/openmrs-db-diff.jar timezone --undo --run-id <run id> --apply
```

What it does:

- It converts rows whose `date_created` falls in [`--from`, `--before`), all their DATETIME columns together. DST
  follows the zone rules.
- It leaves alone:
  - DATE columns
  - values on or after `--before`
  - `--exclude-keys` ranges
  - placeholder rows
  - rows it already converted
  - `obs.value_datetime` (change this with `--skip-columns`)
- Rows edited after `--before` are listed for review rather than converted (`--convert-edited` converts them).
  Rows without a `date_created` are listed too.
- `hour-histogram.csv` is the quickest sanity check: clinic hours should move by the zone's offset.
- Every change is logged in `dbdiff_timezone_shift`, which makes re-runs safe and `--undo` possible. Restart
  OpenMRS afterwards.

## Good to know

- Only **missing** rows are copied. Rows that exist in both databases but differ are left as they are.
- Rows that belong to no patient aren't compared.
- Users missing from the target are copied along with the first patient who needs them.
- If both databases generated identifiers from the same idgen sequence, check `missing/patient_identifier.csv` for
  future collisions before applying.
- If a connection drops, the run stops. Patients already applied stay applied, so it's safe to run again.
