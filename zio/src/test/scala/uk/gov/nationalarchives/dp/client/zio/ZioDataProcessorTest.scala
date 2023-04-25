package uk.gov.nationalarchives.dp.client.zio

import uk.gov.nationalarchives.dp.client.DataProcessorTest
import zio.{Runtime, Task, Unsafe}
import zio.interop.catz.core.*

class ZioDataProcessorTest extends DataProcessorTest[Task] {
  val runtime: Runtime[Any] = Runtime.default

  override def valueFromF[T](value: Task[T]): T = Unsafe.unsafe { implicit unsafe =>
    runtime.unsafe.run(value).getOrThrowFiberFailure()
  }
}
