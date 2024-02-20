package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import uk.gov.nationalarchives.dp.client.EntityTest

class Fs2EntityTest extends EntityTest[IO]:
  override def valueFromF[T](value: IO[T]): T = value.unsafeRunSync()
