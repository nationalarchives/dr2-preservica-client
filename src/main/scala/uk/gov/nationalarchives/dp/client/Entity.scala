package uk.gov.nationalarchives.dp.client

import cats.MonadError

import java.util.UUID

trait Entity {
  def fromString(id: String): UUID = UUID.fromString(id)
  def path: String
  def ref: UUID
  def title: String
  def deleted: Boolean
}
object Entity {

  private abstract case class BaseRef(ref: UUID, title: String, deleted: Boolean) extends Entity

  def fromType[F[_]](entityType: String, ref: UUID, title: String, deleted: Boolean)(implicit
      me: MonadError[F, Throwable]
  ): F[Entity] = entityType match {
    case "IO" =>
      me.pure {
        new BaseRef(ref, title, deleted) {
          override def path: String = "information-objects"
        }
      }
    case "CO" =>
      me.pure {
        new BaseRef(ref, title, deleted) {
          override def path: String = "content-objects"
        }
      }
    case "SO" =>
      me.pure {
        new BaseRef(ref, title, deleted) {
          override def path: String = "structural-objects"
        }
      }
    case _ => me.raiseError(PreservicaClientException(s"Entity type $entityType not recognised"))
  }
}
