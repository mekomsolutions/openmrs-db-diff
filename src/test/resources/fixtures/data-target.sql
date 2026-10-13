-- Target (source of truth). Rows are inserted in a different order from the source, so ids differ.
SET FOREIGN_KEY_CHECKS = 0;
INSERT INTO person VALUES (1, 'M', NULL, 1, '2020-01-01 00:00:00', 0, 'person-admin');
INSERT INTO users VALUES (1, 'admin', 'admin', 1, 1, '2020-01-01 00:00:00', 0, 'user-admin');
INSERT INTO person VALUES (2, 'F', '1990-05-05', 1, '2021-01-01 00:00:00', 0, 'person-only-in-target');
INSERT INTO person VALUES (3, 'F', '1985-03-03', 1, '2021-02-02 10:00:00', 0, 'person-p1');
INSERT INTO patient VALUES (3, 1, '2021-02-02 10:00:00', 0);
INSERT INTO person_name VALUES (1, 3, 'Achieng', 'Otieno', 1, '2021-02-02 10:00:00', 'name-p1');
INSERT INTO concept VALUES (1, 'VITALS', 'concept-vitals'), (2, 'WEIGHT', 'concept-weight');
INSERT INTO location VALUES (1, 'Clinic', 'location-clinic');
INSERT INTO encounter_type VALUES (1, 'Visit', 'etype-visit');
INSERT INTO role VALUES ('Provider', 'role-provider');
INSERT INTO encounter VALUES (1, 1, 3, 1, '2021-02-02 10:00:00', 1, '2021-02-02 10:00:00', 'enc-p1');
INSERT INTO obs VALUES (1, 3, 2, 1, NULL, '2021-02-02 10:00:00', 70, NULL, 1, '2021-02-02 10:00:00', 'obs-p1-weight');
SET FOREIGN_KEY_CHECKS = 1;
