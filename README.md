# ZIO NATS Service Demo

A demonstration of a microservices architecture using **ZIO** and **NATS JetStream**, showcasing typed service endpoints, load simulation, and KV storage.

## Architecture Overview

This project is structured as a multi-module SBT build:

- **`user-api`**: The shared contract. Contains domain models (`User`), error types, and typed `ServiceEndpoint` descriptors. Both the service and client depend on this.
- **`user-service`**: A NATS microservice that manages users. It uses NATS Key-Value (KV) storage for persistence and handles requests defined in the API contract.
- **`user-client`**: A high-concurrency load simulator that exercises the user service at scale.

## Prerequisites

- **Java 11+**
- **SBT** (Scala Build Tool)
- **NATS Server** with JetStream enabled:
  ```bash
  docker run -p 4222:4222 nats -js
  ```

## Development Environment (optional)

**Not required.** The rest of this guide shows how to build and run the project with plain SBT and Docker. This section is only for contributors who want a reproducible dev shell.

This project includes a [devenv](https://devenv.sh/) configuration that provides Java, SBT, NATS server, and the `nats` CLI tool. If you have [devenv](https://devenv.sh/getting-started/) installed:

```bash
devenv shell        # enter the environment
devenv up           # start NATS + JetStream in the background
devenv shell        # then run format, check, build, etc.
```

Available scripts: `format`, `check`, `build`, `run-service`, `run-client`.

## Getting Started

Because SBT locks the project directory, you cannot run both the service and the client using `sbt run` simultaneously. Instead, build the executable JARs first:

### 1. Build the Artifacts
From the project root:
```bash
sbt assembly
```
This produces:
- `user-service/target/scala-3.3.7/user-service.jar`
- `user-client/target/scala-3.3.7/user-client.jar`

### 2. Start the Service
In your first terminal, launch the user microservice:
```bash
java -jar user-service/target/scala-3.3.7/user-service.jar
```

### 3. Run the Load Simulator
In a separate terminal, launch the client to simulate traffic:
```bash
java -jar user-client/target/scala-3.3.7/user-client.jar
```

## Configuration

Both components connect to `nats://localhost:4222` by default. You can override this with the `NATS_URL` environment variable:
```bash
NATS_URL=nats://myserver:4222 java -jar ...
```

For component-specific configuration (like worker counts for the client), see the documentation below.

## Component Documentation

- [**User Service**](./user-service/README.md) – A NATS microservice managing user data in KV buckets.
- [**Load Simulator**](./user-client/README.md) – A high-concurrency client for stress-testing the service.

## Features

- **Type-Safe Messaging**: Uses `zio-nats` to define request-reply patterns with full type safety for both payloads and errors.
- **KV Persistence**: Demonstrates using NATS as a lightweight key-value store.
- **Concurrent Load Testing**: A sophisticated simulator capable of generating thousands of operations per second with configurable worker pools.
- **Clean Architecture**: Separation of concerns between API definitions, business logic, and infrastructure.
