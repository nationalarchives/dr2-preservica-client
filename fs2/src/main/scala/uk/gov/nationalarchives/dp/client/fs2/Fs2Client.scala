package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.httpclient.fs2.HttpClientFs2Backend
import uk.gov.nationalarchives.dp.client.EntityClient._
import uk.gov.nationalarchives.dp.client.AdminClient._
import uk.gov.nationalarchives.dp.client.ContentClient.createContentClient
import uk.gov.nationalarchives.dp.client.{AdminClient, ContentClient, EntityClient}

import scala.concurrent.duration._

object Fs2Client {
  def entityClient(
      url: String,
      duration: FiniteDuration = 15.minutes
  ): IO[EntityClient[IO, Fs2Streams[IO]]] =
    HttpClientFs2Backend.resource[IO]().use { backend =>
      cats.effect.IO(createEntityClient(url, backend, duration))
    }

  def adminClient(url: String, duration: FiniteDuration = 15.minutes): IO[AdminClient[IO]] =
    HttpClientFs2Backend.resource[IO]().use { backend =>
      cats.effect.IO(createAdminClient(url, backend, duration))
    }

  def contentClient(url: String, duration: FiniteDuration = 15.minutes): IO[ContentClient[IO]] =
    HttpClientFs2Backend.resource[IO]().use { backend =>
      cats.effect.IO(createContentClient(url, backend, duration))
    }
}
