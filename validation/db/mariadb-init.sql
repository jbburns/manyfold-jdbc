-- Seed for the MariaDB backend of the GUI validation harness (validation/README.md).
-- The database `demo` and the user `demo` already exist: the mariadb Docker image creates them
-- from MARIADB_DATABASE and MARIADB_USER, and db/start-local.sh creates them for a native
-- server. This script only creates the table, and it is safe to run more than once.
DROP TABLE IF EXISTS orders;
CREATE TABLE orders (
  id INT PRIMARY KEY,
  customer VARCHAR(32),
  amount DECIMAL(10,2)
);
INSERT INTO orders (id, customer, amount) VALUES (10, 'maria', 100.00), (11, 'db', 110.00);
