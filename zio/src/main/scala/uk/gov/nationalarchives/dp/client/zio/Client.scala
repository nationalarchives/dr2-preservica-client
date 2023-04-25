package uk.gov.nationalarchives.dp.client.zio

import sttp.capabilities.zio.ZioStreams
import sttp.client3.httpclient.zio.HttpClientZioBackend
import uk.gov.nationalarchives.dp.client.Client.*
import uk.gov.nationalarchives.dp.client.Client
import zio.interop.catz.core.*
import zio.*

import java.time.ZonedDateTime

object Client {
  @main def hello() = {
    val z = for {
      client <- zioClient("http://ec2-18-133-182-45.eu-west-2.compute.amazonaws.com/")
      b <- client.entitiesUpdatedSince(
        ZonedDateTime.now().minusYears(2),
        AuthDetails("first_user", "Delete0n1stUse")
      )
    } yield b

    val runtime = Runtime.default
    val c = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(z).getOrThrowFiberFailure()
    }
    print(c)
  }

  def zioClient(url: String): Task[Client[Task, ZioStreams]] =
    HttpClientZioBackend().map { backend =>
      createClient[Task, ZioStreams](url, backend)
    }
}
