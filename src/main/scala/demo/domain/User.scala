package demo.domain

import zio.blocks.schema.Schema

/** A user record as stored in the system. */
case class User(
  id: String,
  name: String,
  email: String,
  createdAt: Long // epoch milliseconds
)

object User {
  given Schema[User] = Schema.derived
}

/** Payload for creating a new user. */
case class CreateUserRequest(name: String, email: String)

object CreateUserRequest {
  given Schema[CreateUserRequest] = Schema.derived
}

/**
 * Payload for updating an existing user.
 *
 * Fields left as [[None]] are not modified.
 */
case class UpdateUserRequest(
  id: String,
  name: Option[String] = None,
  email: Option[String] = None
)

object UpdateUserRequest {
  given Schema[UpdateUserRequest] = Schema.derived
}

/** Payload for retrieving a user by ID. */
case class GetUserRequest(id: String)

object GetUserRequest {
  given Schema[GetUserRequest] = Schema.derived
}

/** Payload for deleting a user by ID. */
case class DeleteUserRequest(id: String)

object DeleteUserRequest {
  given Schema[DeleteUserRequest] = Schema.derived
}

/** Confirmation returned after a successful delete. */
case class DeleteUserResponse(id: String)

object DeleteUserResponse {
  given Schema[DeleteUserResponse] = Schema.derived
}

/** Payload for listing users. No filtering parameters for now. */
case class ListUsersRequest()

object ListUsersRequest {
  given Schema[ListUsersRequest] = Schema.derived
}

/** Response for the list operation. */
case class ListUsersResponse(users: List[User])

object ListUsersResponse {
  given Schema[ListUsersResponse] = Schema.derived
}
