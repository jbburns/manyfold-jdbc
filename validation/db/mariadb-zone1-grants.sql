-- Docker Compose only: lets the demo user, which the mariadb image creates for database `demo`,
-- use the database `zone1_dev2` that mariadb-init.sql creates for the schema-directive step.
-- The image runs init scripts as root, in file name order, after mariadb-init.sql. The native
-- path, db/start-local.sh, grants the same on its loopback-only accounts instead.
GRANT ALL ON zone1_dev2.* TO 'demo'@'%';
