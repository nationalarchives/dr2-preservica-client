package uk.gov.nationalarchives.dp.client

import cats.MonadError
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.admin.model.ServeEventQuery
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor4}
import uk.gov.nationalarchives.dp.client.FileInfo._
import uk.gov.nationalarchives.dp.client.Client.AuthDetails

import scala.jdk.CollectionConverters._
import scala.xml.{Elem, Node}

abstract class AdminClientTest[F[_]](port: Int)(implicit
    cme: MonadError[F, Throwable]
) extends AnyFlatSpec
    with BeforeAndAfterEach
    with TableDrivenPropertyChecks {

  def valueFromF[T](value: F[T]): T

  def createClient(url: String): F[AdminClient[F]]

  val preservicaServer = new WireMockServer(port)

  override def beforeEach(): Unit = {
    preservicaServer.resetAll()
    preservicaServer.start()
  }

  override def afterEach(): Unit = {
    preservicaServer.stop()
  }

  val client: AdminClient[F] = valueFromF(createClient(s"http://localhost:$port"))

  val testXmlData: Elem = <Test></Test>
  def schemaResponse(fileName: String): Elem = <Schemas><Schema><Name>{
    fileName
  }</Name><ApiId>1</ApiId></Schema></Schemas>
  def transformResponse(fileName: String): Elem = <Transforms><Transform><Name>{
    fileName
  }</Name><ApiId>1</ApiId></Transform></Transforms>
  def documentResponse(fileName: String): Elem = <Documents><Document><Name>{
    fileName
  }</Name><ApiId>1</ApiId></Document></Documents>

  val schemaFileInfo: SchemaFileInfo =
    SchemaFileInfo("test-file-name", "description", "test-file-name.xsd", testXmlData.toString)
  val transformFileInfo: TransformFileInfo = TransformFileInfo(
    "test-file-name",
    "http://test/from",
    "http://test/to",
    "view",
    "test-file-name.xsl",
    testXmlData.toString
  )
  val indexDefinitionInfo: IndexDefinitionInfo =
    IndexDefinitionInfo("test-file-name", testXmlData.toString)
  val metadataTemplateInfo: MetadataTemplateInfo =
    MetadataTemplateInfo("test-file-name", testXmlData.toString)

  val authDetails: AuthDetails = AuthDetails("test", "test")
  val tokenResponse: String = """{"token": "abcde"}"""
  val tokenUrl = "/api/accesstoken/login"

  val testResponse = "<Test></Test>"

  val t: TableFor4[String, String, String => Node, FileInfo] = Table(
    ("methodName", "path", "response", "input"),
    ("addOrUpdateSchemas", "schemas", schemaResponse, schemaFileInfo),
    ("addOrUpdateTransforms", "transforms", transformResponse, transformFileInfo),
    ("addOrUpdateMetadataTemplates", "documents", documentResponse, metadataTemplateInfo),
    ("addOrUpdateIndexDefinitions", "documents", documentResponse, indexDefinitionInfo)
  )

  forAll(t)((methodName, path, response, input) => {
    val result = input match {
      case i: IndexDefinitionInfo   => client.addOrUpdateIndexDefinitions(i :: Nil, authDetails)
      case mt: MetadataTemplateInfo => client.addOrUpdateMetadataTemplates(mt :: Nil, authDetails)
      case s: SchemaFileInfo        => client.addOrUpdateSchemas(s :: Nil, authDetails)
      case t: TransformFileInfo     => client.addOrUpdateTransforms(t :: Nil, authDetails)
    }

    val url = s"/api/admin/$path"

    methodName should "call the delete endpoint if the name already exists" in {
      val deleteMapping = delete(urlPathMatching(s"$url/1")).willReturn(ok(testResponse))
      preservicaServer.stubFor(post(urlPathMatching(tokenUrl)).willReturn(ok(tokenResponse)))
      preservicaServer.stubFor(get(urlPathMatching(url)).willReturn(ok(response(input.name).toString())))
      preservicaServer.stubFor(deleteMapping)
      preservicaServer.stubFor(post(urlPathMatching(url)).willReturn(ok(testResponse)))

      valueFromF(result)
      val events =
        preservicaServer.getServeEvents(ServeEventQuery.forStubMapping(deleteMapping.build())).getServeEvents.asScala

      events.size should equal(1)
    }

    methodName should "not call the delete endpoint if the name doesn't exist" in {
      val deleteMapping = delete(urlPathMatching(s"$url/1")).willReturn(ok(testResponse))
      preservicaServer.stubFor(post(urlPathMatching(tokenUrl)).willReturn(ok(tokenResponse)))
      preservicaServer.stubFor(get(urlPathMatching(url)).willReturn(ok(response("a-different-name").toString())))
      preservicaServer.stubFor(deleteMapping)
      preservicaServer.stubFor(post(urlPathMatching(url)).willReturn(ok(testResponse)))

      valueFromF(result)
      val events =
        preservicaServer.getServeEvents(ServeEventQuery.forStubMapping(deleteMapping.build())).getServeEvents.asScala

      events.size should equal(0)
    }

    methodName should "create the schema with the correct parameters" in {
      val queryParams = input.toQueryParams.view.mapValues(equalTo).toMap.asJava
      val postMapping = post(urlPathMatching(url))
        .withQueryParams(queryParams)
        .withRequestBody(equalTo(input.xmlData))
        .willReturn(okXml("<Response></Response>"))
      preservicaServer.stubFor(post(urlPathMatching(tokenUrl)).willReturn(ok(tokenResponse)))
      preservicaServer.stubFor(get(urlPathMatching(url)).willReturn(ok(response(input.name).toString())))
      preservicaServer.stubFor(delete(urlPathMatching(s"$url/1")).willReturn(ok()))
      preservicaServer.stubFor(postMapping)

      valueFromF(result)
      val events =
        preservicaServer.getServeEvents(ServeEventQuery.forStubMapping(postMapping.build())).getServeEvents.asScala

      events.size should equal(1)
      val params = events.head.getRequest.getQueryParams
      params.get("name").containsValue("test-file-name") should be(true)
      params.get("original")
    }

    methodName should "return an error if the token api call returns an error" in {
      preservicaServer.stubFor(post(urlPathMatching(tokenUrl)).willReturn(serverError()))
      val error = intercept[PreservicaClientException](valueFromF(result))
      error.getMessage should equal(
        s"Status code 500 calling http://localhost:$port/api/accesstoken/login with method POST statusCode: 500, response: "
      )
    }

    methodName should "return an error if the schema get call returns an error" in {
      preservicaServer.stubFor(post(urlPathMatching(tokenUrl)).willReturn(ok(tokenResponse)))
      preservicaServer.stubFor(get(urlPathMatching(url)).willReturn(serverError()))
      val error = intercept[PreservicaClientException](valueFromF(result))
      error.getMessage should equal(s"Status code 500 calling http://localhost:$port/api/admin/$path with method GET ")
    }

    methodName should "return an error if the delete call returns an error" in {
      preservicaServer.stubFor(post(urlPathMatching(tokenUrl)).willReturn(ok(tokenResponse)))
      preservicaServer.stubFor(get(urlPathMatching(url)).willReturn(ok(response(input.name).toString())))
      preservicaServer.stubFor(delete(urlPathMatching(s"$url/1")).willReturn(serverError()))

      val error = intercept[PreservicaClientException](valueFromF(result))
      error.getMessage should equal(
        s"Status code 500 calling http://localhost:$port/api/admin/$path/1 with method DELETE "
      )
    }

    methodName should "return an error if the create call returns an error" in {
      val queryParams = input.toQueryParams.view.mapValues(equalTo).toMap
      preservicaServer.stubFor(post(urlPathMatching(tokenUrl)).willReturn(ok(tokenResponse)))
      preservicaServer.stubFor(get(urlPathMatching(url)).willReturn(ok(response("a-different-name").toString())))
      preservicaServer.stubFor(
        post(urlPathMatching(url))
          .withQueryParams(queryParams.asJava)
          .withRequestBody(equalTo(input.xmlData))
          .willReturn(serverError())
      )
      val error = intercept[PreservicaClientException](valueFromF(result))
      val paramsString = input.toQueryParams.map(param => s"${param._1}=${param._2}").mkString("&")
      error.getMessage should equal(
        s"Status code 500 calling http://localhost:$port/api/admin/$path?$paramsString with method POST "
      )
    }
  })

}
