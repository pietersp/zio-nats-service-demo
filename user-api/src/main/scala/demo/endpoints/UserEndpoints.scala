package demo.endpoints

import demo.Codecs
import demo.domain.*
import zio.nats.*

/**
 * Typed endpoint descriptors for the user service.
 *
 * These are inert descriptors — no handler logic lives here. Both the server
 * (which binds handlers) and the client (which calls via `requestService`)
 * share these objects as the single source of truth for the API contract.
 *
 * All endpoints are grouped under the `"users"` subject prefix:
 *   - `users.create`
 *   - `users.get`
 *   - `users.update`
 *   - `users.delete`
 *   - `users.list`
 */
object UserEndpoints {

  import Codecs.json.derived

  val createUser: ServiceEndpoint[CreateUserRequest, ValidationError, User] =
    ServiceEndpoint("create")
      .inGroup("users")
      .in[CreateUserRequest]
      .out[User]
      .failsWith[ValidationError]

  val getUser: ServiceEndpoint[GetUserRequest, UserNotFound, User] =
    ServiceEndpoint("get")
      .inGroup("users")
      .in[GetUserRequest]
      .out[User]
      .failsWith[UserNotFound]

  val updateUser: ServiceEndpoint[UpdateUserRequest, UserNotFound | ValidationError, User] =
    ServiceEndpoint("update")
      .inGroup("users")
      .in[UpdateUserRequest]
      .out[User]
      .failsWith[UserNotFound, ValidationError]

  val deleteUser: ServiceEndpoint[DeleteUserRequest, UserNotFound, DeleteUserResponse] =
    ServiceEndpoint("delete")
      .inGroup("users")
      .in[DeleteUserRequest]
      .out[DeleteUserResponse]
      .failsWith[UserNotFound]

  // Infallible: always returns a (possibly empty) list.
  val listUsers: ServiceEndpoint[ListUsersRequest, Nothing, ListUsersResponse] =
    ServiceEndpoint("list")
      .inGroup("users")
      .in[ListUsersRequest]
      .out[ListUsersResponse]
}
