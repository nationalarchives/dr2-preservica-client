package uk.gov.nationalarchives.dp.client.zio

import sttp.capabilities.zio.ZioStreams
import sttp.client3.httpclient.zio.HttpClientZioBackend
import uk.gov.nationalarchives.dp.client.Client._
import uk.gov.nationalarchives.dp.client.Client
import zio.interop.catz.core._
import zio._

import java.time.ZonedDateTime

object ZioClient {
  def client(url: String): Task[Client[Task, ZioStreams]] =
    HttpClientZioBackend().map { backend =>
      createClient[Task, ZioStreams](url, backend)
    }
}
