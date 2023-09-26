package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.implicits.catsSyntaxOptionId
import uk.gov.nationalarchives.dp.client.EntityClient._

import java.util.UUID

object Entities {
  case class Entity(
      entityType: Option[EntityType],
      ref: UUID,
      title: Option[String],
      description: Option[String],
      deleted: Boolean,
      path: Option[String],
      securityTag: Option[SecurityTag] = None,
      parent: Option[UUID] = None
  )

  def fromType[F[_]](
      entityType: String,
      ref: UUID,
      title: Option[String],
      description: Option[String],
      deleted: Boolean,
      securityTag: Option[SecurityTag] = None,
      parent: Option[UUID] = None
  )(implicit
      me: MonadError[F, Throwable]
  ): F[Entity] = {
    def entity(entityType: Option[EntityType]) =
      me.pure(Entity(entityType, ref, title, description, deleted, entityType.map(_.entityPath), securityTag, parent))
    entityType match {
      case "IO" => entity(InformationObject.some)
      case "CO" => entity(ContentObject.some)
      case "SO" => entity(StructuralObject.some)
      case _    => entity(None)
    }
  }

  case class Identifier(identifierName: String, value: String)
}
