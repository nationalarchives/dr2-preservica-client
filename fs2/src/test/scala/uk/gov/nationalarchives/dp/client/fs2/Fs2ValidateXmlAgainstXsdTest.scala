package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import uk.gov.nationalarchives.dp.client.ValidateXmlAgainstXsd.xipXsdSchemaV6
import uk.gov.nationalarchives.dp.client.{ValidateXmlAgainstXsd, ValidateXmlAgainstXsdTest}

class Fs2ValidateXmlAgainstXsdTest extends ValidateXmlAgainstXsdTest[IO]:
  private val xmlValidator = ValidateXmlAgainstXsd[IO](xipXsdSchemaV6)

  override def valueFromF[T](value: IO[T]): T = value.unsafeRunSync()
