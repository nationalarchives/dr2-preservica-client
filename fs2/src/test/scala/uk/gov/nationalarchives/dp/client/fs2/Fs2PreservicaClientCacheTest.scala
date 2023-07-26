package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import uk.gov.nationalarchives.dp.client.PreservicaClientCacheTest

class Fs2PreservicaClientCacheTest extends PreservicaClientCacheTest[IO] {
  override def valueFromF[T](value: IO[T]): T = value.unsafeRunSync()
}
