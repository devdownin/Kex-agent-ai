#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Kex Agent AI Contributors
set -euo pipefail
bootstrap=kafka:29092
kafka_bin=/opt/kafka/bin
"$kafka_bin/kafka-topics.sh" --bootstrap-server "$bootstrap" --create --if-not-exists \
  --topic forecast.demo.orders --partitions 1 --replication-factor 1
printf '{"order":1}\n{"order":2}\n{"order":3}\n' | \
  "$kafka_bin/kafka-console-producer.sh" --bootstrap-server "$bootstrap" --topic forecast.demo.orders
"$kafka_bin/kafka-console-consumer.sh" --bootstrap-server "$bootstrap" \
  --topic forecast.demo.orders --group forecast-demo --from-beginning --max-messages 1 \
  --timeout-ms 10000 --consumer-property enable.auto.commit=true --consumer-property auto.commit.interval.ms=100
