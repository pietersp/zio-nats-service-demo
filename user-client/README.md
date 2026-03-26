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

Spawns concurrent worker fibers that exercise all five user service endpoints:

| Workers | Count | Behaviour |
|---------|-------|-----------|
| Creator | 3 | Creates users; throttles when pool reaches 200 |
| Reader  | 8 | GETs a random user every ~30 ms |
| Updater | 3+1 | Updates a random user every ~80 ms; one fiber injects blank names (1-in-5) to exercise `ValidationError` |
| Deleter | 1 | Deletes a random user every ~200 ms when pool > 80 |
| Lister  | 1 | Lists all users every 1 s |

Stats are printed every 5 seconds:

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
