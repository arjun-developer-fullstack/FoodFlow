#!/usr/bin/env bash
# Creates FoodFlow domain-event topics (idempotent). Retry/DLT topics are created by
# Spring Kafka (@RetryableTopic) per consumer group.
#
# Naming: foodflow.<domain>.events.v1 - partition key = aggregate id (ordering per aggregate).
set -euo pipefail

BOOTSTRAP="${BOOTSTRAP:-kafka:29092}"
KT=/opt/kafka/bin/kafka-topics.sh

create() {
  local topic=$1 partitions=$2 retention_ms=$3
  "$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" --partitions "$partitions" --replication-factor 1 \
    --config retention.ms="$retention_ms" \
    --config min.insync.replicas=1
  echo "ok: $topic"
}

SEVEN_DAYS=604800000
ONE_DAY=86400000

create foodflow.user.events.v1        3 "$SEVEN_DAYS"
create foodflow.restaurant.events.v1  3 "$SEVEN_DAYS"
create foodflow.menu.events.v1        3 "$SEVEN_DAYS"
create foodflow.order.events.v1       6 "$SEVEN_DAYS"
create foodflow.payment.events.v1     6 "$SEVEN_DAYS"
create foodflow.delivery.events.v1    6 "$SEVEN_DAYS"
create foodflow.delivery.location.v1  6 "$ONE_DAY"

"$KT" --bootstrap-server "$BOOTSTRAP" --list
