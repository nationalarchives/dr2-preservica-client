package uk.gov.nationalarchives.dp.client.zio

import uk.gov.nationalarchives.dp.client.zio.ZioClient.{adminClient, workflowClient}
import uk.gov.nationalarchives.dp.client.{WorkflowClient, WorkflowClientTest}
import zio._
import zio.interop.catz._

class ZioWorkflowClientTest extends WorkflowClientTest[Task](9004, 9010) {

  override def valueFromF[T](value: Task[T]): T = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe.run(value).getOrThrow()
  }

  override def createClient(url: String): Task[WorkflowClient[Task]] =
    workflowClient(url, "secret", zeroSeconds, ssmEndpointUri = "http://localhost:9010")
}
