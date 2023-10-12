package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import uk.gov.nationalarchives.dp.client.fs2.Fs2Client._
import uk.gov.nationalarchives.dp.client.{WorkflowClient, WorkflowClientTest}

class Fs2WorkflowClientTest extends WorkflowClientTest[IO](9006, 9008) {
  override def valueFromF[T](value: IO[T]): T = value.unsafeRunSync()

  override def createClient(url: String): IO[WorkflowClient[IO]] =
    workflowClient(url, "secret", zeroSeconds, ssmEndpointUri = "http://localhost:9008")
}
