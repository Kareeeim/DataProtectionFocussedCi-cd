#!/bin/bash
# ===========================================================================
#  DemoBank - database bootstrap (runs once, on an empty MySQL data volume)
# ===========================================================================
#  This script is executed by the official MySQL entrypoint the first time the
#  container initialises an empty data directory. It implements the principle
#  of least privilege by creating TWO separate accounts instead of using a
#  single all-powerful application user:
#
#    demobank_migrator : owns the schema. Used exclusively by Flyway at
#                        application startup to create/alter tables.
#    demobank_app      : the account the running API uses. It can only read
#                        and write rows (SELECT/INSERT/UPDATE/DELETE). It can
#                        NOT create, alter or drop anything, so an SQL
#                        injection or a compromised API process cannot
#                        destroy or restructure the database.
#
#  Passwords are taken from the environment - they are never hardcoded here.
#
#  NOTE: the file is sourced by the entrypoint rather than executed, so we
#  deliberately avoid `set -euo pipefail` (it would leak into the entrypoint's
#  own shell). Failures are handled explicitly instead.
# ===========================================================================

mysql_cmd() {
  mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" --batch --silent "$@"
}

echo "[demobank-init] creating least-privilege database accounts..."

# --- sanity checks: refuse to create accounts with empty passwords ---------
if [ -z "${DB_APP_USER}" ] || [ -z "${DB_APP_PASSWORD}" ]; then
  echo "[demobank-init] FATAL: DB_APP_USER and DB_APP_PASSWORD must be set." >&2
  exit 1
fi

if [ -z "${DB_MIGRATOR_USER}" ] || [ -z "${DB_MIGRATOR_PASSWORD}" ]; then
  echo "[demobank-init] FATAL: DB_MIGRATOR_USER and DB_MIGRATOR_PASSWORD must be set." >&2
  exit 1
fi

# --- migration account: schema owner, needs DDL ----------------------------
mysql_cmd <<-SQL
	CREATE USER IF NOT EXISTS '${DB_MIGRATOR_USER}'@'%' IDENTIFIED BY '${DB_MIGRATOR_PASSWORD}';
	-- DDL + DML limited to the application schema. No global privileges,
	-- no GRANT OPTION, no access to the mysql system schema.
	GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
	  ON \`${MYSQL_DATABASE}\`.* TO '${DB_MIGRATOR_USER}'@'%';
SQL

# --- runtime account: rows only, no schema changes -------------------------
mysql_cmd <<-SQL
	CREATE USER IF NOT EXISTS '${DB_APP_USER}'@'%' IDENTIFIED BY '${DB_APP_PASSWORD}';
	-- Deliberately NO CREATE / ALTER / DROP / GRANT / FILE.
	GRANT SELECT, INSERT, UPDATE, DELETE ON \`${MYSQL_DATABASE}\`.* TO '${DB_APP_USER}'@'%';
SQL

# --- make sure the grants actually took effect -----------------------------
mysql_cmd -e "FLUSH PRIVILEGES;"

if [ $? -ne 0 ]; then
  echo "[demobank-init] FATAL: could not configure database accounts." >&2
  exit 1
fi

echo "[demobank-init] done: '${DB_MIGRATOR_USER}' (DDL+DML) and '${DB_APP_USER}' (DML only) are ready."
