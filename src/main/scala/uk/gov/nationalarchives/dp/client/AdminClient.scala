package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.*
import cats.implicits.*
import sttp.client3.*
import sttp.model.Method
import uk.gov.nationalarchives.dp.client.FileInfo.*
import uk.gov.nationalarchives.dp.client.Client.ClientConfig

/** A client to update schemas, transfers, index definitions and metadata templates in Preservica
  * @tparam F
  *   Type of the effect
  */
trait AdminClient[F[_]] {

  /** Add or update a schema document in Preservica
    * @param fileInfo
    *   A `List` of `SchemaFileInfo` containing the details of the schema updates
    * @return
    *   Unit wrapped in the F effect
    */
  def addOrUpdateSchemas(fileInfo: List[SchemaFileInfo]): F[Unit]

  /** Add or update a transform document in Preservica
    * @param fileInfo
    *   A `List` of `TransformFileInfo` containing the details of the transform updates
    * @return
    *   Unit wrapped in the F effect
    */
  def addOrUpdateTransforms(fileInfo: List[TransformFileInfo]): F[Unit]

  /** Add or update an index definition document in Preservica
    * @param fileInfo
    *   A `List` of `IndexDefinitionInfo` containing the details of the index definition updates
    * @return
    *   Unit wrapped in the F effect
    */
  def addOrUpdateIndexDefinitions(fileInfo: List[IndexDefinitionInfo]): F[Unit]

  /** Add or update an metadata template document in Preservica
    * @param fileInfo
    *   A `List` of `MetadataTemplateInfo` containing the details of the metadata template updates
    * @return
    *   Unit wrapped in the F effect
    */
  def addOrUpdateMetadataTemplates(fileInfo: List[MetadataTemplateInfo]): F[Unit]
}

/** An object containing a method which returns an implementation of the AdminClient trait
  */
object AdminClient {

  /** Creates a new `AdminClient` instance.
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
  def createAdminClient[F[_], S](clientConfig: ClientConfig[F, S])(using
      me: MonadError[F, Throwable],
      sync: Sync[F]
  ): AdminClient[F] = new AdminClient[F] {
    private val apiVersion = 7.0f
    private val client: Client[F, S] = Client(clientConfig)
    import client.*
    private val apiUrl = s"$apiBaseUrl/api/admin/v$apiVersion"

    private def deleteDocument(path: String, apiId: String, token: String): F[Unit] = {
      val url = uri"$apiUrl/$path/$apiId"
      backend
        .send {
          basicRequest.delete(url).headers(Map("Preservica-Access-Token" -> token))
        }
        .flatMap(res =>
          me.fromEither {
            res.body.left
              .map(err => PreservicaClientException(Method.DELETE, url, res.code, err))
              .map(_ => ())
          }
        )
    }

    private def createSchema(
        path: String,
        queryParams: Map[String, String],
        body: String,
        token: String
    ): F[Unit] = {
      val url = uri"$apiUrl/$path?$queryParams"
      backend
        .send(
          basicRequest
            .post(url)
            .headers(Map("Preservica-Access-Token" -> token, "Content-Type" -> "application/xml"))
            .body(body)
            .response(asXml)
        )
        .flatMap(res =>
          me.fromEither {
            res.body.left
              .map(err => PreservicaClientException(Method.POST, url, res.code, err))
              .map(_ => ())
          }
        )
    }

    private def updateFiles(
        fileInfo: List[FileInfo],
        path: String,
        elementName: String
    ) = for {
      token <- getAuthenticationToken
      res <- sendXMLApiRequest(s"$apiUrl/$path", token, Method.GET)
      _ <- fileInfo.map { info =>
        val deleteIfPresent = dataProcessor.existingApiId(res, elementName, info.name) match {
          case Some(id) => deleteDocument(path, id, token)
          case None     => me.unit
        }
        me.flatMap(deleteIfPresent)(_ => createSchema(path, info.toQueryParams, info.xmlData, token))
      }.sequence
    } yield ()

    override def addOrUpdateSchemas(fileInfo: List[SchemaFileInfo]): F[Unit] =
      updateFiles(fileInfo, "schemas", "Schema")

    override def addOrUpdateTransforms(fileInfo: List[TransformFileInfo]): F[Unit] =
      updateFiles(fileInfo, "transforms", "Transform")

    override def addOrUpdateIndexDefinitions(fileInfo: List[IndexDefinitionInfo]): F[Unit] =
      updateFiles(fileInfo, "documents", "Document")

    override def addOrUpdateMetadataTemplates(fileInfo: List[MetadataTemplateInfo]): F[Unit] =
      updateFiles(fileInfo, "documents", "Document")
  }
}
