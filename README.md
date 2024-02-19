# Preservica Client

This is a client which will call the Preservica API and return a result.

There is one package published, an FS2 Client. The client in the root package is written generically so if we want to use another Sttp backend which supports streaming, this can be added easily.

## Documentation

The main documentation can be [found here](https://nationalarchives.github.io/dr2-preservica-client/)

## Usage

```scala
  val baseUrl = "https://apiUrl"

  //Upload stream to S3
  def upload(key: String, stream: io.fs2.Stream): Task[Unit] = ???

  //Gets the metadata for an entity URL and uploads to S3
  def entityMetadataToS3(entity: Entity, authDetails: AuthDetails) = for {
    client <- Clients.fs2Client(baseUrl)
    metadata <- client.metadataForEntity(entity, authDetails)
    _ <- upload("metadata-key", fs2.io.Stream.fromIterable(metadata.flatMap(_.toString.getBytes)))
  } yield ()

  def fileFromContentObject(coId: UUID, authDetails: AuthDetails) = for {
    client <- Fs2Client.entityClient(url, "secretName")
    bitStreamInfo <- client.getBitstreamInfo(UUID.randomUUID())
    _ <- bitStreamInfo.map(eachBitStream => {
      client.streamBitstreamContent[Unit](Fs2Streams.apply)(eachBitStream.url,
        stream => processStream(eachBitStream.name, stream) //Pass a function in to handle the stream
      )
    }).sequence
  } yield ()
```
## Testing

The `ClientTest` and `DataProcessorTest` classes in the root project take type parameters. The individual tests in the FS2 project then extend these classes so the same base tests are run for both clients. 
The subprojects are aggregated so all tests can be run with `sbt test` from the root directory.

## Development
The CI tests run `sbt test` and `sbt scalafmtCheckAll`. 
Compiler warnings are set to show as errors so things like unused imports will cause the tests to fail.

## Deployment
There is one package deployed to maven under the `uk.gov.nationalarchives.dp.client` organisation, `fs2`
These are only available for Scala 3. Cross building for Scala 2 isn't currently possible but this can be refactored to allow this if necessary.
