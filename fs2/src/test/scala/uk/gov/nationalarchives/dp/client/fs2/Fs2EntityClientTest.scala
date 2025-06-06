package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import cats.effect.IO.asyncForIO
import cats.effect.unsafe.implicits.global
import sttp.capabilities.fs2.Fs2Streams
import uk.gov.nationalarchives.dp.client.fs2.Fs2Client.*
import uk.gov.nationalarchives.dp.client.{EntityClient, EntityClientTest}

class Fs2EntityClientTest extends EntityClientTest[IO, Fs2Streams[IO]](9002, 9009, Fs2Streams[IO]):
  override def valueFromF[T](value: IO[T]): T = value.unsafeRunSync()

  override def createClient(): IO[EntityClient[IO, Fs2Streams[IO]]] =
    entityClient("secret", zeroSeconds, ssmEndpointUri = "http://localhost:9009")
