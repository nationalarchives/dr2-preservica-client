package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.implicits._
import cats.effect.Sync
import sttp.client3._
import sttp.client3.upicklejson.asJson
import uk.gov.nationalarchives.dp.client.DataProcessor.ClosureResultIndexNames
import uk.gov.nationalarchives.dp.client.Client.ClientConfig

import upickle.default._

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

trait ContentClient[F[_]] {
  def findExpiredClosedDocuments(secretName: String): F[List[Entity]]
}
object ContentClient {
  case class SearchField(name: String, values: List[String])
  case class SearchQuery(q: String, fields: List[SearchField])

  def createContentClient[F[_], S](clientConfig: ClientConfig[F, S])(implicit
      me: MonadError[F, Throwable],
      sync: Sync[F]
  ): ContentClient[F] = new ContentClient[F] {

    case class SearchResponseValue(objectIds: List[String], totalHits: Int)

    case class SearchResponse(success: Boolean, value: SearchResponseValue)

    private val client: Client[F, S] = Client(clientConfig)
    implicit val fieldWriter: Writer[SearchField] = macroW[SearchField]
    implicit val queryWriter: Writer[SearchQuery] = macroW[SearchQuery]
    implicit val searchResponseValueWriter: Reader[SearchResponseValue] = macroR[SearchResponseValue]
    implicit val searchResponseWriter: Reader[SearchResponse] = macroR[SearchResponse]

    import client._

    def toEntities(entityInfo: List[String]): F[List[Entity]] = entityInfo
      .map(info => {
        val entityTypeAndRefSplit = info.split("\\|")
        val entityType = entityTypeAndRefSplit.head.split(":").last
        val entityRef = UUID.fromString(entityTypeAndRefSplit.last)
        Entity.fromType[F](entityType, entityRef, None, deleted = false)
      })
      .sequence

    private def getClosureResultIndexNames(token: String): F[ClosureResultIndexNames] = {
      val definitionName = "closure-result-index-definition"
      val queryParams = Map("name" -> definitionName, "type" -> "CustomIndexDefinition")
      val apiUri = uri"$apiBaseUrl/api/admin/documents?$queryParams"
      for {
        documents <- getApiResponseXml(apiUri.toString(), token)
        apiId <- me.fromOption(
          dataProcessor.existingApiId(documents, "Document", definitionName),
          PreservicaClientException(s"Cannot find index definition $definitionName")
        )
        definitionContent <- getApiResponseXml(s"$apiBaseUrl/api/admin/documents/$apiId/content", token)
        indexNames <- dataProcessor.closureResultIndexNames(definitionContent)
      } yield indexNames
    }

    private def search(
        start: Int,
        token: String,
        indexNames: ClosureResultIndexNames,
        ids: List[String]
    ): F[List[Entity]] = {
      val max = 100
      basicRequest
        .get(searchUrl(start, max, indexNames))
        .headers(Map("Preservica-Access-Token" -> token))
        .response(asJson[SearchResponse])
        .send(backend)
        .flatMap(res => me.fromEither(res.body))
        .flatMap(searchResponse =>
          if (searchResponse.value.objectIds.isEmpty) {
            toEntities(ids)
          } else {
            search(start + max, token, indexNames, searchResponse.value.objectIds ++ ids)
          }
        )
    }

    private def searchUrl(start: Int, max: Int, indexNames: ClosureResultIndexNames) = {
      val documentStatusField = SearchField(indexNames.documentStatusName, "Closed" :: Nil)
      val toDateTime = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
      val reviewDateField =
        SearchField(indexNames.reviewDateName, s"[2000-01-01T00:00:00.000Z TO $toDateTime]" :: Nil)
      val searchQuery = SearchQuery("", List(documentStatusField, reviewDateField))
      val queryString = write(searchQuery)
      val queryParams = Map(
        "q" -> queryString,
        "start" -> start.toString,
        "max" -> max.toString,
        "metadata" -> indexNames.documentStatusName
      )
      uri"$apiBaseUrl/api/content/search?$queryParams"
    }

    override def findExpiredClosedDocuments(secretName: String): F[List[Entity]] = {
      for {
        token <- getAuthenticationToken(secretName)
        indexNames <- getClosureResultIndexNames(token)
        res <- search(0, token, indexNames, Nil)
      } yield res
    }
  }
}
