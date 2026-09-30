#!/usr/bin/env bash
# Start MariaDB and PostgreSQL natively and seed them for `MODE=multi validation/run.sh`.
#
# This is the NON-DOCKER path. If you have Docker, use validation/docker-compose.yml instead
# (see DEVELOPING.md). This script exists for machines without a Docker daemon, such as CI
# sandboxes, and it changes the machine: it installs packages, starts two servers and creates
# a database and a user in each. Run it only on a throwaway or development machine.
#
# What it does, in order. Every step checks first and can be repeated safely.
#   1. Installs mariadb-server and postgresql with apt-get, if missing (needs root).
#   2. Starts both servers, with `service` if it works, otherwise by starting the daemons
#      directly as their service users.
#   3. Creates database `demo` and user `demo` / password `demo` on each (on MariaDB also the
#      database `zone1_dev2`, and PostgreSQL gets schema `zone1_prod` from its seed, for the
#      schema-directive step of the harness), allows password login
#      from 127.0.0.1, and loads db/mariadb-init.sql and db/postgres-init.sql.
#   4. Prints the rows of each table.
#
# Ports: MariaDB 3306, PostgreSQL 5432, on localhost.
# Run as root (or with sudo). Exit status is non-zero if anything fails.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${LOG_DIR:-$(mktemp -d -t manyfold-validation-db.XXXXXX)}"

log() { printf '[start-local] %s\n' "$*"; }
die() { printf '[start-local] ERROR: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root (sudo validation/db/start-local.sh)"
mkdir -p "$LOG_DIR"
log "logs in $LOG_DIR"

# ---- 1. packages ------------------------------------------------------------------------------
if ! command -v mariadbd >/dev/null 2>&1 || ! command -v pg_ctlcluster >/dev/null 2>&1; then
  command -v apt-get >/dev/null 2>&1 || die "apt-get not found; install MariaDB and PostgreSQL yourself"
  log "installing mariadb-server and postgresql"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y --no-install-recommends mariadb-server postgresql >"$LOG_DIR/apt.log" 2>&1 \
    || { tail -20 "$LOG_DIR/apt.log" >&2; die "apt-get install failed"; }
fi

wait_for() { # wait_for DESCRIPTION COMMAND...
  local what="$1"; shift
  for _ in $(seq 1 60); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  die "$what did not become ready within 60 s"
}

# ---- 2a. MariaDB ------------------------------------------------------------------------------
maria_up() { mariadb-admin --protocol=socket ping; }

if ! maria_up >/dev/null 2>&1; then
  log "starting MariaDB"
  service mariadb start >"$LOG_DIR/mariadb-service.log" 2>&1 || true
  if ! maria_up >/dev/null 2>&1; then
    log "service did not start MariaDB, starting mariadbd directly"
    mkdir -p /run/mysqld && chown mysql:mysql /run/mysqld
    if [ ! -d /var/lib/mysql/mysql ]; then
      mariadb-install-db --user=mysql --datadir=/var/lib/mysql >"$LOG_DIR/mariadb-install.log" 2>&1
    fi
    nohup mariadbd --user=mysql --bind-address=127.0.0.1 >"$LOG_DIR/mariadbd.log" 2>&1 &
  fi
  wait_for "MariaDB" maria_up
fi
log "MariaDB is up"

# ---- 2b. PostgreSQL ---------------------------------------------------------------------------
PG_VERSION="$(ls /usr/lib/postgresql | sort -V | tail -1)"
[ -n "$PG_VERSION" ] || die "no PostgreSQL version found in /usr/lib/postgresql"
PG_CLUSTER=main
PG_CONF_DIR="/etc/postgresql/$PG_VERSION/$PG_CLUSTER"
[ -d "$PG_CONF_DIR" ] || pg_createcluster "$PG_VERSION" "$PG_CLUSTER" >/dev/null

psql_admin() { runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 "$@"; }
pg_up() { runuser -u postgres -- pg_isready -q -h /var/run/postgresql; }

if ! pg_up >/dev/null 2>&1; then
  log "starting PostgreSQL $PG_VERSION"
  pg_ctlcluster "$PG_VERSION" "$PG_CLUSTER" start >"$LOG_DIR/postgres-start.log" 2>&1 \
    || { cat "$LOG_DIR/postgres-start.log" >&2; die "pg_ctlcluster start failed"; }
  wait_for "PostgreSQL" pg_up
fi
log "PostgreSQL $PG_VERSION is up"

# ---- 3a. MariaDB: database, user, seed --------------------------------------------------------
mariadb --protocol=socket <<'SQL'
CREATE DATABASE IF NOT EXISTS demo;
CREATE DATABASE IF NOT EXISTS zone1_dev2;
DROP USER IF EXISTS 'demo'@'%';
CREATE USER IF NOT EXISTS 'demo'@'127.0.0.1' IDENTIFIED BY 'demo';
CREATE USER IF NOT EXISTS 'demo'@'localhost' IDENTIFIED BY 'demo';
GRANT ALL ON demo.* TO 'demo'@'127.0.0.1';
GRANT ALL ON demo.* TO 'demo'@'localhost';
GRANT ALL ON zone1_dev2.* TO 'demo'@'127.0.0.1';
GRANT ALL ON zone1_dev2.* TO 'demo'@'localhost';
FLUSH PRIVILEGES;
SQL
mariadb --protocol=socket demo <"$SCRIPT_DIR/mariadb-init.sql"

# ---- 3b. PostgreSQL: role, database, password auth, seed --------------------------------------
if [ "$(psql_admin -tAc "SELECT 1 FROM pg_roles WHERE rolname='demo'")" != 1 ]; then
  psql_admin -c "CREATE ROLE demo LOGIN PASSWORD 'demo'"
fi
if [ "$(psql_admin -tAc "SELECT 1 FROM pg_database WHERE datname='demo'")" != 1 ]; then
  psql_admin -c "CREATE DATABASE demo OWNER demo"
fi

# A rule that puts password auth for `demo` from 127.0.0.1 ahead of Ubuntu's defaults. Ubuntu's
# default already uses scram-sha-256 for host connections, but a rule of our own keeps this
# script correct if the default was changed.
HBA="$PG_CONF_DIR/pg_hba.conf"
RULE="host    demo            demo            127.0.0.1/32            scram-sha-256"
if ! grep -qxF "$RULE" "$HBA"; then
  log "adding a password rule for demo to $HBA"
  { echo "# manyfold-jdbc validation harness"; echo "$RULE"; cat "$HBA"; } >"$HBA.new"
  cat "$HBA.new" >"$HBA"
  rm -f "$HBA.new"
  psql_admin -c "SELECT pg_reload_conf()" >/dev/null
fi

PGOPTIONS="-c client_min_messages=warning" PGPASSWORD=demo psql -X -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -U demo -d demo \
  -f "$SCRIPT_DIR/postgres-init.sql"

# ---- 4. confirm -------------------------------------------------------------------------------
log "MariaDB demo.orders over TCP as demo/demo:"
mariadb -h 127.0.0.1 -P 3306 -udemo -pdemo demo -e 'SELECT * FROM orders ORDER BY id'
log "MariaDB zone1_dev2.orders over TCP as demo/demo:"
mariadb -h 127.0.0.1 -P 3306 -udemo -pdemo zone1_dev2 -e 'SELECT * FROM orders ORDER BY id'
log "PostgreSQL demo.orders over TCP as demo/demo:"
PGPASSWORD=demo psql -X -h 127.0.0.1 -p 5432 -U demo -d demo -c 'SELECT * FROM orders ORDER BY id'
log "PostgreSQL demo.zone1_prod.orders over TCP as demo/demo:"
PGPASSWORD=demo psql -X -h 127.0.0.1 -p 5432 -U demo -d demo -c 'SELECT * FROM zone1_prod.orders ORDER BY id'
log "ready: MariaDB on 3306, PostgreSQL on 5432, user demo, password demo"
