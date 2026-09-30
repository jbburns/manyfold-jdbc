-- Seed for the PostgreSQL backend of the GUI validation harness (validation/README.md).
-- The database `demo` and the user `demo` already exist: the postgres Docker image creates them
-- from POSTGRES_DB and POSTGRES_USER, and db/start-local.sh creates them for a native server.
-- This script only creates the tables, and it is safe to run more than once.
DROP TABLE IF EXISTS orders;
CREATE TABLE orders (
  id INT PRIMARY KEY,
  customer VARCHAR(32),
  amount DECIMAL(10,2)
);
INSERT INTO orders (id, customer, amount) VALUES (20, 'postgres', 200.00);

-- Schema for the schema-directive step (README "Different schema per backend"): the same table
-- name under a schema that MariaDB and H2 name differently.
DROP SCHEMA IF EXISTS zone1_prod CASCADE;
CREATE SCHEMA zone1_prod;
CREATE TABLE zone1_prod.orders (
  id INT PRIMARY KEY,
  customer VARCHAR(32),
  amount DECIMAL(10,2)
);
INSERT INTO zone1_prod.orders (id, customer, amount) VALUES (20, 'postgres', 200.00);
