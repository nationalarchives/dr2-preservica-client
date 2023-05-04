package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import com.github.benmanes.caffeine.cache.Caffeine
import sttp.capabilities.Streams
import sttp.client3._
import sttp.client3.upicklejson._
import uk.gov.nationalarchives.dp.client.Client.{AuthDetails, BitStreamInfo}
import upickle.default._

import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import java.util.UUID
import scala.xml.{Elem, XML}
import scalacache._
import scalacache.caffeine._
import scalacache.memoization._

import scala.concurrent.duration._

trait Client[F[_], S] {
  def metadataForEntity(entity: Entity, auth: AuthDetails): F[Seq[Elem]]

  def getBitstreamInfo(contentRef: UUID, authDetails: AuthDetails): F[Seq[BitStreamInfo]]

  def streamBitstreamContent[T](
      stream: Streams[S]
  )(url: String, authDetails: AuthDetails, streamFn: stream.BinaryStream => F[T]): F[T]

  def entitiesUpdatedSince(dateTime: ZonedDateTime, authDetails: AuthDetails): F[Seq[Entity]]
}

object Client {
  private val asXml: ResponseAs[Either[String, Elem], Any] = asString.mapRight(XML.loadString)
  implicit val responsePayloadRW: ReadWriter[Token] = macroRW[Token]

  case class AuthDetails(userName: String, password: String)

  case class Token(token: String)

  case class BitStreamInfo(name: String, url: String)

  def createClient[F[_], S](
      apiBaseUrl: String,
      backend: SttpBackend[F, S],
      duration: FiniteDuration
  )(implicit
      me: MonadError[F, Throwable],
      sync: Sync[F]
  ): Client[F, S] = new Client[F, S] {
    val dataProcessor: DataProcessor[F] = DataProcessor[F]()

    implicit class EitherUtils[T](e: Either[String, T]) {
      def bodyLift: F[T] = me.fromTry(e.left.map(err => new RuntimeException(err)).toTry)
    }

    implicit val cache: Cache[F, String, F[String]] = CaffeineCache(
      Caffeine.newBuilder.build[String, Entry[F[String]]]
    )

    private def getAuthenticationToken(authDetails: AuthDetails): F[String] =
      memoize[F, F[String]](Some(duration)) {
        val response = basicRequest
          .body(Map("username" -> authDetails.userName, "password" -> authDetails.password))
          .post(uri"$apiBaseUrl/api/accesstoken/login")
          .response(asJson[Token])
          .send(backend)
        me.flatMap(response) { res =>
          me.fromTry(res.body.map(t => t.token).toTry)
        }
      }.flatten

    private def getApiResponseXml(url: String, token: String): F[Elem] = {
      val request = basicRequest
        .get(uri"$url")
        .headers(Map("Preservica-Access-Token" -> token))
        .response(asXml)

      me.flatMap(backend.send(request))(_.body.bodyLift)
    }

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
      def request(token: String) = basicRequest
        .get(uri"$url")
        .headers(Map("Preservica-Access-Token" -> token))
        .response(asStream(stream)(streamFn))

      for {
        token <- getAuthenticationToken(authDetails)
        res <- backend.send(request(token))
        body <- res.body.bodyLift
      } yield body
    }
  }
}
