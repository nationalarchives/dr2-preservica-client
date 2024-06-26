package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import uk.gov.nationalarchives.dp.client.fs2.Fs2Client.*
import uk.gov.nationalarchives.dp.client.{UserClient, UserClientTest}

class Fs2UserClientTest extends UserClientTest[IO](9010, 9011):
  override def valueFromF[T](value: IO[T]): T = value.unsafeRunSync()

  override def createClient(url: String): IO[UserClient[IO]] =
    userClient(url, "secret", zeroSeconds, ssmEndpointUri = "http://localhost:9011")
