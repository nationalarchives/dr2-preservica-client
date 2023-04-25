package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import uk.gov.nationalarchives.dp.client.DataProcessorTest
import cats.effect.unsafe.implicits.global

class Fs2DataProcessorTest extends DataProcessorTest[IO] {
  override def valueFromF[T](value: IO[T]): T = value.unsafeRunSync()
}
