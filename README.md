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

## Getting Started

### 1. Start the Service
In one terminal, run the user management service:
```bash
sbt "userService/run"
```

### 2. Run the Load Simulator
In a separate terminal, run the client to simulate traffic:
```bash
sbt "userClient/run"
```

## Detailed Documentation

For more specific information on each component, refer to their respective READMEs:

- [**User Service Guide**](./user-service/README.md) – Implementation details, storage configuration, and API handlers.
- [**Load Simulator Guide**](./user-client/README.md) – Configuration options, worker scaling, and performance reporting.

## Features

- **Type-Safe Messaging**: Uses `zio-nats` to define request-reply patterns with full type safety for both payloads and errors.
- **KV Persistence**: Demonstrates using NATS as a lightweight key-value store.
- **Concurrent Load Testing**: A sophisticated simulator capable of generating thousands of operations per second with configurable worker pools.
- **Clean Architecture**: Separation of concerns between API definitions, business logic, and infrastructure.

---
Created by [pietersp](https://github.com/pietersp/zio-nats-service-demo).
