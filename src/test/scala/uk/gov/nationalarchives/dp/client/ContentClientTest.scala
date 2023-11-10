package uk.gov.nationalarchives.dp.client

import cats.MonadError
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.admin.model.ServeEventQuery
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._
import uk.gov.nationalarchives.dp.client.ContentClient.{SearchField, SearchQuery}
import upickle.default
import upickle.default.{macroR, read}

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

  "search" should "search using the parameters passed to the method" in {
    preservicaServer.stubFor(post(urlPathMatching(tokenUrl)).willReturn(ok(tokenResponse)))
    val searchResponse = """{"success":true,"value":{"objectIds":[],"totalHits":1}}"""
    val searchMapping: MappingBuilder = get(urlPathMatching("/api/content/search"))
      .willReturn(okJson(searchResponse))
    preservicaServer.stubFor(searchMapping)
    val queryString = """{"q":"","fields":[{"name":"xip.title","values":["test-title"]}]}"""

    valueFromF(client.searchEntities(SearchQuery(queryString, SearchField("test1", List("value1")) :: Nil)))

    val events =
      preservicaServer.getServeEvents(ServeEventQuery.forStubMapping(searchMapping.build())).getServeEvents.asScala
    val searchQParam = events.head.getRequest.getQueryParams.get("q").values().get(0)
    implicit val searchFieldReader: default.Reader[SearchField] = macroR[SearchField]
    implicit val searchQueryReader: default.Reader[SearchQuery] = macroR[SearchQuery]
    val query = read[SearchQuery](searchQParam)
    val searchField = query.fields.head
    query.q should equal(queryString)
    searchField.name should equal("test1")
    searchField.values.head should equal("value1")
  }

  "search" should "call the API multiple times if there are multiple pages" in {
    preservicaServer.stubFor(post(urlPathMatching(tokenUrl)).willReturn(ok(tokenResponse)))
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

    valueFromF(client.searchEntities(SearchQuery("", Nil)))

    val firstEvents =
      preservicaServer.getServeEvents(ServeEventQuery.forStubMapping(firstSearchMapping.build())).getServeEvents.asScala
    val secondEvents = preservicaServer
      .getServeEvents(ServeEventQuery.forStubMapping(secondSearchMapping.build()))
      .getServeEvents
      .asScala

    firstEvents.size should equal(1)
    secondEvents.size should equal(1)
  }

  "search" should "return an error if the API returns an error" in {
    preservicaServer.stubFor(post(urlPathMatching(tokenUrl)).willReturn(ok(tokenResponse)))
    val searchMapping: MappingBuilder = get(urlPathMatching("/api/content/search"))
      .willReturn(serverError())
    preservicaServer.stubFor(searchMapping)

    val ex = intercept[Exception] {
      valueFromF(client.searchEntities(SearchQuery("", Nil)))
    }

    ex.getMessage should equal("statusCode: 500, response: ")
  }
}
