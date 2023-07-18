package uk.gov.nationalarchives.dp.client

import cats.MonadError
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.admin.model.ServeEventQuery
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.matchers.should.Matchers._
import uk.gov.nationalarchives.dp.client.ContentClient.{SearchField, SearchQuery}
import upickle.default
import upickle.default._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

abstract class ContentClientTest[F[_]](preservicaPort: Int, secretsManagerPort: Int)(implicit
    cme: MonadError[F, Throwable]
) extends AnyFlatSpec
    with BeforeAndAfterEach {
  def valueFromF[T](value: F[T]): T

  def createClient(url: String): F[ContentClient[F]]

  val zeroSeconds: FiniteDuration = FiniteDuration(0, TimeUnit.SECONDS)
  val preservicaServer = new WireMockServer(preservicaPort)
  val secretsManagerServer = new WireMockServer(secretsManagerPort)

  val secretsResponse = """{"SecretString":"{\"username\":\"test\",\"password\":\"test\"}"}"""

  override def beforeEach(): Unit = {
    secretsManagerServer.resetAll()
    preservicaServer.resetAll()
    preservicaServer.start()
    secretsManagerServer.start()
    secretsManagerServer.stubFor(post(urlEqualTo("/")).willReturn(okJson(secretsResponse)))
  }

  override def afterEach(): Unit = {
    preservicaServer.stop()
    secretsManagerServer.stop()
  }

  val client: ContentClient[F] = valueFromF(createClient(s"http://localhost:$preservicaPort"))

  val tokenResponse: String = """{"token": "abcde"}"""
  val tokenUrl = "/api/accesstoken/login"

  "findExpiredClosedDocuments" should "search using the index names from the API" in {
    val documentResponse = <DocumentsResponse xmlns="http://preservica.com/AdminAPI/v6.5">
      <Documents>
        <Document>
          <Name>closure-result-index-definition</Name>
          <ApiId>1</ApiId>
        </Document>
      </Documents>
    </DocumentsResponse>
    val documentContentResponse = <index>
        <shortName>tns</shortName>
        <term indexName="test_date_index" indexType="DATE"/>
        <term indexName="test_string_index" indexType="STRING_DEFAULT"/>
      </index>
    preservicaServer.stubFor(post(urlPathMatching(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlPathMatching("/api/admin/documents")).willReturn(okXml(documentResponse.toString)))
    preservicaServer.stubFor(
      get(urlEqualTo("/api/admin/documents/1/content"))
        .willReturn(okXml(documentContentResponse.toString))
    )
    val searchResponse = """{"success":true,"value":{"objectIds":[],"totalHits":1}}"""
    val searchMapping: MappingBuilder = get(urlPathMatching("/api/content/search"))
      .willReturn(okJson(searchResponse))
    preservicaServer.stubFor(searchMapping)

    valueFromF(client.findExpiredClosedDocuments("secretName"))

    val events =
      preservicaServer.getServeEvents(ServeEventQuery.forStubMapping(searchMapping.build())).getServeEvents.asScala
    val searchQParam = events.head.getRequest.getQueryParams.get("q").values().get(0)
    implicit val searchFieldReader: default.Reader[SearchField] = macroR[SearchField]
    implicit val searchQueryReader: default.Reader[SearchQuery] = macroR[SearchQuery]
    val query = read[SearchQuery](searchQParam)
    val fieldNames = query.fields.map(_.name).sorted
    fieldNames.head should equal("tns.test_date_index")
    fieldNames.last should equal("tns.test_string_index")
  }

  "findExpiredClosedDocuments" should "return an error if the search index is not found" in {
    val documentResponse = <DocumentsResponse xmlns="http://preservica.com/AdminAPI/v6.5">
      <Documents>
        <Document>
          <Name>another-result-index-definition</Name>
          <ApiId>1</ApiId>
        </Document>
      </Documents>
    </DocumentsResponse>
    preservicaServer.stubFor(post(urlPathMatching(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlPathMatching("/api/admin/documents")).willReturn(okXml(documentResponse.toString)))

    val error = intercept[PreservicaClientException] {
      valueFromF(client.findExpiredClosedDocuments("secretName"))
    }
    error.getMessage should equal("Cannot find index definition closure-result-index-definition")
  }

  "findExpiredClosedDocuments" should "return an error if the search index contains the wrong field types" in {
    val documentResponse = <DocumentsResponse xmlns="http://preservica.com/AdminAPI/v6.5">
      <Documents>
        <Document>
          <Name>closure-result-index-definition</Name>
          <ApiId>1</ApiId>
        </Document>
      </Documents>
    </DocumentsResponse>
    val documentContentResponse = <index>
      <shortName>tns</shortName>
      <term indexName="test_date_index" indexType="ANOTHER_TYPE"/>
      <term indexName="test_string_index" indexType="A_DIFFERENT_TYPE"/>
    </index>
    preservicaServer.stubFor(post(urlPathMatching(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlPathMatching("/api/admin/documents")).willReturn(okXml(documentResponse.toString)))
    preservicaServer.stubFor(
      get(urlEqualTo("/api/admin/documents/1/content"))
        .willReturn(okXml(documentContentResponse.toString))
    )
    val error = intercept[PreservicaClientException] {
      valueFromF(client.findExpiredClosedDocuments("secretName"))
    }
    error.getMessage should equal("No review date index found for closure result")
  }

  "findExpiredClosedDocuments" should "call the API multiple times if there are multiple pages" in {
    val documentResponse = <DocumentsResponse xmlns="http://preservica.com/AdminAPI/v6.5">
      <Documents>
        <Document>
          <Name>closure-result-index-definition</Name>
          <ApiId>1</ApiId>
        </Document>
      </Documents>
    </DocumentsResponse>
    val documentContentResponse = <index>
      <shortName>tns</shortName>
      <term indexName="test_date_index" indexType="DATE"/>
      <term indexName="test_string_index" indexType="STRING_DEFAULT"/>
    </index>
    preservicaServer.stubFor(post(urlPathMatching(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlPathMatching("/api/admin/documents")).willReturn(okXml(documentResponse.toString)))
    preservicaServer.stubFor(
      get(urlEqualTo("/api/admin/documents/1/content"))
        .willReturn(okXml(documentContentResponse.toString))
    )
    val firstSearchResponse =
      """{"success":true,"value":{"objectIds":["sdb:IO|4d2813fb-bb00-4c07-bfb9-374104a347c6"],"totalHits":1}}"""
    val secondSearchResponse = """{"success":true,"value":{"objectIds":[],"totalHits":1}}"""
    val firstSearchMapping: MappingBuilder = get(urlPathMatching("/api/content/search"))
      .inScenario("RecursiveCall")
      .whenScenarioStateIs(STARTED)
      .willReturn(okJson(firstSearchResponse))
      .willSetStateTo("Next call")
    val secondSearchMapping: MappingBuilder = get(urlPathMatching("/api/content/search"))
      .inScenario("RecursiveCall")
      .whenScenarioStateIs("Next call")
      .willReturn(okJson(secondSearchResponse))

    preservicaServer.stubFor(firstSearchMapping)
    preservicaServer.stubFor(secondSearchMapping)

    valueFromF(client.findExpiredClosedDocuments("secretName"))

    val firstEvents =
      preservicaServer.getServeEvents(ServeEventQuery.forStubMapping(firstSearchMapping.build())).getServeEvents.asScala
    val secondEvents = preservicaServer
      .getServeEvents(ServeEventQuery.forStubMapping(secondSearchMapping.build()))
      .getServeEvents
      .asScala

    firstEvents.size should equal(1)
    secondEvents.size should equal(1)
  }
}
