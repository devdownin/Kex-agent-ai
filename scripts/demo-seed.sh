#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-kafka:29092}"
KAFKA_BIN="${KAFKA_BIN:-/opt/kafka/bin}"

create_topic() {
  local topic="$1"
  "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists     --topic "$topic" --partitions 1 --replication-factor 1 >/dev/null
}

produce_lines() {
  local topic="$1"
  "$KAFKA_BIN/kafka-console-producer.sh" --bootstrap-server "$BOOTSTRAP" --topic "$topic" >/dev/null
}

echo "Preparing Kex demo scenarios on $BOOTSTRAP"

for topic in demo.nominal demo.empty demo.stalled demo.lag demo.dlq; do
  create_topic "$topic"
done

for i in $(seq 1 12); do
  printf '{"scenario":"nominal","id":%s,"status":"processed"}\n' "$i"
done | produce_lines demo.nominal

printf '{"scenario":"stalled","id":1,"status":"last-known-event"}\n' | produce_lines demo.stalled

for i in $(seq 1 30); do
  printf '{"scenario":"lag","id":%s,"status":"queued"}\n' "$i"
done | produce_lines demo.lag

for i in $(seq 1 5); do
  printf '{"scenario":"dlq","id":%s,"error":"validation_failed"}\n' "$i"
done | produce_lines demo.dlq

# Create a real consumer-group offset behind the end of demo.lag.
# The console consumer commits the first three records and exits; the remaining records stay pending.
"$KAFKA_BIN/kafka-console-consumer.sh"   --bootstrap-server "$BOOTSTRAP"   --topic demo.lag   --group demo-slow-consumer   --from-beginning   --max-messages 3   --timeout-ms 10000 >/dev/null 2>&1 || true

echo "Kex demo data ready:"
echo "  demo.nominal : 12 processed events"
echo "  demo.empty   : intentionally empty"
echo "  demo.stalled : one last-known event, then no producer"
echo "  demo.lag     : 30 events, demo-slow-consumer consumed only 3"
echo "  demo.dlq     : 5 failed events"
