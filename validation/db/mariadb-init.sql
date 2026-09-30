-- Seed for the MariaDB backend of the GUI validation harness (validation/README.md).
-- The database `demo` and the user `demo` already exist: the mariadb Docker image creates them
-- from MARIADB_DATABASE and MARIADB_USER, and db/start-local.sh creates them for a native
-- server. This script creates the table, and it is safe to run more than once.
--
-- It also creates the database `zone1_dev2` for the schema-directive step (README "Different
-- schema per backend"; MariaDB calls a schema a database). Granting the demo user access to it
-- differs per path, so it is not here: db/mariadb-zone1-grants.sql does it for the Docker image
-- (which runs init scripts as root) and db/start-local.sh does it for a native server.
DROP TABLE IF EXISTS orders;
CREATE TABLE orders (
  id INT PRIMARY KEY,
  customer VARCHAR(32),
  amount DECIMAL(10,2)
);
INSERT INTO orders (id, customer, amount) VALUES (10, 'maria', 100.00), (11, 'db', 110.00);

CREATE DATABASE IF NOT EXISTS zone1_dev2;
DROP TABLE IF EXISTS zone1_dev2.orders;
CREATE TABLE zone1_dev2.orders (
  id INT PRIMARY KEY,
  customer VARCHAR(32),
  amount DECIMAL(10,2)
);
INSERT INTO zone1_dev2.orders (id, customer, amount) VALUES (10, 'maria', 100.00), (11, 'db', 110.00);
