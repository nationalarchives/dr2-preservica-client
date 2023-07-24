package uk.gov.nationalarchives.dp.client.zio

import uk.gov.nationalarchives.dp.client.PreservicaClientCacheTest
import zio._
import zio.interop.catz._

class ZioPreservicaClientCacheTest extends PreservicaClientCacheTest[Task] {
  override def valueFromF[T](value: Task[T]): T = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe.run(value).getOrThrow()
  }
}
