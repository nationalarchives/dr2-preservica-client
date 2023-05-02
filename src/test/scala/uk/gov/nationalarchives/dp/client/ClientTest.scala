package uk.gov.nationalarchives.dp.client

import cats.MonadError
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatest.{Assertion, BeforeAndAfterEach}
import sttp.capabilities.Streams
import uk.gov.nationalarchives.dp.client.Client.AuthDetails
import uk.gov.nationalarchives.dp.client.Entity.fromType

import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID
import scala.jdk.CollectionConverters._

abstract class ClientTest[F[_], S](port: Int, stream: Streams[S])(implicit
    cme: MonadError[F, Throwable]
) extends AnyFlatSpec
    with BeforeAndAfterEach {

  def valueFromF[T](value: F[T]): T

  def createClient(url: String): F[Client[F, S]]

  def testClient(url: String): Client[F, S] = valueFromF(createClient(url))

  override def beforeEach(): Unit = {
    preservicaServer.start()
    preservicaServer.resetAll()
  }

  override def afterEach(): Unit = preservicaServer.stop()

  val preservicaServer = new WireMockServer(port)

  def checkServerCall(url: String): Assertion =
    preservicaServer.getAllServeEvents.asScala.count(_.getRequest.getUrl == url) should equal(1)

  val authDetails: AuthDetails = AuthDetails("user", "password")
  val tokenResponse: String = """{"token": "abcde"}"""
  val tokenUrl = "/api/accesstoken/login"

  "getBitstreamInfo" should "call the correct API endpoints and return the bitstream info" in {
    val id = UUID.randomUUID()
    val fileName = "test.txt"

    val entityUrl = s"/api/entity/content-objects/$id"
    val generationsUrl = s"/api/entity/content-objects/$id/generations"
    val generationUrl = s"/api/entity/content-objects/$id/generations/1"
    val bitstreamUrl = s"api/entity/content-objects/$id/generations/1/bitstreams/1"

    val entityResponse =
      <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <AdditionalInformation>
        <Generations>http://localhost:{port}{generationsUrl}</Generations>
      </AdditionalInformation>
    </EntityResponse>.toString()
    val generationsResponse =
      <GenerationsResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <Generations>
        <Generation active="true">http://localhost:{port}{generationUrl}</Generation>
      </Generations>
    </GenerationsResponse>.toString()
    val generationResponse =
      <GenerationResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <Bitstreams>
        <Bitstream filename="test.txt">http://localhost:{port}{bitstreamUrl}</Bitstream>
      </Bitstreams>
    </GenerationResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))
    preservicaServer.stubFor(get(urlEqualTo(generationsUrl)).willReturn(ok(generationsResponse)))
    preservicaServer.stubFor(get(urlEqualTo(generationUrl)).willReturn(ok(generationResponse)))

    val client = testClient(s"http://localhost:$port")
    val response: F[Seq[Client.BitStreamInfo]] = client.getBitstreamInfo(id, authDetails)

    val bitStreamInfo = valueFromF(response).head
    bitStreamInfo.url should equal(s"http://localhost:$port$bitstreamUrl")
    bitStreamInfo.name should equal(fileName)

    checkServerCall(entityUrl)
    checkServerCall(generationsUrl)
    checkServerCall(generationUrl)
  }

  "getBitstreamInfo" should "call the correct API endpoints and return the bitstream info for multiple generations" in {
    val id = UUID.randomUUID()

    val entityUrl = s"/api/entity/content-objects/$id"
    val generationsUrl = s"/api/entity/content-objects/$id/generations"
    val generationOneUrl = s"/api/entity/content-objects/$id/generations/1"
    val generationTwoUrl = s"/api/entity/content-objects/$id/generations/2"
    val bitstreamOneUrl = s"api/entity/content-objects/$id/generations/1/bitstreams/1"
    val bitstreamTwoUrl = s"api/entity/content-objects/$id/generations/2/bitstreams/2"

    val entityResponse =
      <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <AdditionalInformation>
        <Generations>http://localhost:{port}{generationsUrl}</Generations>
      </AdditionalInformation>
    </EntityResponse>.toString()
    val generationsResponse =
      <GenerationsResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <Generations>
        <Generation active="true">http://localhost:{port}{generationOneUrl}</Generation>
        <Generation active="true">http://localhost:{port}{generationTwoUrl}</Generation>
      </Generations>
    </GenerationsResponse>.toString()
    val generationOneResponse =
      <GenerationResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <Bitstreams>
        <Bitstream filename="test1.txt">http://localhost:{port}{bitstreamOneUrl}</Bitstream>
      </Bitstreams>
    </GenerationResponse>.toString()

    val generationTwoResponse =
      <GenerationResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <Bitstreams>
        <Bitstream filename="test2.txt">http://localhost:{port}{bitstreamTwoUrl}</Bitstream>
      </Bitstreams>
    </GenerationResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))
    preservicaServer.stubFor(get(urlEqualTo(generationsUrl)).willReturn(ok(generationsResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(generationOneUrl)).willReturn(ok(generationOneResponse))
    )
    preservicaServer.stubFor(
      get(urlEqualTo(generationTwoUrl)).willReturn(ok(generationTwoResponse))
    )

    val client = testClient(s"http://localhost:$port")
    val response: F[Seq[Client.BitStreamInfo]] = client.getBitstreamInfo(id, authDetails)

    val bitStreamInfo = valueFromF(response)
    bitStreamInfo.size should equal(2)
    bitStreamInfo.head.url should equal(s"http://localhost:$port$bitstreamOneUrl")
    bitStreamInfo.head.name should equal("test1.txt")

    bitStreamInfo.last.url should equal(s"http://localhost:$port$bitstreamTwoUrl")
    bitStreamInfo.last.name should equal("test2.txt")

    checkServerCall(entityUrl)
    checkServerCall(generationsUrl)
    checkServerCall(generationOneUrl)
    checkServerCall(generationTwoUrl)
  }

  "getBitstreamInfo" should "return an error if no generations are available" in {
    val id = UUID.randomUUID()

    val entityUrl = s"/api/entity/content-objects/$id"

    val entityResponse =
      <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <AdditionalInformation>
      </AdditionalInformation>
    </EntityResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))

    val client = testClient(s"http://localhost:$port")
    val response: F[Seq[Client.BitStreamInfo]] = client.getBitstreamInfo(id, authDetails)

    val expectedError = valueFromF(cme.attempt(response))

    expectedError.isLeft should be(true)
    expectedError.left.map(err => {
      err.getMessage should equal("Generation not found")
    })
  }

  "getBitstreamInfo" should "return an error if the server is unavailable" in {
    val tokenUrl = "/api/accesstoken/login"
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(serverError()))

    val client = testClient(s"http://localhost:$port")
    val response = client.getBitstreamInfo(UUID.randomUUID(), authDetails)

    val expectedError = valueFromF(cme.attempt(response))

    expectedError.isLeft should be(true)
    expectedError.left.map(err => {
      err.getMessage should equal("statusCode: 500, response: ")
    })
  }

  "streamBitstreamContent" should "stream content to the provided function" in {
    val url = s"http://localhost:$port"
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo("/bitstream/1")).willReturn(ok("test")))
    val client = testClient(url)

    val response = client.streamBitstreamContent[String](stream)(
      s"$url/bitstream/1",
      authDetails,
      _ => cme.pure(s"Test return value")
    )
    val responseValue = valueFromF(response)
    responseValue should equal("Test return value")
  }

  "streamBitstreamContent" should "return an error if the server is unavailable" in {
    val url = s"http://localhost:$port"
    val tokenUrl = "/api/accesstoken/login"
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(serverError()))

    val client = testClient(s"http://localhost:$port")
    val response =
      client.streamBitstreamContent[Unit](stream)(s"$url/bitstream/1", authDetails, _ => cme.unit)

    val expectedError = valueFromF(cme.attempt(response))

    expectedError.isLeft should be(true)
    expectedError.left.map(err => {
      err.getMessage should equal("statusCode: 500, response: ")
    })
  }

  "metadataForEntityUrl" should "return a single fragment when the object has one fragment" in {
    val url = s"http://localhost:$port"
    val entityId = UUID.randomUUID()
    val entity = valueFromF(fromType("IO", entityId, "title", deleted = false))
    val entityUrl = s"/api/entity/${entity.path}/${entity.id}"
    val fragmentOneUrl = s"/api/entity/information-objects/$entityId/metadata/${UUID.randomUUID()}"
    val entityResponse =
      <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <AdditionalInformation>
        <Metadata>
          <Fragment>{s"$url$fragmentOneUrl"}</Fragment>
        </Metadata>
      </AdditionalInformation>
    </EntityResponse>.toString

    val fragmentOneContent = <Test1>
      <Test1Value>Test1Value</Test1Value>
    </Test1>
    val fragmentOneResponse =
      <MetadataResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <MetadataContainer>
        <Content>
          {fragmentOneContent}
        </Content>
      </MetadataContainer>
    </MetadataResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(fragmentOneUrl)).willReturn(ok(fragmentOneResponse.toString))
    )

    val client = testClient(url)

    val res = client.metadataForEntity(entity, authDetails)
    val metadata = valueFromF(res)

    metadata.size should equal(1)
    metadata.head should equal(fragmentOneContent)

    checkServerCall(entityUrl)
    checkServerCall(fragmentOneUrl)
  }

  "metadataForEntityUrl" should "return a multiple fragments when the object has multiple fragments" in {
    val url = s"http://localhost:$port"
    val entityId = UUID.randomUUID()
    val entity = valueFromF(fromType("IO", entityId, "title", deleted = false))
    val entityUrl = s"/api/entity/${entity.path}/${entity.id}"
    val fragmentOneUrl = s"/api/entity/information-objects/$entityId/metadata/${UUID.randomUUID()}"
    val fragmentTwoUrl = s"/api/entity/information-objects/$entityId/metadata/${UUID.randomUUID()}"
    val entityResponse =
      <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <AdditionalInformation>
        <Metadata>
          <Fragment>{s"$url$fragmentOneUrl"}</Fragment>
          <Fragment>{s"$url$fragmentTwoUrl"}</Fragment>
        </Metadata>
      </AdditionalInformation>
    </EntityResponse>.toString

    val fragmentOneContent = <Test1>
      <Test1Value>Test1Value</Test1Value>
    </Test1>
    val fragmentOneResponse =
      <MetadataResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <MetadataContainer>
        <Content>
          {fragmentOneContent}
        </Content>
      </MetadataContainer>
    </MetadataResponse>

    val fragmentTwoContent = <Test2>
      <Test2Value>Test2Value</Test2Value>
    </Test2>
    val fragmentTwoResponse =
      <MetadataResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <MetadataContainer>
        <Content>
          {fragmentTwoContent}
        </Content>
      </MetadataContainer>
    </MetadataResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(fragmentOneUrl)).willReturn(ok(fragmentOneResponse.toString))
    )
    preservicaServer.stubFor(
      get(urlEqualTo(fragmentTwoUrl)).willReturn(ok(fragmentTwoResponse.toString))
    )

    val client = testClient(url)

    val res = client.metadataForEntity(entity, authDetails)
    val metadata = valueFromF(res)

    metadata.size should equal(2)
    metadata.head should equal(fragmentOneContent)
    metadata.last should equal(fragmentTwoContent)

    checkServerCall(entityUrl)
    checkServerCall(fragmentOneUrl)
    checkServerCall(fragmentTwoUrl)
  }

  "metadataForEntityUrl" should "return an empty list when the object has no fragments" in {
    val url = s"http://localhost:$port"
    val entityId = UUID.randomUUID()
    val entity = valueFromF(fromType("IO", entityId, "title", deleted = false))
    val entityUrl = s"/api/entity/${entity.path}/${entity.id}"
    val entityResponse =
      <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <AdditionalInformation>
        <Metadata>
        </Metadata>
      </AdditionalInformation>
    </EntityResponse>.toString

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))

    val client = testClient(url)

    val res = client.metadataForEntity(entity, authDetails)
    val metadata = valueFromF(res)

    metadata.size should equal(0)

    checkServerCall(entityUrl)
  }

  "metadataForEntityUrl" should "return an error if the server is unavailable" in {
    val url = s"http://localhost:$port"
    val tokenUrl = "/api/accesstoken/login"
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(serverError()))
    val entity = valueFromF(fromType("IO", UUID.randomUUID(), "title", deleted = false))
    val client = testClient(s"http://localhost:$port")
    val response = client.metadataForEntity(entity, authDetails)

    val expectedError = valueFromF(cme.attempt(response))

    expectedError.isLeft should be(true)
    expectedError.left.map(err => {
      err.getMessage should equal("statusCode: 500, response: ")
    })
  }

  "entitiesUpdatedSince" should "return paginated values" in {
    val date = ZonedDateTime.of(2023, 4, 25, 0, 0, 0, 0, ZoneId.of("UTC"))
    val firstPage = <EntitiesResponse>
      <Entities>
        <Entity title="page1File.txt" ref="8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91" type="IO">http://localhost/page1/object</Entity>
      </Entities>
      <Paging>
        <Next>http://localhost:{
      port
    }/api/entity/entities/updated-since?date=2023-04-25T00%3A00%3A00.000Z&amp;start=100&amp;max=100</Next>
      </Paging>
    </EntitiesResponse>
    val secondPage = <EntitiesResponse>
      <Entities>
        <Entity title="page2File.txt" ref="6ca62825-4225-4dad-ac93-1d018bade02f" type="SO" deleted="true">http://localhost/page2/object</Entity>
      </Entities>
      <Paging>
      </Paging>
    </EntitiesResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/entities/updated-since"))
        .withQueryParams(
          Map(
            "date" -> equalTo("2023-04-25T00:00:00.000Z"),
            "max" -> equalTo("100"),
            "start" -> equalTo("0")
          ).asJava
        )
        .willReturn(ok(firstPage.toString()))
    )
    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/entities/updated-since"))
        .withQueryParams(
          Map(
            "date" -> equalTo("2023-04-25T00:00:00.000Z"),
            "max" -> equalTo("100"),
            "start" -> equalTo("100")
          ).asJava
        )
        .willReturn(ok(secondPage.toString()))
    )
    val client = testClient(s"http://localhost:$port")
    val response = valueFromF(client.entitiesUpdatedSince(date, authDetails))

    val pageOne = response.head
    val pageTwo = response.last

    pageOne.id.toString should equal("8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91")
    pageOne.path should equal("information-objects")
    pageOne.title should be("page1File.txt")
    pageOne.deleted should be(false)

    pageTwo.id.toString should equal("6ca62825-4225-4dad-ac93-1d018bade02f")
    pageTwo.path should equal("structural-objects")
    pageTwo.title should be("page2File.txt")
    pageTwo.deleted should be(true)
  }

  "entitiesUpdatedSince" should "return an empty list if none have been updated" in {
    val date = ZonedDateTime.of(2023, 4, 25, 0, 0, 0, 0, ZoneId.of("UTC"))
    val input = <EntitiesResponse>
      <Entities></Entities>
    </EntitiesResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/entities/updated-since"))
        .withQueryParams(
          Map(
            "date" -> equalTo("2023-04-25T00:00:00.000Z"),
            "max" -> equalTo("100"),
            "start" -> equalTo("0")
          ).asJava
        )
        .willReturn(ok(input.toString()))
    )

    val client = testClient(s"http://localhost:$port")
    val response = valueFromF(client.entitiesUpdatedSince(date, authDetails))

    response.size should equal(0)
  }
}
