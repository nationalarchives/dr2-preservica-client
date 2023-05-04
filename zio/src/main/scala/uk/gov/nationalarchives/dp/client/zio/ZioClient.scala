package uk.gov.nationalarchives.dp.client.zio

import sttp.capabilities.zio.ZioStreams
import sttp.client3.httpclient.zio.HttpClientZioBackend
import uk.gov.nationalarchives.dp.client.Client._
import uk.gov.nationalarchives.dp.client.Client
import zio.Task
import zio.interop.catz._

import scala.concurrent.duration._

object ZioClient {
  def client(url: String, duration: FiniteDuration = 15.minutes): Task[Client[Task, ZioStreams]] =
    HttpClientZioBackend().map { backend =>
      createClient[Task, ZioStreams](url, backend, duration)
    }
}
