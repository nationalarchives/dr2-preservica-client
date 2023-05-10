package uk.gov.nationalarchives.dp.client.zio
import uk.gov.nationalarchives.dp.client.zio.ZioClient.contentClient
import uk.gov.nationalarchives.dp.client.{ContentClient, ContentClientTest}
import zio._
import zio.interop.catz._

class ZioContentClientTest extends ContentClientTest[Task](9005) {

  override def valueFromF[T](value: Task[T]): T = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe.run(value).getOrThrow()
  }

  override def createClient(url: String): Task[ContentClient[Task]] = contentClient(url)
}
