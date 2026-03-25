package demo.domain

import zio.blocks.schema.Schema
import zio.nats.ServiceErrorMapper

/** Returned when no user with the given ID exists. */
case class UserNotFound(id: String)

object UserNotFound {
  given Schema[UserNotFound] = Schema.derived

  given ServiceErrorMapper[UserNotFound] with {
    def toErrorResponse(e: UserNotFound): (String, Int) =
      (s"User '${e.id}' not found", 404)
  }
}

/** Returned when a request field fails validation. */
case class ValidationError(field: String, reason: String)

object ValidationError {
  given Schema[ValidationError] = Schema.derived

  given ServiceErrorMapper[ValidationError] with {
    def toErrorResponse(e: ValidationError): (String, Int) =
      (s"${e.field}: ${e.reason}", 400)
  }
}
