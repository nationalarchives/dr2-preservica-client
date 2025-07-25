package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import cats.effect.IO.asyncForIO
import cats.effect.unsafe.implicits.global
import uk.gov.nationalarchives.dp.client.fs2.Fs2Client.*
import uk.gov.nationalarchives.dp.client.{ContentClient, ContentClientTest}

class Fs2ContentClientTest extends ContentClientTest[IO](9006, 9008):
  override def valueFromF[T](value: IO[T]): T = value.unsafeRunSync()

  override def createClient(): IO[ContentClient[IO]] =
    contentClient("secret", zeroSeconds, ssmEndpointUri = "http://localhost:9008", retryCount = 1)
