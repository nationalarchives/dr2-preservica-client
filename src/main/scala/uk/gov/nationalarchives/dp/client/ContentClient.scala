package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Async
import cats.implicits.*
import io.circe.{Decoder, Printer}
import io.circe.generic.auto.*
import io.circe.syntax.*
import sttp.client3.*
import sttp.client3.circe.asJson
import sttp.model.Uri
import uk.gov.nationalarchives.dp.client.Client.ClientConfig
import uk.gov.nationalarchives.dp.client.ContentClient.SearchQuery
import uk.gov.nationalarchives.dp.client.Entities.*

import java.util.UUID

/** A client to search for entities in Preservica
  * @tparam F
  *   Type of the effect
  */
trait ContentClient[F[_]]:

  /** @param searchQuery
    *   The search query to use
    * @param max
    *   The maximum number of results to return. Defaults to 100
    * @return
    *   A list of `Entity` objects wrapped in the F effect
    */
  def searchEntities(searchQuery: SearchQuery, max: Int = 100): F[List[Entity]]

/** An object containing a method which returns an implementation of the ContentClient trait
  */
object ContentClient:

  /** Represents a field to be returned by the search query
    * @param name
    *   The name of the field
    * @param values
    *   The values to be returned. This is not used but is needed by Preservica
    */
  case class SearchField(name: String, values: List[String])

  /** Represents a complete search request
    * @param q
    *   A string containing the search query
    * @param fields
    *   A list of [[SearchField]]
    */
  case class SearchQuery(q: String, fields: List[SearchField])

  /** Creates a new `ContentClient` instance.
    * @param clientConfig
    *   Configuration parameters needed to create the client
    * @param me
    *   An implicit instance of cats.MonadError
    * @param sync
    *   An implicit instance of cats.Sync
    * @tparam F
    *   The type of the effect
    * @tparam S
    *   The type of the Stream to be used for the streaming methods.
    * @return
    */
  def createContentClient[F[_], S](clientConfig: ClientConfig[F, S])(using
      me: MonadError[F, Throwable],
      sync: Async[F]
  ): ContentClient[F] = new ContentClient[F]:
    case class SearchResponseValue(objectIds: List[String], totalHits: Int)

    case class SearchResponse(success: Boolean, value: SearchResponseValue)

    private val client: Client[F, S] = Client(clientConfig)
    import client.*

    def toEntities(entityInfo: List[String]): F[List[Entity]] = entityInfo
      .map(info =>
        val entityTypeAndRefSplit = info.split("\\|")
        val entityType = entityTypeAndRefSplit.head.split(":").last
        val entityRef = UUID.fromString(entityTypeAndRefSplit.last)
        fromType[F](entityType, entityRef, None, None, deleted = false)
      )
      .sequence

    private def search(
        start: Int,
        token: String,
        searchQuery: SearchQuery,
        ids: List[String]
    ): F[List[Entity]] =
      val max = 100
      basicRequest
        .get(searchUri(start, max, searchQuery))
        .headers(Map("Preservica-Access-Token" -> token))
        .response(asJson[SearchResponse])
        .send(backend)
        .flatMap(res => me.fromEither(res.body))
        .flatMap(searchResponse =>
          if searchResponse.value.objectIds.isEmpty then toEntities(ids)
          else search(start + max, token, searchQuery, searchResponse.value.objectIds ++ ids)
        )

    private def searchUri(start: Int, max: Int, searchQuery: SearchQuery): Uri =
      val queryString = searchQuery.asJson.printWith(Printer.noSpaces)
      val queryParams = Map(
        "q" -> queryString,
        "start" -> start.toString,
        "max" -> max.toString,
        "metadata" -> searchQuery.fields.map(_.name).mkString(",")
      )
      uri"$apiBaseUrl/api/content/search?$queryParams"

    override def searchEntities(searchQuery: SearchQuery, max: Int = 100): F[List[Entity]] =
      for
        token <- getAuthenticationToken
        res <- search(0, token, searchQuery, Nil)
      yield res
