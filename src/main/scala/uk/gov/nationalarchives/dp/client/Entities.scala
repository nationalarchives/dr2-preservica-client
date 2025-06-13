package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.implicits.catsSyntaxOptionId
import uk.gov.nationalarchives.dp.client.EntityClient.*
import uk.gov.nationalarchives.dp.client.EntityClient.EntityType.*

import java.util.UUID

/** The `Entity` case class with helper methods.
  */
object Entities:

  /** A Preservica entity
    * @param entityType
    *   The type of the entity
    * @param ref
    *   The id of the entity
    * @param title
    *   The title of the entity
    * @param description
    *   The description of the entity
    * @param deleted
    *   Whether the entity is deleted
    * @param path
    *   The path to use in the API call url
    * @param securityTag
    *   The security tag of the entity
    * @param parent
    *   The ref of the parent entity
    */
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

  /** Creates an entity from the IO/SO/CO type returned by Preservica
    * @param entityType
    *   The type of the entity
    * @param ref
    *   The id of the entity
    * @param title
    *   The title of the entity
    * @param description
    *   The description of the entity
    * @param deleted
    *   Whether the entity is deleted
    * @param securityTag
    *   The security tag of the entity
    * @param parent
    *   The ref of the parent entity
    * @param me
    *   An implicit instance of MonadError
    * @tparam F
    *   The effect type
    * @return
    *   An entity wrapped in the F effect
    */
  def fromType[F[_]](
      entityType: String,
      ref: UUID,
      title: Option[String],
      description: Option[String],
      deleted: Boolean,
      securityTag: Option[SecurityTag] = None,
      parent: Option[UUID] = None
  )(using
      me: MonadError[F, Throwable]
  ): F[Entity] =
    def entity(entityType: Option[EntityType]) =
      me.pure(Entity(entityType, ref, title, description, deleted, entityType.map(_.entityPath), securityTag, parent))
    entityType match
      case "IO" => entity(InformationObject.some)
      case "CO" => entity(ContentObject.some)
      case "SO" => entity(StructuralObject.some)
      case _    => entity(None)

  /** Represents an identifier on an Entity
    * @param id
    *   The identifier id
    * @param identifierName
    *   The identifier name
    * @param value
    *   The identifier value
    */
  case class IdentifierResponse(id: String, identifierName: String, value: String)
  
  enum ShortEntity:
    case ShortStructuralObject(reference: UUID) extends ShortEntity
    case ShortInformationObject(reference: UUID) extends ShortEntity
    case ShortContentObject(reference: UUID) extends ShortEntity
    case NoEntity extends ShortEntity