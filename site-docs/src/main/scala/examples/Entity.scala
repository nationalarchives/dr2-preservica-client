package examples

import uk.gov.nationalarchives.dp.client.Entities.EntityRef.{ContentObjectRef, InformationObjectRef}

object Entity {
  // #fs2
  object PreservicaFs2 {
    import cats.effect.IO
    import cats.implicits.*
    import sttp.capabilities.fs2.Fs2Streams
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client

    import java.util.UUID

    def processStream(name: String, stream: fs2.Stream[IO, Byte]): IO[Unit] = ???

    def getAndProcessStream(): IO[Unit] = {
      for {
        client <- Fs2Client.entityClient("secretName")
        bitStreamInfo <- client.getBitstreamInfo(UUID.randomUUID())
        _ <- bitStreamInfo
          .map(eachBitStream => {
            client.streamBitstreamContent[Unit](Fs2Streams.apply)(
              eachBitStream.potentialUrl.get, // The url is optional because it is not available in the response from information-objects/{ref}?expand=structure
              stream => processStream(eachBitStream.name, stream) // Pass a function in to handle the stream
            )
          })
          .sequence
      } yield ()
    }

    private def doSomething(): IO[Unit] = ???
    private def doSomethingElse(): IO[Unit] = ???
  }
  // #fs2
}
