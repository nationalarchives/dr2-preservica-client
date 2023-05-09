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
import scala.concurrent.duration._
import scala.xml.{Elem, XML}
import uk.gov.nationalarchives.dp.client.Utils._

trait EntityClient[F[_], S] {
  def metadataForEntity(entity: Entity, auth: AuthDetails): F[Seq[Elem]]

  def getBitstreamInfo(contentRef: UUID, authDetails: AuthDetails): F[Seq[BitStreamInfo]]

  def streamBitstreamContent[T](
      stream: Streams[S]
  )(url: String, authDetails: AuthDetails, streamFn: stream.BinaryStream => F[T]): F[T]

  def entitiesUpdatedSince(dateTime: ZonedDateTime, authDetails: AuthDetails): F[Seq[Entity]]
}

object EntityClient {

  def createEntityClient[F[_], S](
      apiBaseUrl: String,
      backend: SttpBackend[F, S],
      duration: FiniteDuration
  )(implicit
      me: MonadError[F, Throwable],
      sync: Sync[F]
  ): EntityClient[F, S] = new EntityClient[F, S] {
    val utils: Utils[F, S] = Utils(apiBaseUrl, backend, duration)
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
        authDetails: AuthDetails
    ): F[Seq[BitStreamInfo]] = {
      for {
        token <- getAuthenticationToken(authDetails)
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

    override def metadataForEntity(entity: Entity, auth: AuthDetails): F[Seq[Elem]] = {
      for {
        token <- getAuthenticationToken(auth)
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
        authDetails: AuthDetails
    ): F[Seq[Entity]] = {
      val dateString = dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"))
      val queryParams = Map("date" -> dateString, "max" -> "100", "start" -> "0")
      val url = uri"$apiBaseUrl/api/entity/entities/updated-since?$queryParams"
      for {
        token <- getAuthenticationToken(authDetails)
        entities <- updatedEntities(url.toString.some, token, Nil)
      } yield entities
    }

    def streamBitstreamContent[T](
        stream: Streams[S]
    )(url: String, authDetails: AuthDetails, streamFn: stream.BinaryStream => F[T]): F[T] = {
      val apiUri = uri"$url"
      def request(token: String) = basicRequest
        .get(apiUri)
        .headers(Map("Preservica-Access-Token" -> token))
        .response(asStream(stream)(streamFn))

      for {
        token <- getAuthenticationToken(authDetails)
        res <- backend.send(request(token))
        body <- me.fromEither {
          res.body.left.map(err => PreservicaClientException(Method.GET, apiUri, res.code, err))
        }
      } yield body
    }
  }
}
