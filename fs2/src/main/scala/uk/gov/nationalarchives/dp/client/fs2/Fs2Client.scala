package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.httpclient.fs2.HttpClientFs2Backend
import uk.gov.nationalarchives.dp.client.Client._
import uk.gov.nationalarchives.dp.client.Client

object Fs2Client {
  def client(url: String): IO[Client[IO, Fs2Streams[IO]]] =
    HttpClientFs2Backend.resource[IO]().use { backend =>
      cats.effect.IO(createClient(url, backend))
    }
}
