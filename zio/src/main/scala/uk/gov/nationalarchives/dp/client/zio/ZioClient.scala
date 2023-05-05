package uk.gov.nationalarchives.dp.client.zio

import sttp.capabilities.zio.ZioStreams
import sttp.client3.httpclient.zio.HttpClientZioBackend
import uk.gov.nationalarchives.dp.client.EntityClient._
import uk.gov.nationalarchives.dp.client.AdminClient._
import uk.gov.nationalarchives.dp.client.{AdminClient, EntityClient}
import zio.Task
import zio.interop.catz._

import scala.concurrent.duration._

object ZioClient {

  def entityClient(
      url: String,
      duration: FiniteDuration = 15.minutes
  ): Task[EntityClient[Task, ZioStreams]] =
    HttpClientZioBackend().map { backend =>
      createEntityClient[Task, ZioStreams](url, backend, duration)
    }

  def adminClient(url: String, duration: FiniteDuration = 15.minutes): Task[AdminClient[Task]] =
    HttpClientZioBackend().map { backend =>
      createAdminClient[Task, ZioStreams](url, backend, duration)
    }
}
