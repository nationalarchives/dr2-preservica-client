package uk.gov.nationalarchives.dp.client.zio

import uk.gov.nationalarchives.dp.client.EntityTest
import zio.{Runtime, Task, Unsafe}
import zio.interop.catz._

class ZioEntityTest extends EntityTest[Task] {
  val runtime: Runtime[Any] = Runtime.default

  override def valueFromF[T](value: Task[T]): T = Unsafe.unsafe { implicit unsafe =>
    runtime.unsafe.run(value).getOrThrow()
  }
}
