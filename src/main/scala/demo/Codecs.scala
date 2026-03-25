package demo

import zio.nats.*
import zio.blocks.schema.json.JsonFormat

/**
 * Shared codec builder for the demo app.
 *
 * Import `Codecs.json.derived` in any file that needs `NatsCodec` instances
 * derived from the zio-blocks JSON format.
 */
object Codecs {
  val json = NatsCodec.fromFormat(JsonFormat)
}
