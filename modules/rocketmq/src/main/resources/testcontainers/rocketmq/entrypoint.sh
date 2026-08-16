#!/bin/sh
set -e

if [ -z "$ROCKETMQ_HOME" ]; then
  ROCKETMQ_HOME=$(ls -d /home/rocketmq/rocketmq-* 2>/dev/null | head -n 1)
fi
export ROCKETMQ_HOME
echo "Using ROCKETMQ_HOME=$ROCKETMQ_HOME"

wait_for_log() {
  log_file="$1"
  pattern="$2"
  name="$3"
  count=0
  until grep -q "$pattern" "$log_file" 2>/dev/null; do
    count=$((count + 1))
    if [ "$count" -gt 60 ]; then
      echo "$name failed to start within 60s" >&2
      cat "$log_file" >&2
      exit 1
    fi
    sleep 1
  done
  echo "$name is up"
}

nohup sh "$ROCKETMQ_HOME/bin/mqnamesrv" > /tmp/namesrv-console.log 2>&1 &
wait_for_log /tmp/namesrv-console.log "boot success" "NameServer"

nohup sh "$ROCKETMQ_HOME/bin/mqbroker" -n 127.0.0.1:9876 \
  -c /tmp/testcontainers/broker.conf > /tmp/broker-console.log 2>&1 &
wait_for_log /tmp/broker-console.log "boot success" "Broker"

# The cluster-mode proxy creates its system topics on the cluster at startup
# and fails if no broker is registered with the NameServer yet, so wait for
# the broker registration (logged to the NameServer's file log, not console).
wait_for_log "${HOME:-/home/rocketmq}/logs/rocketmqlogs/namesrv.log" "new broker registered" "Broker registration"

# Cluster mode (not local mode): the proxy then answers gRPC QueryRoute with
# the endpoints the client dialed (useEndpointPortFromRequest), which keeps
# random host-port mapping working. In local mode the proxy would advertise
# brokerIP1:grpcServerPort instead, which is unreachable from the host.
#
# Note: mqproxy/runserver.sh launches the JVM without exec, so java is not PID 1
# and won't receive SIGTERM from docker stop; container stop relies on the kill
# timeout / Ryuk force-removal (accepted trade-off for test containers).
exec sh "$ROCKETMQ_HOME/bin/mqproxy" -n 127.0.0.1:9876 \
  -pc /tmp/testcontainers/rmq-proxy.json
