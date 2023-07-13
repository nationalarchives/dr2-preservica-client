package uk.gov.nationalarchives.dp.client

import cats.MonadError
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatest.{Assertion, BeforeAndAfterEach}
import sttp.capabilities.Streams
import uk.gov.nationalarchives.dp.client.Entities.fromType
import uk.gov.nationalarchives.dp.client.Client._
import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID
import scala.jdk.CollectionConverters._

abstract class EntityClientTest[F[_], S](preservicaPort: Int, secretsManagerPort: Int, stream: Streams[S])(implicit
    cme: MonadError[F, Throwable]
) extends AnyFlatSpec
    with BeforeAndAfterEach {

  def valueFromF[T](value: F[T]): T

  def createClient(url: String): F[EntityClient[F, S]]

  def testClient(url: String): EntityClient[F, S] = valueFromF(createClient(url))

  val secretsManagerServer = new WireMockServer(secretsManagerPort)

  val secretsResponse = """{"SecretString":"{\"username\":\"test\",\"password\":\"test\"}"}"""

  override def beforeEach(): Unit = {
    preservicaServer.start()
    preservicaServer.resetAll()
    secretsManagerServer.start()
    secretsManagerServer.stubFor(post(urlEqualTo("/")).willReturn(okJson(secretsResponse)))
  }

  override def afterEach(): Unit = {
    preservicaServer.stop()
    secretsManagerServer.stop()
  }

  val preservicaServer = new WireMockServer(preservicaPort)

  def checkServerCall(url: String): Assertion =
    preservicaServer.getAllServeEvents.asScala.count(_.getRequest.getUrl == url) should equal(1)

  val secretName: String = "secretName"
  val tokenResponse: String = """{"token": "abcde"}"""
  val tokenUrl = "/api/accesstoken/login"

  val ref: UUID = UUID.randomUUID()
  val entityUrl = s"/api/entity/content-objects/$ref"

  "getBitstreamInfo" should "call the correct API endpoints and return the bitstream info" in {
    val fileName = "test.txt"
    val generationsUrl = s"/api/entity/content-objects/$ref/generations"
    val generationUrl = s"$generationsUrl/1"
    val bitstreamUrl = s"$generationUrl/bitstreams/1"

    val entityResponse =
      <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <AdditionalInformation>
        <Generations>http://localhost:{preservicaPort}{generationsUrl}</Generations>
      </AdditionalInformation>
    </EntityResponse>.toString()
    val generationsResponse =
      <GenerationsResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <Generations>
        <Generation active="true">http://localhost:{preservicaPort}{generationUrl}</Generation>
      </Generations>
    </GenerationsResponse>.toString()
    val generationResponse =
      <GenerationResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <Bitstreams>
        <Bitstream filename="test.txt">http://localhost:{preservicaPort}{bitstreamUrl}</Bitstream>
      </Bitstreams>
    </GenerationResponse>.toString()
    val bitstreamResponse = <BitstreamResponse>
      <xip:Bitstream>
        <xip:Filename>{fileName}</xip:Filename>
        <xip:FileSize>1234</xip:FileSize>
      </xip:Bitstream>
      <AdditionalInformation>
        <Content>http://test</Content>
      </AdditionalInformation>
    </BitstreamResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))
    preservicaServer.stubFor(get(urlEqualTo(generationsUrl)).willReturn(ok(generationsResponse)))
    preservicaServer.stubFor(get(urlEqualTo(generationUrl)).willReturn(ok(generationResponse)))
    preservicaServer.stubFor(get(urlEqualTo(bitstreamUrl)).willReturn(ok(bitstreamResponse)))

    val client = testClient(s"http://localhost:$preservicaPort")
    val response: F[Seq[BitStreamInfo]] = client.getBitstreamInfo(ref, secretName)

    val bitStreamInfo = valueFromF(response).head
    bitStreamInfo.url should equal(s"http://test")
    bitStreamInfo.name should equal(fileName)

    checkServerCall(entityUrl)
    checkServerCall(generationsUrl)
    checkServerCall(generationUrl)
  }

  "getBitstreamInfo" should "call the correct API endpoints and return the bitstream info for multiple generations" in {
    val generationsUrl = s"/api/entity/content-objects/$ref/generations"
    val generationOneUrl = s"$generationsUrl/1"
    val generationTwoUrl = s"$generationsUrl/2"
    val bitstreamOneUrl = s"$generationOneUrl/bitstreams/1"
    val bitstreamTwoUrl = s"$generationTwoUrl/bitstreams/1"

    val entityResponse =
      <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <AdditionalInformation>
        <Generations>http://localhost:{preservicaPort}{generationsUrl}</Generations>
      </AdditionalInformation>
    </EntityResponse>.toString()
    val generationsResponse =
      <GenerationsResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <Generations>
        <Generation active="true">http://localhost:{preservicaPort}{generationOneUrl}</Generation>
        <Generation active="true">http://localhost:{preservicaPort}{generationTwoUrl}</Generation>
      </Generations>
    </GenerationsResponse>.toString()
    val generationOneResponse =
      <GenerationResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <Bitstreams>
        <Bitstream filename="test1.txt">http://localhost:{preservicaPort}{bitstreamOneUrl}</Bitstream>
      </Bitstreams>
    </GenerationResponse>.toString()

    val generationTwoResponse =
      <GenerationResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <Bitstreams>
        <Bitstream filename="test2.txt">http://localhost:{preservicaPort}{bitstreamTwoUrl}</Bitstream>
      </Bitstreams>
    </GenerationResponse>.toString()

    val bitstreamOneResponse = <BitstreamResponse>
      <xip:Bitstream>
        <xip:Filename>test1.txt</xip:Filename>
        <xip:FileSize>1234</xip:FileSize>
      </xip:Bitstream>
      <AdditionalInformation>
        <Content>http://test</Content>
      </AdditionalInformation>
    </BitstreamResponse>.toString()

    val bitstreamTwoResponse = <BitstreamResponse>
      <xip:Bitstream>
        <xip:Filename>test2.txt</xip:Filename>
        <xip:FileSize>1234</xip:FileSize>
      </xip:Bitstream>
      <AdditionalInformation>
        <Content>http://test</Content>
      </AdditionalInformation>
    </BitstreamResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))
    preservicaServer.stubFor(get(urlEqualTo(generationsUrl)).willReturn(ok(generationsResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(generationOneUrl)).willReturn(ok(generationOneResponse))
    )
    preservicaServer.stubFor(
      get(urlEqualTo(generationTwoUrl)).willReturn(ok(generationTwoResponse))
    )
    preservicaServer.stubFor(get(urlEqualTo(bitstreamOneUrl)).willReturn(ok(bitstreamOneResponse)))
    preservicaServer.stubFor(get(urlEqualTo(bitstreamTwoUrl)).willReturn(ok(bitstreamTwoResponse)))

    val client = testClient(s"http://localhost:$preservicaPort")
    val response: F[Seq[BitStreamInfo]] = client.getBitstreamInfo(ref, secretName)

    val bitStreamInfo = valueFromF(response)
    bitStreamInfo.size should equal(2)
    bitStreamInfo.head.url should equal(s"http://test")
    bitStreamInfo.head.name should equal("test1.txt")

    bitStreamInfo.last.url should equal(s"http://test")
    bitStreamInfo.last.name should equal("test2.txt")

    checkServerCall(entityUrl)
    checkServerCall(generationsUrl)
    checkServerCall(generationOneUrl)
    checkServerCall(generationTwoUrl)
  }

  "getBitstreamInfo" should "return an error if no generations are available" in {
    val entityResponse =
      <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <AdditionalInformation>
      </AdditionalInformation>
    </EntityResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))

    val client = testClient(s"http://localhost:$preservicaPort")
    val response: F[Seq[BitStreamInfo]] = client.getBitstreamInfo(ref, secretName)

    val expectedError = valueFromF(cme.attempt(response))

    expectedError.isLeft should be(true)
    expectedError.left.map(err => {
      err.getMessage should equal("Generation not found")
    })
  }

  "getBitstreamInfo" should "return an error if the server is unavailable" in {
    val tokenUrl = "/api/accesstoken/login"
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(serverError()))

    val client = testClient(s"http://localhost:$preservicaPort")
    val response = client.getBitstreamInfo(UUID.randomUUID(), secretName)

    val expectedError = valueFromF(cme.attempt(response))

    expectedError.isLeft should be(true)
    expectedError.left.map(err => {
      err.getMessage should equal(
        s"Status code 500 calling http://localhost:$preservicaPort/api/accesstoken/login with method POST statusCode: 500, response: "
      )
    })
  }

  "streamBitstreamContent" should "stream content to the provided function" in {
    val url = s"http://localhost:$preservicaPort"
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo("/bitstream/1")).willReturn(ok("test")))
    val client = testClient(url)

    val response = client.streamBitstreamContent[String](stream)(
      s"$url/bitstream/1",
      secretName,
      _ => cme.pure(s"Test return value")
    )
    val responseValue = valueFromF(response)
    responseValue should equal("Test return value")
  }

  "streamBitstreamContent" should "return an error if the server is unavailable" in {
    val url = s"http://localhost:$preservicaPort"
    val tokenUrl = "/api/accesstoken/login"
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(serverError()))

    val client = testClient(s"http://localhost:$preservicaPort")
    val response =
      client.streamBitstreamContent[Unit](stream)(s"$url/bitstream/1", secretName, _ => cme.unit)

    val expectedError = valueFromF(cme.attempt(response))

    expectedError.isLeft should be(true)
    expectedError.left.map(err => {
      err.getMessage should equal(
        s"Status code 500 calling http://localhost:$preservicaPort/api/accesstoken/login with method POST statusCode: 500, response: "
      )
    })
  }

  "metadataForEntityUrl" should "return a single fragment when the object has one fragment" in {
    val url = s"http://localhost:$preservicaPort"
    val entityId = UUID.randomUUID()
    val entity = valueFromF(fromType("IO", entityId, Option("title"), deleted = false))
    val entityUrl = s"/api/entity/${entity.path}/${entity.ref}"
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

    val res = client.metadataForEntity(entity, secretName)
    val metadata = valueFromF(res)

    metadata.size should equal(1)
    metadata.head should equal(fragmentOneContent)

    checkServerCall(entityUrl)
    checkServerCall(fragmentOneUrl)
  }

  "metadataForEntityUrl" should "return a multiple fragments when the object has multiple fragments" in {
    val url = s"http://localhost:$preservicaPort"
    val entityId = UUID.randomUUID()
    val entity = valueFromF(fromType("IO", entityId, Option("title"), deleted = false))
    val entityUrl = s"/api/entity/${entity.path}/${entity.ref}"
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

    val res = client.metadataForEntity(entity, secretName)
    val metadata = valueFromF(res)

    metadata.size should equal(2)
    metadata.head should equal(fragmentOneContent)
    metadata.last should equal(fragmentTwoContent)

    checkServerCall(entityUrl)
    checkServerCall(fragmentOneUrl)
    checkServerCall(fragmentTwoUrl)
  }

  "metadataForEntityUrl" should "return an error when the object has no fragments" in {
    val url = s"http://localhost:$preservicaPort"
    val entityId = UUID.randomUUID()
    val entity = valueFromF(fromType("IO", entityId, Option("title"), deleted = false))
    val entityUrl = s"/api/entity/${entity.path}/${entity.ref}"
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

    val res = client.metadataForEntity(entity, secretName)
    val error = intercept[PreservicaClientException] {
      valueFromF(res)
    }
    error.getMessage should equal("No content found for elements:\n")

    checkServerCall(entityUrl)
  }

  "metadataForEntityUrl" should "return an error if the server is unavailable" in {
    val tokenUrl = "/api/accesstoken/login"
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(serverError()))
    val entity = valueFromF(fromType("IO", UUID.randomUUID(), Option("title"), deleted = false))
    val client = testClient(s"http://localhost:$preservicaPort")
    val response = client.metadataForEntity(entity, secretName)

    val expectedError = valueFromF(cme.attempt(response))

    expectedError.isLeft should be(true)
    expectedError.left.map(err => {
      err.getMessage should equal(
        s"Status code 500 calling http://localhost:$preservicaPort/api/accesstoken/login with method POST statusCode: 500, response: "
      )
    })
  }

  "entitiesUpdatedSince" should "return an entity if one was updated since the datetime specified" in {
    val date = ZonedDateTime.of(2023, 4, 25, 0, 0, 0, 0, ZoneId.of("UTC"))
    val pageResult = <EntitiesResponse>
      <Entities>
        <Entity title="page1File.txt" ref="8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91" type="IO">http://localhost/page1/object</Entity>
      </Entities>
      <Paging>
        <Next>http://localhost:{
      preservicaPort
    }/api/entity/entities/updated-since?date=2023-04-25T00%3A00%3A00.000Z
          &amp;
          start=100
          &amp;
          max=1000</Next>
      </Paging>
    </EntitiesResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/entities/updated-since"))
        .withQueryParams(
          Map(
            "date" -> equalTo("2023-04-25T00:00:00.000Z"),
            "max" -> equalTo("1000"),
            "start" -> equalTo("0")
          ).asJava
        )
        .willReturn(ok(pageResult.toString()))
    )

    val client = testClient(s"http://localhost:$preservicaPort")
    val response = valueFromF(client.entitiesUpdatedSince(date, secretName, 0))

    val expectedEntity = response.head

    expectedEntity.ref.toString should equal("8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91")
    expectedEntity.path should equal("information-objects")
    expectedEntity.title.get should be("page1File.txt")
    expectedEntity.deleted should be(false)
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
            "max" -> equalTo("1000"),
            "start" -> equalTo("0")
          ).asJava
        )
        .willReturn(ok(input.toString()))
    )

    val client = testClient(s"http://localhost:$preservicaPort")
    val response = valueFromF(client.entitiesUpdatedSince(date, secretName, 0))

    response.size should equal(0)
  }

  "entitiesUpdatedSince" should "return an error if the request is malformed" in {
    val date = ZonedDateTime.of(2023, 4, 25, 0, 0, 0, 0, ZoneId.of("UTC"))

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
        .willReturn(badRequest())
    )

    val client = testClient(s"http://localhost:$preservicaPort")
    val response = valueFromF(cme.attempt(client.entitiesUpdatedSince(date, secretName, 0)))

    response.left.map(err => {
      err.getClass.getSimpleName should equal("PreservicaClientException")
    })
  }
}
