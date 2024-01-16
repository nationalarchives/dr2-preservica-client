package uk.gov.nationalarchives.dp.client.zio

import uk.gov.nationalarchives.dp.client.zio.ZioClient.processMonitorClient
import uk.gov.nationalarchives.dp.client.{ProcessMonitorClient, ProcessMonitorClientTest}
import zio._

class ZioProcessMonitorClientTest extends ProcessMonitorClientTest[Task](9004, 9010) {

  override def valueFromF[T](value: Task[T]): T = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe.run(value).getOrThrow()
  }

  override def createClient(url: String): Task[ProcessMonitorClient[Task]] =
    processMonitorClient(url, "secret", zeroSeconds, ssmEndpointUri = "http://localhost:9010")
}
