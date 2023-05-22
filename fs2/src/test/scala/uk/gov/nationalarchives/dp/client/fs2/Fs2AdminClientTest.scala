package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import cats.effect.IO.asyncForIO
import cats.effect.unsafe.implicits.global
import uk.gov.nationalarchives.dp.client.fs2.Fs2Client._
import uk.gov.nationalarchives.dp.client.{AdminClient, AdminClientTest}

class Fs2AdminClientTest extends AdminClientTest[IO](9003) {
  override def valueFromF[T](value: IO[T]): T = value.unsafeRunSync()

  override def createClient(url: String): IO[AdminClient[IO]] = adminClient(url)
}
