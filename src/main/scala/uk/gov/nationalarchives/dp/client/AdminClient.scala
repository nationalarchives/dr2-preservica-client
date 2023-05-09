package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect._
import cats.implicits._
import sttp.client3._
import sttp.model.Method
import uk.gov.nationalarchives.dp.client.Utils._
import uk.gov.nationalarchives.dp.client.FileInfo._

import scala.concurrent.duration.FiniteDuration

trait AdminClient[F[_]] {

  def addOrUpdateSchemas(fileInfo: List[SchemaFileInfo], authDetails: AuthDetails): F[Unit]

  def addOrUpdateTransforms(fileInfo: List[TransformFileInfo], authDetails: AuthDetails): F[Unit]

  def addOrUpdateIndexDefinitions(
      fileInfo: List[IndexDefinitionInfo],
      authDetails: AuthDetails
  ): F[Unit]

  def addOrUpdateMetadataTemplates(
      fileInfo: List[MetadataTemplateInfo],
      authDetails: AuthDetails
  ): F[Unit]
}
object AdminClient {
  def createAdminClient[F[_], S](
      apiBaseUrl: String,
      backend: SttpBackend[F, S],
      duration: FiniteDuration
  )(implicit
      me: MonadError[F, Throwable],
      sync: Sync[F]
  ): AdminClient[F] = new AdminClient[F] {

    val utils: Utils[F, S] = Utils(apiBaseUrl, backend, duration)
    import utils._

    private def deleteDocument(path: String, apiId: String, token: String): F[Unit] = {
      val url = uri"$apiBaseUrl/api/admin/$path/$apiId"
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
      val url = uri"$apiBaseUrl/api/admin/$path?$queryParams"
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
        authDetails: AuthDetails,
        path: String,
        elementName: String
    ) = for {
      token <- getAuthenticationToken(authDetails)
      res <- getApiResponseXml(s"$apiBaseUrl/api/admin/$path", token)
      _ <- fileInfo.map { info =>
        val deleteIfPresent = dataProcessor.existingApiId(res, elementName, info.name) match {
          case Some(id) => deleteDocument(path, id, token)
          case None     => me.unit
        }
        me.flatMap(deleteIfPresent)(_ => createSchema(path, info.toQueryParams, info.xmlData, token))
      }.sequence
    } yield ()

    override def addOrUpdateSchemas(
        fileInfo: List[SchemaFileInfo],
        authDetails: AuthDetails
    ): F[Unit] = updateFiles(fileInfo, authDetails, "schemas", "Schema")

    override def addOrUpdateTransforms(
        fileInfo: List[TransformFileInfo],
        authDetails: AuthDetails
    ): F[Unit] = updateFiles(fileInfo, authDetails, "transforms", "Transform")

    override def addOrUpdateIndexDefinitions(
        fileInfo: List[IndexDefinitionInfo],
        authDetails: AuthDetails
    ): F[Unit] = updateFiles(fileInfo, authDetails, "documents", "Document")

    override def addOrUpdateMetadataTemplates(
        fileInfo: List[MetadataTemplateInfo],
        authDetails: AuthDetails
    ): F[Unit] = updateFiles(fileInfo, authDetails, "documents", "Document")
  }
}
