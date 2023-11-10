package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import sttp.client3._
import sttp.client3.upicklejson.asJson
import sttp.model.Uri
import uk.gov.nationalarchives.dp.client.Client.ClientConfig
import uk.gov.nationalarchives.dp.client.ContentClient.SearchQuery
import uk.gov.nationalarchives.dp.client.Entities._
import upickle.default._

import java.util.UUID

trait ContentClient[F[_]] {
  def searchEntities(searchQuery: SearchQuery, max: Int = 100): F[List[Entity]]
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
        fromType[F](entityType, entityRef, None, None, deleted = false)
      })
      .sequence

    private def search(
        start: Int,
        token: String,
        searchQuery: SearchQuery,
        ids: List[String]
    ): F[List[Entity]] = {
      val max = 100
      basicRequest
        .get(searchUri(start, max, searchQuery))
        .headers(Map("Preservica-Access-Token" -> token))
        .response(asJson[SearchResponse])
        .send(backend)
        .flatMap(res => me.fromEither(res.body))
        .flatMap(searchResponse =>
          if (searchResponse.value.objectIds.isEmpty) {
            toEntities(ids)
          } else {
            search(start + max, token, searchQuery, searchResponse.value.objectIds ++ ids)
          }
        )
    }

    private def searchUri(start: Int, max: Int, searchQuery: SearchQuery): Uri = {
      val queryString = write(searchQuery)
      val queryParams = Map(
        "q" -> queryString,
        "start" -> start.toString,
        "max" -> max.toString,
        "metadata" -> searchQuery.fields.map(_.name).mkString(",")
      )
      uri"$apiBaseUrl/api/content/search?$queryParams"
    }

    override def searchEntities(searchQuery: SearchQuery, max: Int = 100): F[List[Entity]] = {
      for {
        token <- getAuthenticationToken
        res <- search(0, token, searchQuery, Nil)
      } yield res
    }
  }
}
