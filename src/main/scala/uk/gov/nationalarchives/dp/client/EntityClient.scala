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
import uk.gov.nationalarchives.dp.client.Utils._

trait EntityClient[F[_], S] {
  def metadataForEntity(entity: Entity, secretName: String): F[Seq[Elem]]

  def getBitstreamInfo(contentRef: UUID, secretName: String): F[Seq[BitStreamInfo]]

  def streamBitstreamContent[T](
      stream: Streams[S]
  )(url: String, secretName: String, streamFn: stream.BinaryStream => F[T]): F[T]

  def entitiesUpdatedSince(dateTime: ZonedDateTime, secretName: String): F[Seq[Entity]]
}

object EntityClient {

  def createEntityClient[F[_], S](clientConfig: ClientConfig[F, S])(implicit
      me: MonadError[F, Throwable],
      sync: Sync[F]
  ): EntityClient[F, S] = new EntityClient[F, S] {
    val utils: Utils[F, S] = Utils(clientConfig)
    import utils._

    private def updatedEntities(
        url: Option[String],
        token: String,
        allEntities: Seq[Entity]
    ): F[Seq[Entity]] = {
      if (url.isEmpty) {
        me.pure(allEntities)
      } else {
        for {
          entitiesResponseXml <- getApiResponseXml(url.get, token)
          entitiesWithUpdates <- dataProcessor.getUpdatedEntities(entitiesResponseXml)
          nextPageUrl <- dataProcessor.nextPage(entitiesResponseXml)
          allUpdatedEntities <- updatedEntities(
            nextPageUrl,
            token,
            allEntities ++ entitiesWithUpdates
          )
        } yield allUpdatedEntities
      }
    }

    override def getBitstreamInfo(
        contentRef: UUID,
        secretName: String
    ): F[Seq[BitStreamInfo]] = {
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
        allBitstreams <- dataProcessor.allBitstreamInfo(allGenerationElements)
      } yield allBitstreams
    }

    override def metadataForEntity(entity: Entity, secretName: String): F[Seq[Elem]] = {
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
    }

    override def entitiesUpdatedSince(
        dateTime: ZonedDateTime,
        secretName: String
    ): F[Seq[Entity]] = {
      val dateString = dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"))
      val queryParams = Map("date" -> dateString, "max" -> "100", "start" -> "0")
      val url = uri"$apiBaseUrl/api/entity/entities/updated-since?$queryParams"
      for {
        token <- getAuthenticationToken(secretName)
        entities <- updatedEntities(url.toString.some, token, Nil)
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
