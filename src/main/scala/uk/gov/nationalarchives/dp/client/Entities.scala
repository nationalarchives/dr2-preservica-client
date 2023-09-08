package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.implicits.catsSyntaxOptionId
import uk.gov.nationalarchives.dp.client.EntityClient.{ContentObject, InformationObject, StructuralObject}

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
        Entity("IO".some, ref, title, description, deleted, InformationObject.entityPath.some)
      }
    case "CO" =>
      me.pure {
        Entity("CO".some, ref, title, description, deleted, ContentObject.entityPath.some)
      }
    case "SO" =>
      me.pure {
        Entity("SO".some, ref, title, description, deleted, StructuralObject.entityPath.some)
      }
    case _ =>
      me.pure {
        Entity(None, ref, title, description, deleted, None)
      }
  }

  case class Identifier(identifierName: String, value: String)
}
