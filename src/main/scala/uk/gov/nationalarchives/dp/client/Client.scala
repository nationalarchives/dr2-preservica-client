package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.implicits.*
import sttp.capabilities
import sttp.capabilities.Streams
import sttp.client3.*
import sttp.client3.upicklejson.*
import uk.gov.nationalarchives.dp.client.Client.{AuthDetails, BitStreamInfo, Entity}
import upickle.default.*

import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import java.util.UUID
import scala.xml.{Elem, XML}

trait Client[F[_], S] {
  def metadataForEntityUrl(url: String, auth: AuthDetails): F[Seq[Elem]]

  def getBitstreamInfo(contentId: UUID, authDetails: AuthDetails): F[Seq[BitStreamInfo]]

  def streamBitstreamContent[T](
      stream: Streams[S]
  )(url: String, authDetails: AuthDetails, streamFn: stream.BinaryStream => F[T]): F[T]

  def entitiesUpdatedSince(dateTime: ZonedDateTime, authDetails: AuthDetails): F[Seq[Entity]]
}

object Client {
  private val asXml: ResponseAs[Either[String, Elem], Any] = asString.mapRight(XML.loadString)
  given responsePayloadRW: ReadWriter[Token] = macroRW[Token]
  case class AuthDetails(userName: String, password: String)
  case class Token(token: String)
  case class BitStreamInfo(name: String, url: String)
  case class Entity(ref: String, title: String, entityType: String, url: String, deleted: Boolean)

  def createClient[F[_], S](apiBaseUrl: String, backend: SttpBackend[F, S])(using
      me: MonadError[F, Throwable]
  ): Client[F, S] = new Client[F, S]:
    val dataProcessor: DataProcessor[F] = DataProcessor[F]()

    extension [T](e: Either[String, T])
      def bodyLift: F[T] = me.fromTry(e.left.map(err => new RuntimeException(err)).toTry)

    private def getAuthenticationToken(authDetails: AuthDetails): F[String] = {
      val response = basicRequest
        .body(Map("username" -> authDetails.userName, "password" -> authDetails.password))
        .post(uri"$apiBaseUrl/api/accesstoken/login")
        .response(asJson[Token])
        .send(backend)
      me.flatMap(response)(res => me.fromTry(res.body.map(b => b.token).toTry))
    }

    private def xmlForUrl(url: String, token: String): F[Elem] = {
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
          elem <- xmlForUrl(url.get, token)
          nextPage <- dataProcessor.nextPage(elem)
          updateEntities <- dataProcessor.updatedEntities(elem)
          all <- updatedEntities(nextPage, token, allEntities ++ updateEntities)
        } yield all
      }
    }

    override def getBitstreamInfo(
        contentId: UUID,
        authDetails: AuthDetails
    ): F[Seq[BitStreamInfo]] = {
      for {
        token <- getAuthenticationToken(authDetails)
        contentEntity <- xmlForUrl(s"$apiBaseUrl/api/entity/content-objects/$contentId", token)
        generationUrl <- dataProcessor.generationUrlFromEntity(contentEntity)
        generationInfo <- xmlForUrl(generationUrl, token)
        allGenerationUrls <- dataProcessor.allGenerationUrls(generationInfo)
        allGenerationElements <- allGenerationUrls.map(url => xmlForUrl(url, token)).sequence
        allBitstreams <- dataProcessor.allBitstreamInfo(allGenerationElements)
      } yield allBitstreams
    }

    override def metadataForEntityUrl(url: String, auth: AuthDetails): F[Seq[Elem]] = {
      for {
        token <- getAuthenticationToken(auth)
        entityInfo <- xmlForUrl(url, token)
        fragmentUrls <- dataProcessor.fragmentUrls(entityInfo)
        fragmentResponse <- fragmentUrls.map(url => xmlForUrl(url, token)).sequence
        fragments <- dataProcessor.fragments(fragmentResponse)
      } yield {
        fragments.map(XML.loadString)
      }
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
