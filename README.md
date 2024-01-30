# Preservica Client

This is a client which will call the Preservica API and return a result.

There are two packages published, an FS2 Client and a ZIO client. The client in the root package is written generically so if we want to use another Sttp backend which supports streaming, this can be added easily.

## Documentation

The main documentation can be [found here](https://nationalarchives.github.io/dr2-preservica-client/)

## Usage
This example uses the ZIO client but something similar will work with the FS2 client.

```scala
  val baseUrl = "https://apiUrl"

  //Upload stream to S3
  def upload(key: String, stream: ZioStreams.BinaryStream): Task[Unit] = ???

  //Gets the metadata for an entity URL and uploads to S3
  def entityMetadataToS3(entity: Entity, authDetails: AuthDetails) = for {
    client <- Clients.zioClient(baseUrl)
    metadata <- client.metadataForEntity(entity, authDetails)
    _ <- upload("metadata-key", ZStream.fromIterable(metadata.flatMap(_.toString.getBytes)))
  } yield ()

  def fileFromContentObject(coId: UUID, authDetails: AuthDetails) = for {
    client <- Clients.zioClient(baseUrl)
    // Get bitstream file name and url
    res <- client.getBitstreamInfo(UUID.fromString("884be732-2b54-4690-9ce2-db47a6fbbaf8"), authDetails)
    _ <- ZIO.collectAll {
      res.map(eachBitStream => {
        client.streamBitstreamContent[Unit](ZioStreams)(eachBitStream.url, authDetails, 
          stream => upload(eachBitStream.name, stream) //Pass a function in to handle the stream
        )
      })
    }
  } yield ()
```
## Testing

The `ClientTest` and `DataProcessorTest` classes in the root project take type parameters. The individual tests in the ZIO and FS2 projects then extend these classes so the same base tests are run for both clients. 
The subprojects are aggregated so all tests can be run with `sbt test` from the root directory.

## Development
The CI tests run `sbt test` and `sbt scalafmtCheckAll`. 
Compiler warnings are set to show as errors so things like unused imports will cause the tests to fail.

## Deployment
There are two packages deployed to maven under the `uk.gov.nationalarchives.dp.client` organisation, `fs2` and `zio`
These are only available for Scala 3. Cross building for Scala 2 isn't currently possible but this can be refactored to allow this if necessary.
