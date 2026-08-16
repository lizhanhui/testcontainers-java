#!/bin/sh
set -e

if [ -z "$ROCKETMQ_HOME" ]; then
  ROCKETMQ_HOME=$(ls -d /home/rocketmq/rocketmq-* 2>/dev/null | head -n 1)
fi
export ROCKETMQ_HOME
echo "Using ROCKETMQ_HOME=$ROCKETMQ_HOME"

nohup sh "$ROCKETMQ_HOME/bin/mqnamesrv" > /tmp/namesrv-console.log 2>&1 &

count=0
until grep -q "boot success" /tmp/namesrv-console.log; do
  count=$((count + 1))
  if [ "$count" -gt 60 ]; then
    echo "NameServer failed to start within 60s" >&2
    cat /tmp/namesrv-console.log >&2
    exit 1
  fi
  sleep 1
done
echo "NameServer is up"

exec sh "$ROCKETMQ_HOME/bin/mqproxy" -pm local -n 127.0.0.1:9876 \
  -bc /tmp/testcontainers/broker.conf \
  -pc /tmp/testcontainers/rmq-proxy.json
