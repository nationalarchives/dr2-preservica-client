package uk.gov.nationalarchives.dp.client.fs2

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import uk.gov.nationalarchives.dp.client.fs2.Fs2Client.*
import uk.gov.nationalarchives.dp.client.{WorkflowClient, WorkflowClientTest}

class Fs2WorkflowClientTest extends WorkflowClientTest[IO](9006, 9008):
  override def valueFromF[T](value: IO[T]): T = value.unsafeRunSync()

  override def createClient(): IO[WorkflowClient[IO]] =
    workflowClient("secret", zeroSeconds, ssmEndpointUri = "http://localhost:9008", retryCount = 1)
