#!/usr/bin/env bash
# End-to-end self-test on two throwaway MySQL containers (never touches h4 or h9).
# Loads a small OpenMRS-shaped schema, runs report, dry run and apply, then checks the remapped ids.
set -euo pipefail
cd "$(dirname "$0")/.."

SRC=data-sync-test-source
TGT=data-sync-test-target
F=src/test/resources/fixtures
WORK=$(mktemp -d)

cleanup() { docker rm -f "$SRC" "$TGT" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

mvn -q -B package -DskipTests
docker rm -f "$SRC" "$TGT" >/dev/null 2>&1 || true
docker run -d --name "$SRC" -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=openmrs -p 33071:3306 mysql:5.7 >/dev/null
docker run -d --name "$TGT" -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=openmrs -p 33081:3306 mysql:5.7 >/dev/null

echo "Waiting for MySQL..."
for c in "$SRC" "$TGT"; do
  until docker exec "$c" mysql -uroot -proot -e "SELECT 1" openmrs >/dev/null 2>&1; do sleep 2; done
done
for c in "$SRC" "$TGT"; do docker exec -i "$c" mysql -uroot -proot openmrs < "$F/schema.sql" 2>/dev/null; done
docker exec -i "$SRC" mysql -uroot -proot openmrs < "$F/data-source.sql" 2>/dev/null
docker exec -i "$TGT" mysql -uroot -proot openmrs < "$F/data-target.sql" 2>/dev/null

cp "$F/excluded-tables.txt" "$WORK/"
cat > "$WORK/sync.properties" <<PROPS
source.url=jdbc:mysql://127.0.0.1:3314/openmrs?useSSL=false&allowPublicKeyRetrieval=true
source.user=openmrs
source.password=password
target.url=jdbc:mysql://127.0.0.1:3310/openmrs?useSSL=false&allowPublicKeyRetrieval=true
target.user=openmrs
target.password=password
excludedTablesFile=excluded-tables.txt
outputDir=$WORK/output
PROPS

JAR="java -jar target/openmrs-data-sync.jar"
$JAR report --config "$WORK/sync.properties"
$JAR sync --config "$WORK/sync.properties"
$JAR sync --apply --yes --config "$WORK/sync.properties"

q() { docker exec "$TGT" mysql -uroot -proot -N openmrs -e "$1" 2>/dev/null; }
check() { if [ "$(q "$2")" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1: got '$(q "$2")', expected '$3'"; FAILED=1; fi; }
FAILED=0
check "user created by itself" "SELECT c.uuid FROM users u JOIN users c ON c.user_id=u.creator WHERE u.uuid='user-nurse'" "user-nurse"
check "person created by the new user" "SELECT u.uuid FROM person p JOIN users u ON u.user_id=p.creator WHERE p.uuid='person-p2'" "user-nurse"
check "patient id follows person id" "SELECT p.uuid FROM patient pa JOIN person p ON p.person_id=pa.patient_id WHERE p.uuid='person-p2'" "person-p2"
check "obs concept remapped" "SELECT c.uuid FROM obs o JOIN concept c ON c.concept_id=o.concept_id WHERE o.uuid='obs-p2-weight-child'" "concept-weight"
check "obs group remapped" "SELECT g.uuid FROM obs o JOIN obs g ON g.obs_id=o.obs_group_id WHERE o.uuid='obs-p2-weight-child'" "obs-p2-vitals-group"
check "new obs in existing encounter" "SELECT e.uuid FROM obs o JOIN encounter e ON e.encounter_id=o.encounter_id WHERE o.uuid='obs-p1-weight-2'" "enc-p1"
check "drug_order follows orders" "SELECT o.uuid FROM drug_order d JOIN orders o ON o.order_id=d.order_id" "order-p2"
check "user_role copied" "SELECT u.uuid FROM user_role r JOIN users u ON u.user_id=r.user_id" "user-nurse"
check "obs on unknown concept not copied" "SELECT COUNT(*) FROM obs WHERE uuid IN ('obs-p1-height','obs-p1-child-of-height')" "0"
exit $FAILED
