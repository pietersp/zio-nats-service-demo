# user-service

A NATS microservice that provides CRUD operations for user management. It uses NATS Key-Value (KV) storage for persistence.

## Prerequisites

- Java 11+
- A JetStream-enabled NATS server:
  ```bash
  docker run -p 4222:4222 nats -js
  ```

## Build

From the project root:
```bash
sbt "userService/assembly"
```

## Run

```bash
java -jar user-service/target/scala-3.3.7/user-service.jar
```

To use a custom NATS server:
```bash
NATS_URL=nats://myserver:4222 java -jar user-service/target/scala-3.3.7/user-service.jar
```

## API Endpoints

The service listens on the `users.>` subject prefix and implements the following endpoints defined in `user-api`:

| Endpoint | Subject | Request | Response |
|----------|---------|---------|----------|
| `createUser` | `users.create` | `CreateUserRequest` | `User` |
| `getUser` | `users.get` | `GetUserRequest` | `User` |
| `updateUser` | `users.update` | `UpdateUserRequest` | `User` |
| `deleteUser` | `users.delete` | `DeleteUserRequest` | `DeleteUserResponse` |
| `listUsers` | `users.list` | `ListUsersRequest` | `ListUsersResponse` |

## Implementation Details

- **ZIO NATS**: Uses the `nats.service` DSL to bind typed handlers to NATS subjects.
- **Persistence**: Data is stored in a NATS KV bucket. On startup, the service ensures the bucket exists.
- **Error Handling**: Uses ZIO's typed error channel to return structured errors (e.g., `ValidationError`, `UserNotFound`) over the wire, which are automatically deserialized by the client.
