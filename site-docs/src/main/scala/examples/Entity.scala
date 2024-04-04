package examples

object Entity {
  // #fs2
  object PreservicaFs2 {
    import cats.effect.IO
    import cats.implicits.*
    import sttp.capabilities.fs2.Fs2Streams
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client

    import java.util.UUID

    val url = "https://test.preservica.com"

    def processStream(name: String, stream: fs2.Stream[IO, Byte]): IO[Unit] = ???

    def getAndProcessStream(): IO[Unit] = {
      for {
        client <- Fs2Client.entityClient(url, "secretName")
        bitStreamInfo <- client.getBitstreamInfo(UUID.randomUUID())
        _ <- bitStreamInfo
          .map(eachBitStream => {
            client.streamBitstreamContent[Unit](Fs2Streams.apply)(
              eachBitStream.url,
              stream => processStream(eachBitStream.name, stream) // Pass a function in to handle the stream
            )
          })
          .sequence
      } yield ()
    }
  }
  // #fs2
}
