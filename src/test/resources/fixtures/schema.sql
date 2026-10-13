-- A cut-down OpenMRS-shaped schema covering the cases the sync has to handle:
-- person/users foreign-key cycle, patient (no uuid, primary key = person id), obs groups (self reference),
-- drug_order (no uuid, primary key = orders id), user_role (no uuid, composite key) and excluded metadata.
SET FOREIGN_KEY_CHECKS = 0;
CREATE TABLE person
(
    person_id    INT AUTO_INCREMENT PRIMARY KEY,
    gender       VARCHAR(50),
    birthdate    DATE,
    creator      INT      NOT NULL,
    date_created DATETIME NOT NULL,
    voided       TINYINT(1) NOT NULL DEFAULT 0,
    uuid         CHAR(38) NOT NULL UNIQUE
) ENGINE=InnoDB;
CREATE TABLE users
(
    user_id      INT AUTO_INCREMENT PRIMARY KEY,
    system_id    VARCHAR(50) NOT NULL,
    username     VARCHAR(50) UNIQUE,
    person_id    INT         NOT NULL,
    creator      INT         NOT NULL,
    date_created DATETIME    NOT NULL,
    retired      TINYINT(1) NOT NULL DEFAULT 0,
    uuid         CHAR(38)    NOT NULL UNIQUE,
    FOREIGN KEY (person_id) REFERENCES person (person_id),
    FOREIGN KEY (creator) REFERENCES users (user_id)
) ENGINE=InnoDB;
ALTER TABLE person
    ADD FOREIGN KEY (creator) REFERENCES users (user_id);
CREATE TABLE patient
(
    patient_id   INT PRIMARY KEY,
    creator      INT      NOT NULL,
    date_created DATETIME NOT NULL,
    voided       TINYINT(1) NOT NULL DEFAULT 0,
    FOREIGN KEY (patient_id) REFERENCES person (person_id),
    FOREIGN KEY (creator) REFERENCES users (user_id)
) ENGINE=InnoDB;
CREATE TABLE person_name
(
    person_name_id INT AUTO_INCREMENT PRIMARY KEY,
    person_id      INT      NOT NULL,
    given_name     VARCHAR(50),
    family_name    VARCHAR(50),
    creator        INT      NOT NULL,
    date_created   DATETIME NOT NULL,
    uuid           CHAR(38) NOT NULL UNIQUE,
    FOREIGN KEY (person_id) REFERENCES person (person_id),
    FOREIGN KEY (creator) REFERENCES users (user_id)
) ENGINE=InnoDB;
CREATE TABLE concept
(
    concept_id INT AUTO_INCREMENT PRIMARY KEY,
    short_name VARCHAR(50),
    uuid       CHAR(38) NOT NULL UNIQUE
) ENGINE=InnoDB;
CREATE TABLE location
(
    location_id INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255),
    uuid        CHAR(38) NOT NULL UNIQUE
) ENGINE=InnoDB;
CREATE TABLE encounter_type
(
    encounter_type_id INT AUTO_INCREMENT PRIMARY KEY,
    name              VARCHAR(50),
    uuid              CHAR(38) NOT NULL UNIQUE
) ENGINE=InnoDB;
CREATE TABLE role
(
    role VARCHAR(50) PRIMARY KEY,
    uuid CHAR(38) NOT NULL UNIQUE
) ENGINE=InnoDB;
CREATE TABLE user_role
(
    user_id INT         NOT NULL,
    role    VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role),
    FOREIGN KEY (user_id) REFERENCES users (user_id),
    FOREIGN KEY (role) REFERENCES role (role)
) ENGINE=InnoDB;
CREATE TABLE encounter
(
    encounter_id       INT AUTO_INCREMENT PRIMARY KEY,
    encounter_type     INT      NOT NULL,
    patient_id         INT      NOT NULL,
    location_id        INT,
    encounter_datetime DATETIME NOT NULL,
    creator            INT      NOT NULL,
    date_created       DATETIME NOT NULL,
    uuid               CHAR(38) NOT NULL UNIQUE,
    FOREIGN KEY (encounter_type) REFERENCES encounter_type (encounter_type_id),
    FOREIGN KEY (patient_id) REFERENCES patient (patient_id),
    FOREIGN KEY (location_id) REFERENCES location (location_id),
    FOREIGN KEY (creator) REFERENCES users (user_id)
) ENGINE=InnoDB;
CREATE TABLE obs
(
    obs_id       INT AUTO_INCREMENT PRIMARY KEY,
    person_id    INT      NOT NULL,
    concept_id   INT      NOT NULL,
    encounter_id INT,
    obs_group_id INT,
    obs_datetime DATETIME NOT NULL,
    value_numeric DOUBLE,
    value_text   TEXT,
    creator      INT      NOT NULL,
    date_created DATETIME NOT NULL,
    uuid         CHAR(38) NOT NULL UNIQUE,
    FOREIGN KEY (person_id) REFERENCES person (person_id),
    FOREIGN KEY (concept_id) REFERENCES concept (concept_id),
    FOREIGN KEY (encounter_id) REFERENCES encounter (encounter_id),
    FOREIGN KEY (obs_group_id) REFERENCES obs (obs_id),
    FOREIGN KEY (creator) REFERENCES users (user_id)
) ENGINE=InnoDB;
CREATE TABLE orders
(
    order_id     INT AUTO_INCREMENT PRIMARY KEY,
    patient_id   INT      NOT NULL,
    concept_id   INT      NOT NULL,
    encounter_id INT      NOT NULL,
    creator      INT      NOT NULL,
    date_created DATETIME NOT NULL,
    uuid         CHAR(38) NOT NULL UNIQUE,
    FOREIGN KEY (patient_id) REFERENCES patient (patient_id),
    FOREIGN KEY (concept_id) REFERENCES concept (concept_id),
    FOREIGN KEY (encounter_id) REFERENCES encounter (encounter_id),
    FOREIGN KEY (creator) REFERENCES users (user_id)
) ENGINE=InnoDB;
CREATE TABLE drug_order
(
    order_id INT PRIMARY KEY,
    dose DOUBLE,
    FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB;
SET FOREIGN_KEY_CHECKS = 1;
