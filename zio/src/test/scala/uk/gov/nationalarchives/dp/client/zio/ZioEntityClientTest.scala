package uk.gov.nationalarchives.dp.client.zio
import sttp.capabilities.zio.ZioStreams
import uk.gov.nationalarchives.dp.client.zio.ZioClient._
import uk.gov.nationalarchives.dp.client.{EntityClient, EntityClientTest}
import zio._
import zio.interop.catz.core._

class ZioEntityClientTest extends EntityClientTest[Task, ZioStreams](9013, 9012, ZioStreams) {

  override def valueFromF[T](value: Task[T]): T = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe.run(value).getOrThrow()
  }

  override def createClient(url: String): Task[EntityClient[Task, ZioStreams]] =
    entityClient(url, ssmEndpointUri = "http://localhost:9012")
}
