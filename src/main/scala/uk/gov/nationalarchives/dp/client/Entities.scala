package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.implicits.catsSyntaxOptionId

import java.util.UUID

object Entities {
  case class Entity(
      entityType: Option[String],
      ref: UUID,
      title: Option[String],
      description: Option[String],
      deleted: Boolean,
      path: Option[String]
  )

  def fromType[F[_]](
      entityType: String,
      ref: UUID,
      title: Option[String],
      description: Option[String],
      deleted: Boolean
  )(implicit
      me: MonadError[F, Throwable]
  ): F[Entity] = entityType match {
    case "IO" =>
      me.pure {
        Entity("IO".some, ref, title, description, deleted, "information-objects".some)
      }
    case "CO" =>
      me.pure {
        Entity("CO".some, ref, title, description, deleted, "content-objects".some)
      }
    case "SO" =>
      me.pure {
        Entity("SO".some, ref, title, description, deleted, "structural-objects".some)
      }
    case _ =>
      me.pure {
        Entity(None, ref, title, description, deleted, None)
      }
  }
}
