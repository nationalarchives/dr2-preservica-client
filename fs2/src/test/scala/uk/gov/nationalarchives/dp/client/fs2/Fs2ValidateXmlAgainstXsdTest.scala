package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import uk.gov.nationalarchives.dp.client.ValidateXmlAgainstXsdTest

class Fs2ValidateXmlAgainstXsdTest extends ValidateXmlAgainstXsdTest[IO]:
  override def valueFromF[T](value: IO[T]): T = value.unsafeRunSync()
