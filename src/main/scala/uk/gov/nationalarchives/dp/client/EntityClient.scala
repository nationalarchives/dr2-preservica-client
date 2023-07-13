package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import sttp.capabilities.Streams
import sttp.client3._
import sttp.model.Method

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.xml.{Elem, XML}
import uk.gov.nationalarchives.dp.client.Client._
import uk.gov.nationalarchives.dp.client.Entities.Entity

trait EntityClient[F[_], S] {
  val dateFormatter: DateTimeFormatter

  def metadataForEntity(entity: Entity, secretName: String): F[Seq[Elem]]

  def getBitstreamInfo(contentRef: UUID, secretName: String): F[Seq[BitStreamInfo]]

  def streamBitstreamContent[T](
      stream: Streams[S]
  )(url: String, secretName: String, streamFn: stream.BinaryStream => F[T]): F[T]

  def entitiesUpdatedSince(
      dateTime: ZonedDateTime,
      secretName: String,
      startEntry: Int,
      maxEntries: Int = 1000
  ): F[Seq[Entity]]
}

object EntityClient {

  def createEntityClient[F[_], S](clientConfig: ClientConfig[F, S])(implicit
      me: MonadError[F, Throwable],
      sync: Sync[F]
  ): EntityClient[F, S] = new EntityClient[F, S] {
    val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private val apiBaseUrl: String = clientConfig.apiBaseUrl

    private val client: Client[F, S] = Client(clientConfig)
    import client._

    private def updatedEntities(
        url: String,
        token: String
    ): F[Seq[Entity]] =
      for {
        entitiesResponseXml <- getApiResponseXml(url, token)
        entitiesWithUpdates <- dataProcessor.getUpdatedEntities(entitiesResponseXml)
      } yield entitiesWithUpdates

    override def getBitstreamInfo(
        contentRef: UUID,
        secretName: String
    ): F[Seq[BitStreamInfo]] =
      for {
        token <- getAuthenticationToken(secretName)
        contentEntity <- getApiResponseXml(
          s"$apiBaseUrl/api/entity/content-objects/$contentRef",
          token
        )
        generationUrl <- dataProcessor.generationUrlFromEntity(contentEntity)
        generationInfo <- getApiResponseXml(generationUrl, token)
        allGenerationUrls <- dataProcessor.allGenerationUrls(generationInfo)
        allGenerationElements <- allGenerationUrls
          .map(url => getApiResponseXml(url, token))
          .sequence
        allUrls <- dataProcessor.allBitstreamUrls(allGenerationElements)
        bitstreamXmls <- allUrls.map(url => getApiResponseXml(url, token)).sequence
        allBitstreamInfo <- dataProcessor.allBitstreamInfo(bitstreamXmls)
      } yield allBitstreamInfo

    override def metadataForEntity(entity: Entity, secretName: String): F[Seq[Elem]] =
      for {
        token <- getAuthenticationToken(secretName)
        entityInfo <- getApiResponseXml(
          s"$apiBaseUrl/api/entity/${entity.path}/${entity.ref}",
          token
        )
        fragmentUrls <- dataProcessor.fragmentUrls(entityInfo)
        fragmentResponse <- fragmentUrls.map(url => getApiResponseXml(url, token)).sequence
        fragments <- dataProcessor.fragments(fragmentResponse)
      } yield fragments.map(XML.loadString)

    override def entitiesUpdatedSince(
        dateTime: ZonedDateTime,
        secretName: String,
        startEntry: Int,
        maxEntries: Int = 1000
    ): F[Seq[Entity]] = {
      val dateString = dateTime.format(dateFormatter)
      val queryParams = Map("date" -> dateString, "max" -> maxEntries, "start" -> startEntry)
      val url = uri"$apiBaseUrl/api/entity/entities/updated-since?$queryParams"
      for {
        token <- getAuthenticationToken(secretName)
        entities <- updatedEntities(url.toString, token)
      } yield entities
    }

    def streamBitstreamContent[T](
        stream: Streams[S]
    )(url: String, secretName: String, streamFn: stream.BinaryStream => F[T]): F[T] = {
      val apiUri = uri"$url"
      def request(token: String) = basicRequest
        .get(apiUri)
        .headers(Map("Preservica-Access-Token" -> token))
        .response(asStream(stream)(streamFn))

      for {
        token <- getAuthenticationToken(secretName)
        res <- backend.send(request(token))
        body <- me.fromEither {
          res.body.left.map(err => PreservicaClientException(Method.GET, apiUri, res.code, err))
        }
      } yield body
    }
  }
}
