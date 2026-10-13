-- Source (has extra data the target is missing).
SET FOREIGN_KEY_CHECKS = 0;
INSERT INTO person VALUES (1, 'M', NULL, 1, '2020-01-01 00:00:00', 0, 'person-admin');
INSERT INTO users VALUES (1, 'admin', 'admin', 1, 1, '2020-01-01 00:00:00', 0, 'user-admin');
INSERT INTO person VALUES (2, 'F', '1985-03-03', 1, '2021-02-02 10:00:00', 0, 'person-p1');
INSERT INTO patient VALUES (2, 1, '2021-02-02 10:00:00', 0);
INSERT INTO person_name VALUES (1, 2, 'Achieng', 'Otieno', 1, '2021-02-02 10:00:00', 'name-p1');
INSERT INTO concept VALUES (1, 'WEIGHT', 'concept-weight'), (2, 'VITALS', 'concept-vitals'), (3, 'HEIGHT', 'concept-height-source-only');
INSERT INTO location VALUES (1, 'Clinic', 'location-clinic');
INSERT INTO encounter_type VALUES (1, 'Visit', 'etype-visit');
INSERT INTO role VALUES ('Provider', 'role-provider');
INSERT INTO encounter VALUES (1, 1, 2, 1, '2021-02-02 10:00:00', 1, '2021-02-02 10:00:00', 'enc-p1');
INSERT INTO obs VALUES (1, 2, 1, 1, NULL, '2021-02-02 10:00:00', 70, NULL, 1, '2021-02-02 10:00:00', 'obs-p1-weight');

-- Only in the source --------------------------------------------------------------------------------
-- A new user whose person row was created by that same user (person <-> users cycle, users self reference).
INSERT INTO person VALUES (3, 'M', NULL, 2, '2022-01-01 08:00:00', 0, 'person-user2');
INSERT INTO users VALUES (2, 'nurse', 'nurse', 3, 2, '2022-01-01 08:00:00', 0, 'user-nurse');
INSERT INTO user_role VALUES (2, 'Provider');
-- A new patient registered by that user, with an encounter, an obs group whose child has a lower id than the group,
-- and a drug order.
INSERT INTO person VALUES (4, 'F', '2000-12-31', 2, '2022-03-03 09:15:00', 0, 'person-p2');
INSERT INTO patient VALUES (4, 2, '2022-03-03 09:15:00', 0);
INSERT INTO person_name VALUES (2, 4, 'Wanjiru', 'Kamau', 2, '2022-03-03 09:15:00', 'name-p2');
INSERT INTO encounter VALUES (2, 1, 4, 1, '2022-03-03 09:20:00', 2, '2022-03-03 09:20:00', 'enc-p2');
INSERT INTO obs VALUES (2, 4, 1, 2, 3, '2022-03-03 09:20:00', 60.5, NULL, 2, '2022-03-03 09:20:00', 'obs-p2-weight-child');
INSERT INTO obs VALUES (3, 4, 2, 2, NULL, '2022-03-03 09:20:00', NULL, NULL, 2, '2022-03-03 09:20:00', 'obs-p2-vitals-group');
INSERT INTO orders VALUES (1, 4, 1, 2, 2, '2022-03-03 09:25:00', 'order-p2');
INSERT INTO drug_order VALUES (1, 5.0);
-- A new obs for the existing patient, in the existing encounter.
INSERT INTO obs VALUES (4, 2, 1, 1, NULL, '2022-04-04 11:00:00', 72, 'follow-up, "rechecked"', 1, '2022-04-04 11:00:00', 'obs-p1-weight-2');
-- An obs on a concept the target does not have (blocked), and a child that depends on it (blocked too).
INSERT INTO obs VALUES (5, 2, 3, 1, NULL, '2022-05-05 12:00:00', 165, NULL, 1, '2022-05-05 12:00:00', 'obs-p1-height');
INSERT INTO obs VALUES (6, 2, 1, 1, 5, '2022-05-05 12:00:00', 71, NULL, 1, '2022-05-05 12:00:00', 'obs-p1-child-of-height');
-- A patient registered by a new user whose username is taken by a different user in the target: copying that
-- user fails, so this patient fails as a whole and must leave nothing behind, without stopping the others.
INSERT INTO person VALUES (5, 'F', NULL, 1, '2022-06-06 08:00:00', 0, 'person-clerk');
INSERT INTO users VALUES (3, 'clerk', 'clerk', 5, 1, '2022-06-06 08:00:00', 0, 'user-clerk');
INSERT INTO person VALUES (6, 'M', '1999-09-09', 3, '2022-06-06 09:00:00', 0, 'person-p3');
INSERT INTO patient VALUES (6, 3, '2022-06-06 09:00:00', 0);
INSERT INTO person_name VALUES (3, 6, 'Kibet', 'Rotich', 3, '2022-06-06 09:00:00', 'name-p3');
SET FOREIGN_KEY_CHECKS = 1;
