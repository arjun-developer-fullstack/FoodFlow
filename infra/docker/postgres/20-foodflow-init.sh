#!/usr/bin/env bash
# FoodFlow: one database + one owner role per service (database-per-service, ADR-0001/0008).
# Runs once, on first container start (empty data volume). Passwords come from the container
# environment (infra/docker/.env), never from this file. Cloud databases are provisioned by Terraform.
set -euo pipefail

create_service_db() {
  local role=$1 database=$2 password=$3
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v role="$role" -v database="$database" -v password="$password" <<'SQL'
CREATE ROLE :"role" LOGIN PASSWORD :'password';
CREATE DATABASE :"database" OWNER :"role";
REVOKE CONNECT ON DATABASE :"database" FROM PUBLIC;
SQL
}

enable_postgis() {
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$1" -c 'CREATE EXTENSION IF NOT EXISTS postgis;'
}

create_service_db user_svc       user_db       "$USER_DB_PASSWORD"
create_service_db restaurant_svc restaurant_db "$RESTAURANT_DB_PASSWORD"
create_service_db order_svc      order_db      "$ORDER_DB_PASSWORD"
create_service_db payment_svc    payment_db    "$PAYMENT_DB_PASSWORD"
create_service_db delivery_svc   delivery_db   "$DELIVERY_DB_PASSWORD"

# Extensions need superuser rights, so they are installed here rather than in service migrations.
enable_postgis restaurant_db
enable_postgis delivery_db
