package examples

import uk.gov.nationalarchives.dp.client.EntityClient.ContentObject

object Entity {
  // #fs2
  object PreservicaFs2 {
    import cats.effect.IO
    import cats.implicits._
    import sttp.capabilities.fs2.Fs2Streams
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client

    import java.util.UUID

    val url = "https://test.preservica.com"

    def processStream(name: String, stream: fs2.Stream[IO, Byte]): IO[Unit] = ???

    def getAndProcessStream(): IO[Unit] = {
      for {
        client <- Fs2Client.entityClient(url, "secretName")
        bitStreamInfo <- client.getBitstreamInfo(UUID.randomUUID(), ContentObject)
        _ <- bitStreamInfo.bitStreamInfo.map(eachBitStream => {
          client.streamBitstreamContent[Unit](Fs2Streams.apply)(eachBitStream.url,
            stream => processStream(eachBitStream.name, stream) //Pass a function in to handle the stream
          )
        }).sequence
      } yield ()
    }
  }
  // #fs2

  // #zio
  object PreservicaZio {
    import sttp.capabilities.zio.ZioStreams
    import uk.gov.nationalarchives.dp.client.zio.ZioClient
    import zio._

    import java.util.UUID
    import sttp.capabilities.zio.ZioStreams.BinaryStream

    val url = "https://test.preservica.com"

    def processStream(name: String, stream: BinaryStream): Task[Unit] = ???

    def getAndProcessStream(): Task[Unit] = {
      for {
        client <- ZioClient.entityClient(url, "secretName")
        bitStreamInfo <- client.getBitstreamInfo(UUID.randomUUID(), ContentObject)
        _ <- ZIO.collectAll {
          bitStreamInfo.bitStreamInfo.map(eachBitStream => {
            client.streamBitstreamContent[Unit](ZioStreams)(eachBitStream.url,
              stream => processStream(eachBitStream.name, stream) //Pass a function in to handle the stream
            )
          })
        }
      } yield ()
    }
  }
  // #zio
}
