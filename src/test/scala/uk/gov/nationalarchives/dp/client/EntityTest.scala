package uk.gov.nationalarchives.dp.client

import cats.MonadError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor2}
import uk.gov.nationalarchives.dp.client.Entities.fromType

import java.util.UUID

abstract class EntityTest[F[_]](implicit cme: MonadError[F, Throwable])
    extends AnyFlatSpec
    with TableDrivenPropertyChecks {
  def valueFromF[T](value: F[T]): T

  val entityTypes: TableFor2[String, String] = Table(
    ("entityType", "expectedPath"),
    ("IO", "information-objects"),
    ("CO", "content-objects"),
    ("SO", "structural-objects")
  )
  forAll(entityTypes) { (entityType, expectedPath) =>
    "fromType" should s"return an object with path $expectedPath for entity type $entityType" in {
      valueFromF(fromType(entityType, UUID.randomUUID(), None, deleted = false)).path.get should equal(
        expectedPath
      )
    }
  }
}
