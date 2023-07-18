package uk.gov.nationalarchives.dp.client.zio

import uk.gov.nationalarchives.dp.client.zio.ZioClient.adminClient
import uk.gov.nationalarchives.dp.client.{AdminClient, AdminClientTest}
import zio._
import zio.interop.catz._

class ZioAdminClientTest extends AdminClientTest[Task](9004, 9010) {

  override def valueFromF[T](value: Task[T]): T = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe.run(value).getOrThrow()
  }

  override def createClient(url: String): Task[AdminClient[Task]] =
    adminClient(url, zeroSeconds, ssmEndpointUri = "http://localhost:9010")
}
