package uk.gov.nationalarchives.dp.client

import cats.MonadError

import java.util.UUID

object Entities {
  case class Entity(entityType: String, ref: UUID, title: Option[String], deleted: Boolean, path: String)

  def fromType[F[_]](entityType: String, ref: UUID, title: Option[String], deleted: Boolean)(implicit
                                                                                             me: MonadError[F, Throwable]
  ): F[Entity] = entityType match {
    case "IO" =>
      me.pure {
        Entity("IO", ref, title, deleted, "information-objects")
      }
    case "CO" =>
      me.pure {
        Entity("CO", ref, title, deleted, "content-objects")
      }
    case "SO" =>
      me.pure {
        Entity("SO", ref, title, deleted, "structural-objects")
      }
    case _ => me.raiseError(PreservicaClientException(s"Entity type $entityType not recognised"))
  }
}
