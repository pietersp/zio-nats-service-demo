# user-client

Load-simulation client for the user service. Runs as an independent JVM process — start the service first, then run this client in a separate terminal.

## Prerequisites

- Java 11+
- A JetStream-enabled NATS server running locally:
  ```
  docker run -p 4222:4222 nats -js
  ```
- The user service running (see `user-service/`)

## Build

From the project root:

```
sbt "userClient/assembly"
```

This produces `user-client/target/scala-3.3.7/user-client.jar`.

## Run

```
java -jar user-client/target/scala-3.3.7/user-client.jar
```

To connect to a non-default NATS server, set `NATS_URL`:

```
NATS_URL=nats://myserver:4222 java -jar user-client/target/scala-3.3.7/user-client.jar
```

Press **Ctrl-C** to stop.

## What it does

Spawns concurrent worker fibers that exercise all five user service endpoints. Worker counts scale dynamically with the target pool size.

| Workers | Count (Default) | Behaviour |
|---------|-----------------|-----------|
| Creator | 100             | Creates users; throttles when pool reaches `POOL_TARGET` |
| Reader  | 200             | GETs a random user every ~30 ms |
| Updater | 100+1           | Updates a random user every ~80 ms; one fiber injects blank names (1-in-5) to exercise `ValidationError` |
| Deleter | 1               | Deletes a random user every ~200 ms when pool > `POOL_MIN` |
| Lister  | 1               | Lists all users every 1 s |

## Configuration

The simulator can be adjusted using the following environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `POOL_TARGET` | `1000` | Target pool size. Creators throttle when reached. Workers scale: 1 creator/updater per 10, 2 readers per 10. |
| `POOL_MIN` | `80` | Minimum pool size. Deleters stop below this threshold. |
| `REPORT_EVERY_SECS` | `5` | How often (in seconds) to print the load report. |
| `NATS_URL` | `nats://localhost:4222` | URL of the NATS server to connect to. |

Example running with custom load:
```bash
POOL_TARGET=5000 java -jar user-client/target/scala-3.3.7/user-client.jar
```

Stats are printed periodically (default every 5 seconds):

```
╔═══ Load Report [5s interval | 284 ops/s | pool: 147 users] ═══╗
  create     71  (14/s)
  get       892 ok    3 not-found  (179/s)
  update    234 ok    5 not-found    8 validation  (49/s)
  delete     32  (6/s)
  list        2
  infra errors: 0
╚════════════════════════════════════════════════════════════╝
```
