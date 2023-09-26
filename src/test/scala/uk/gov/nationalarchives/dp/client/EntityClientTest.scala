package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.implicits.catsSyntaxOptionId
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatest.prop.TableDrivenPropertyChecks.forAll
import org.scalatest.prop.TableFor2
import org.scalatest.prop.Tables.Table
import org.scalatest.{Assertion, BeforeAndAfterEach}
import sttp.capabilities.Streams
import uk.gov.nationalarchives.dp.client.Entities.{Entity, Identifier, fromType}
import uk.gov.nationalarchives.dp.client.Client._
import uk.gov.nationalarchives.dp.client.EntityClient.{
  AddEntityRequest,
  Open,
  UpdateEntityRequest,
  StructuralObject,
  InformationObject,
  ContentObject
}

import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.xml.PrettyPrinter

abstract class EntityClientTest[F[_], S](preservicaPort: Int, secretsManagerPort: Int, stream: Streams[S])(implicit
    cme: MonadError[F, Throwable]
) extends AnyFlatSpec
    with BeforeAndAfterEach {

  def valueFromF[T](value: F[T]): T

  def createClient(url: String): F[EntityClient[F, S]]

  def testClient(url: String): EntityClient[F, S] = valueFromF(createClient(url))

  val zeroSeconds: FiniteDuration = FiniteDuration(0, TimeUnit.SECONDS)

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

  val tokenResponse: String = """{"token": "abcde"}"""
  val tokenUrl = "/api/accesstoken/login"

  val ref: UUID = UUID.randomUUID()
  val entityUrl = s"/api/entity/content-objects/$ref"

  private val identifier = Identifier(
    "TestIdentifierName",
    "TestIdentifierValue"
  )

  val updateRequestPermutations: TableFor2[String, Option[String]] = Table(
    ("title", "description"),
    ("page1FileCorrection.txt", Some("A new description")),
    ("page1FileCorrection.txt", None)
  )

  List((Some(ref), "with"), (None, "without")).foreach { case (reference, withOrWithout) =>
    "addEntity" should s"make a correct request $withOrWithout a predefined reference to add an entity" in {
      val entityResponse =
        <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
          <xip:StructuralObject>
            <xip:Ref>
              {ref}
            </xip:Ref>
            <xip:Title>page1File.txt</xip:Title>
            <xip:Description>A description</xip:Description>
            <xip:SecurityTag>open</xip:SecurityTag>
            <xip:Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</xip:Parent>
          </xip:StructuralObject>
          <AdditionalInformation>
            <Generations>
            </Generations>
          </AdditionalInformation>
        </EntityResponse>.toString()

      preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
      preservicaServer.stubFor(post(urlEqualTo(s"/api/entity/structural-objects")).willReturn(ok(entityResponse)))

      val addEntityRequest = AddEntityRequest(
        reference,
        "page1FileCorrection.txt",
        Some("A new description"),
        StructuralObject,
        Open,
        Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
      )

      val client = testClient(s"http://localhost:$preservicaPort")
      val addEntityResponse: F[UUID] = client.addEntity(addEntityRequest)

      val _ = valueFromF(addEntityResponse)

      val requestMade = getRequestMade(preservicaServer)

      requestMade should be(
        s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
++++++++++++
            <StructuralObject xmlns="http://preservica.com/XIP/v6.5">
              ${if (addEntityRequest.ref.nonEmpty) s"<Ref>${addEntityRequest.ref.get}</Ref>"}
              <Title>page1FileCorrection.txt</Title>
              <Description>A new description</Description>
              <SecurityTag>open</SecurityTag>
              <Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</Parent>
            </StructuralObject>""".replace("++++++++++++", "            ")
      )
    }
  }

  "addEntity" should s"make a correct request with the object details inside a XIP node if entity path to add is an 'information object'" in {
    val entityResponse =
      <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <xip:InformationObject>
        <xip:Ref>
          {ref}
        </xip:Ref>
        <xip:Title>page1File.txt</xip:Title>
        <xip:Description>A description</xip:Description>
        <xip:SecurityTag>open</xip:SecurityTag>
        <xip:Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</xip:Parent>
      </xip:InformationObject>
      <AdditionalInformation>
        <Generations>
        </Generations>
      </AdditionalInformation>
    </EntityResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(post(urlEqualTo(s"/api/entity/information-objects")).willReturn(ok(entityResponse)))

    val addEntityRequest = AddEntityRequest(
      None,
      "page1FileCorrection.txt",
      Some("A new description"),
      InformationObject,
      Open,
      Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
    )

    val client = testClient(s"http://localhost:$preservicaPort")
    val addEntityResponse: F[UUID] = client.addEntity(addEntityRequest)

    val _ = valueFromF(addEntityResponse)

    val requestMade = getRequestMade(preservicaServer)

    requestMade should be(
      s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <XIP xmlns="http://preservica.com/XIP/v6.5">
            <InformationObject xmlns="http://preservica.com/XIP/v6.5">
              ${if (addEntityRequest.ref.nonEmpty) "<Ref>${addEntityRequest.ref}</Ref>"}
              <Title>page1FileCorrection.txt</Title>
              <Description>A new description</Description>
              <SecurityTag>open</SecurityTag>
              <Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</Parent>
            </InformationObject>
            </XIP>"""
    )
  }

  "addEntity" should "return an error if a content-object entity path was passed in" in {
    val addEntityRequest = AddEntityRequest(
      None,
      "page1FileCorrection.txt",
      Some("A new description"),
      ContentObject,
      Open,
      Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
    )

    val client = testClient(s"http://localhost:$preservicaPort")
    val addEntityResponse: F[UUID] = client.addEntity(addEntityRequest)

    val error = intercept[PreservicaClientException] {
      valueFromF(addEntityResponse)
    }

    error.getMessage should be("You currently cannot create a content object via the API.")
  }

  "addEntity" should "return an error if a non-structural object was passed in without a parent" in {
    val client = testClient(s"http://localhost:$preservicaPort")
    val addEntityRequest = AddEntityRequest(
      Some(ref),
      "page1FileCorrection.txt",
      Some("A new description"),
      InformationObject,
      Open,
      None
    )

    val addEntityResponse: F[UUID] = client.addEntity(addEntityRequest)

    val error = intercept[PreservicaClientException] {
      valueFromF(addEntityResponse)
    }

    error.getMessage should be(
      "You must pass in the parent ref if you would like to add/update a non-structural object."
    )
  }

  "addEntity" should s"return a message confirmation if the object got updated" in {
    val entityResponse =
      <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <xip:StructuralObject>
        <xip:Ref>
          {ref}
        </xip:Ref>
        <xip:Title>page1File.txt</xip:Title>
        <xip:Description>A description</xip:Description>
        <xip:SecurityTag>open</xip:SecurityTag>
        <xip:Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</xip:Parent>
      </xip:StructuralObject>
      <AdditionalInformation>
        <Generations>
        </Generations>
      </AdditionalInformation>
    </EntityResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(post(urlEqualTo(s"/api/entity/structural-objects")).willReturn(ok(entityResponse)))

    val addEntityRequest = AddEntityRequest(
      Some(ref),
      "page1FileCorrection.txt",
      Some("A new description"),
      StructuralObject,
      Open,
      Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
    )

    val client = testClient(s"http://localhost:$preservicaPort")
    val addEntityResponse: F[UUID] = client.addEntity(addEntityRequest)

    val response = valueFromF(addEntityResponse)

    response should be(ref)
  }

  "addEntity" should s"return an exception if the API call does" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(post(urlEqualTo(s"/api/entity/structural-objects")).willReturn(badRequest()))

    val updateEntityRequest = AddEntityRequest(
      Some(UUID.fromString("6380a397-294b-4b02-990f-db5fc20b113f")),
      "page1FileCorrection.txt",
      Some("A new description"),
      StructuralObject,
      Open,
      Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
    )

    val client = testClient(s"http://localhost:$preservicaPort")

    val error = intercept[PreservicaClientException] {
      valueFromF(client.addEntity(updateEntityRequest))
    }

    error.getMessage should equal(
      s"Status code 400 calling http://localhost:$preservicaPort/api/entity/structural-objects with method POST "
    )
  }

  forAll(updateRequestPermutations) { (title, potentialDescription) =>
    {
      val indexesOfChanges = List(Some(0), potentialDescription.map(_ => 1)).flatten
      val nodesToUpdate = indexesOfChanges.map(index => updateRequestPermutations.heading.productElement(index))

      "updateEntity" should s"make a correct request to update the ${nodesToUpdate.mkString(" and ")}" in {
        val entityResponse =
          <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
          <xip:StructuralObject>
            <xip:Ref>
              {ref}
            </xip:Ref>
            <xip:Title>page1File.txt</xip:Title>
            <xip:Description>A description</xip:Description>
            <xip:SecurityTag>open</xip:SecurityTag>
            <xip:Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</xip:Parent>
          </xip:StructuralObject>
          <AdditionalInformation>
            <Generations>
            </Generations>
          </AdditionalInformation>
        </EntityResponse>.toString()

        preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
        preservicaServer.stubFor(put(urlEqualTo(s"/api/entity/structural-objects/$ref")).willReturn(ok(entityResponse)))

        val updateEntityRequest = UpdateEntityRequest(
          ref,
          title,
          potentialDescription,
          StructuralObject,
          Open,
          Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
        )

        val client = testClient(s"http://localhost:$preservicaPort")
        val updateEntityResponse: F[String] = client.updateEntity(updateEntityRequest)

        val _ = valueFromF(updateEntityResponse)

        val requestMade = getRequestMade(preservicaServer)

        requestMade should be(
          s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
++++++++++++
            <StructuralObject xmlns="http://preservica.com/XIP/v6.5">
              <Ref>${updateEntityRequest.ref}</Ref>
              <Title>${updateEntityRequest.title}</Title>
              ${if (updateEntityRequest.descriptionToChange.nonEmpty)
              s"<Description>${updateEntityRequest.descriptionToChange.get}</Description>"}
              <SecurityTag>open</SecurityTag>
              <Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</Parent>
            </StructuralObject>""".replace("++++++++++++", "            ")
        )
      }
    }
  }

  "updateEntity" should "return an error if a non-structural object was passed in without a parent" in {
    val client = testClient(s"http://localhost:$preservicaPort")
    val updateEntityRequest = UpdateEntityRequest(
      ref,
      "page1FileCorrection.txt",
      Some("A new description"),
      InformationObject,
      Open,
      None
    )

    val updateEntityResponse: F[String] = client.updateEntity(updateEntityRequest)

    val error = intercept[PreservicaClientException] {
      valueFromF(updateEntityResponse)
    }

    error.getMessage should be(
      "You must pass in the parent ref if you would like to add/update a non-structural object."
    )
  }

  "updateEntity" should s"return a message confirmation if the object got updated" in {
    val entityResponse =
      <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <xip:StructuralObject>
        <xip:Ref>
          {ref}
        </xip:Ref>
        <xip:Title>page1File.txt</xip:Title>
        <xip:Description>A description</xip:Description>
        <xip:SecurityTag>open</xip:SecurityTag>
        <xip:Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</xip:Parent>
      </xip:StructuralObject>
      <AdditionalInformation>
        <Generations>
        </Generations>
      </AdditionalInformation>
    </EntityResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(put(urlEqualTo(s"/api/entity/structural-objects/$ref")).willReturn(ok(entityResponse)))

    val updateEntityRequest = UpdateEntityRequest(
      ref,
      "page1FileCorrection.txt",
      Some("A new description"),
      StructuralObject,
      Open,
      Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
    )

    val client = testClient(s"http://localhost:$preservicaPort")
    val updateEntityResponse: F[String] = client.updateEntity(updateEntityRequest)

    val response = valueFromF(updateEntityResponse)

    response should be("Entity was updated")
  }

  "updateEntity" should s"return an exception if the API call does" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      put(urlEqualTo(s"/api/entity/structural-objects/6380a397-294b-4b02-990f-db5fc20b113f")).willReturn(badRequest())
    )

    val updateEntityRequest = UpdateEntityRequest(
      UUID.fromString("6380a397-294b-4b02-990f-db5fc20b113f"),
      "page1FileCorrection.txt",
      Some("A new description"),
      StructuralObject,
      Open,
      Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
    )

    val client = testClient(s"http://localhost:$preservicaPort")

    val error = intercept[PreservicaClientException] {
      valueFromF(client.updateEntity(updateEntityRequest))
    }

    error.getMessage should equal(
      s"Status code 400 calling http://localhost:$preservicaPort/api/entity/structural-objects/6380a397-294b-4b02-990f-db5fc20b113f with method PUT "
    )
  }

  "nodesFromEntity" should "return a Map of names and values of the nodes requested" in {
    val generationsUrl = s"/api/entity/content-objects/$ref/generations"

    val entityResponse =
      <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
        <xip:ContentObject>
          <xip:Ref>
            {ref}
          </xip:Ref>
          <xip:Title>page1File.txt</xip:Title>
          <xip:Description>A description</xip:Description>
          <xip:SecurityTag>open</xip:SecurityTag>
          <xip:Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</xip:Parent>
        </xip:ContentObject>
        <AdditionalInformation>
          <Generations>http://localhost:
            {preservicaPort}{generationsUrl}
          </Generations>
        </AdditionalInformation>
      </EntityResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))

    val client = testClient(s"http://localhost:$preservicaPort")
    val response: F[Map[String, String]] =
      client.nodesFromEntity(ref, ContentObject, List("parent", "description"))

    val nodeAndValue = valueFromF(response)
    nodeAndValue.size should equal(2)

    nodeAndValue("parent") should be("58412111-c73d-4414-a8fc-495cfc57f7e1")
    nodeAndValue("description") should be("A description")

    checkServerCall(entityUrl)
  }

  "nodesFromEntity" should "return an empty Map if no names of nodes were requested" in {
    val generationsUrl = s"/api/entity/content-objects/$ref/generations"

    val entityResponse =
      <EntityResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
        <xip:ContentObject>
          <xip:Ref>
            {ref}
          </xip:Ref>
          <xip:Title>page1File.txt</xip:Title>
          <xip:Description>A description</xip:Description>
          <xip:SecurityTag>open</xip:SecurityTag>
          <xip:Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</xip:Parent>
        </xip:ContentObject>
        <AdditionalInformation>
          <Generations>http://localhost:
            {preservicaPort}{generationsUrl}
          </Generations>
        </AdditionalInformation>
      </EntityResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))

    val client = testClient(s"http://localhost:$preservicaPort")
    val response: F[Map[String, String]] = client.nodesFromEntity(ref, ContentObject, Nil)

    val nodeAndValue = valueFromF(response)
    nodeAndValue.size should equal(0)

    checkServerCall(entityUrl)
  }

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
    val response: F[Seq[BitStreamInfo]] = client.getBitstreamInfo(ref)

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
    val response: F[Seq[BitStreamInfo]] = client.getBitstreamInfo(ref)

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
    val response: F[Seq[BitStreamInfo]] = client.getBitstreamInfo(ref)

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
    val response = client.getBitstreamInfo(UUID.randomUUID())

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
      client.streamBitstreamContent[Unit](stream)(s"$url/bitstream/1", _ => cme.unit)

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
    val entity = valueFromF(fromType("IO", entityId, Option("title"), Option("description"), deleted = false))
    val entityUrl = s"/api/entity/${entity.path.get}/${entity.ref}"
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

    val res = client.metadataForEntity(entity)
    val metadata = valueFromF(res)

    metadata.size should equal(1)
    metadata.head should equal(fragmentOneContent)

    checkServerCall(entityUrl)
    checkServerCall(fragmentOneUrl)
  }

  "metadataForEntityUrl" should "return a multiple fragments when the object has multiple fragments" in {
    val url = s"http://localhost:$preservicaPort"
    val entityId = UUID.randomUUID()
    val entity = valueFromF(fromType("IO", entityId, Option("title"), Option("description"), deleted = false))
    val entityUrl = s"/api/entity/${entity.path.get}/${entity.ref}"
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

    val res = client.metadataForEntity(entity)
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
    val entity = valueFromF(fromType("IO", entityId, Option("title"), Option("description"), deleted = false))
    val entityUrl = s"/api/entity/${entity.path.get}/${entity.ref}"
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

    val res = client.metadataForEntity(entity)
    val error = intercept[PreservicaClientException] {
      valueFromF(res)
    }
    error.getMessage should equal("No content found for elements:\n")

    checkServerCall(entityUrl)
  }

  "metadataForEntityUrl" should "return an error if the server is unavailable" in {
    val tokenUrl = "/api/accesstoken/login"
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(serverError()))
    val entity = valueFromF(fromType("IO", UUID.randomUUID(), Option("title"), Option("description"), deleted = false))
    val client = testClient(s"http://localhost:$preservicaPort")
    val response = client.metadataForEntity(entity)

    val expectedError = valueFromF(cme.attempt(response))

    expectedError.isLeft should be(true)
    expectedError.left.map(err => {
      err.getMessage should equal(
        s"Status code 500 calling http://localhost:$preservicaPort/api/accesstoken/login with method POST statusCode: 500, response: "
      )
    })
  }

  "metadataForEntityUrl" should "return an error if the entity path is empty" in {
    val id = UUID.randomUUID()
    val entity = Entity(None, id, None, None, deleted = true, None)
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    val client = testClient(s"http://localhost:$preservicaPort")
    val ex = intercept[PreservicaClientException] {
      valueFromF(client.metadataForEntity(entity))
    }
    ex.getMessage should equal(s"No path found for entity id $id. Could this entity have been deleted?")
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
    val response = valueFromF(client.entitiesUpdatedSince(date, 0))

    val expectedEntity = response.head

    expectedEntity.ref.toString should equal("8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91")
    expectedEntity.path.get should equal("information-objects")
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
    val response = valueFromF(client.entitiesUpdatedSince(date, 0))

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
    val response = valueFromF(cme.attempt(client.entitiesUpdatedSince(date, 0)))

    response.left.map(err => {
      err.getClass.getSimpleName should equal("PreservicaClientException")
    })
  }

  "entityEventActions" should "return all paginated values in reverse chronological order (most recent EventAction first)" in {
    val firstPage = <EventActionsResponse>
      <EventActions>
        <xip:EventAction commandType="command_create">
          <xip:Event type="Ingest">
            <xip:Ref>6da319fa-07e0-4a83-9c5a-b6bad08445b1</xip:Ref>
            <xip:Date>2023-06-26T08:14:08.441Z</xip:Date>
            <xip:User>test user</xip:User>
          </xip:Event>
          <xip:Date>2023-06-26T08:14:07.441Z</xip:Date>
          <xip:Entity>a9e1cae8-ea06-4157-8dd4-82d0525b031c</xip:Entity>
        </xip:EventAction>
      </EventActions>
      <Paging>
        <Next>http://localhost:{
      preservicaPort
    }/api/entity/content-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/event-actions?max=1000&amp;start=1000</Next>
      </Paging>
    </EventActionsResponse>
    val secondPage = <EventActionsResponse>
      <EventActions>
        <xip:EventAction commandType="AddIdentifier">
          <xip:Event type="Modified">
            <xip:Ref>efe9b25d-c3b4-476a-8ff1-d52fb01ad96b</xip:Ref>
            <xip:Date>2023-06-27T08:14:08.442Z</xip:Date>
            <xip:User>test user</xip:User>
          </xip:Event>
          <xip:Date>2023-06-27T08:14:07.442Z</xip:Date>
          <xip:Entity>a9e1cae8-ea06-4157-8dd4-82d0525b031c</xip:Entity>
        </xip:EventAction>
      </EventActions>
      <Paging>
      </Paging>
    </EventActionsResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/content-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/event-actions"))
        .withQueryParams(
          Map(
            "max" -> equalTo("1000"),
            "start" -> equalTo("0")
          ).asJava
        )
        .willReturn(ok(firstPage.toString()))
    )

    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/content-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/event-actions"))
        .withQueryParams(
          Map(
            "max" -> equalTo("1000"),
            "start" -> equalTo("1000")
          ).asJava
        )
        .willReturn(ok(secondPage.toString()))
    )
    val client = testClient(s"http://localhost:$preservicaPort")
    val response = valueFromF(
      client.entityEventActions(
        Entities.Entity(
          "CO".some,
          UUID.fromString("a9e1cae8-ea06-4157-8dd4-82d0525b031c"),
          None,
          None,
          deleted = false,
          ContentObject.entityPath.some
        )
      )
    )

    val firstResult = response.head
    val secondResult = response.last

    firstResult.dateOfEvent.toString should equal("2023-06-27T08:14:08.442Z")
    firstResult.eventRef should equal(UUID.fromString("efe9b25d-c3b4-476a-8ff1-d52fb01ad96b"))
    firstResult.eventType should be("Modified")

    secondResult.dateOfEvent.toString should equal("2023-06-26T08:14:08.441Z")
    secondResult.eventRef should equal(UUID.fromString("6da319fa-07e0-4a83-9c5a-b6bad08445b1"))
    secondResult.eventType should be("Ingest")
  }

  "entityEventActions" should "return an error if the request is malformed" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/content-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/event-actions"))
        .withQueryParams(
          Map(
            "max" -> equalTo("1000"),
            "start" -> equalTo("0")
          ).asJava
        )
        .willReturn(badRequest())
    )

    val client = testClient(s"http://localhost:$preservicaPort")
    val response = valueFromF(
      cme.attempt(
        client.entityEventActions(
          Entities.Entity(
            "CO".some,
            UUID.fromString("a9e1cae8-ea06-4157-8dd4-82d0525b031c"),
            None,
            None,
            deleted = false,
            ContentObject.entityPath.some
          )
        )
      )
    )

    response.left.map { err =>
      err.getClass.getSimpleName should equal("PreservicaClientException")
    }
  }

  "entityEventActions" should "return an error if the entity path is empty" in {
    val id = UUID.randomUUID()
    val client = testClient(s"http://localhost:$preservicaPort")
    val ex = intercept[PreservicaClientException] {
      valueFromF(
        client.entityEventActions(
          Entities.Entity(
            None,
            id,
            None,
            None,
            deleted = true,
            None
          )
        )
      )
    }
    ex.getMessage should equal(s"No path found for entity id $id. Could this entity have been deleted?")
  }

  "entitiesByIdentifier" should "return an entity if it has the identifier specified" in {
    val pageResult = <EntitiesResponse>
      <Entities>
        <Entity title="page1File.txt" ref="8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91" type="SO">http://localhost/page1/object</Entity>
      </Entities>
      <Paging>
        <Next>http://localhost:
          {preservicaPort}
          /api/entity/entities/by-identifier?type=testIdentifier&amp;value=testValue
        </Next>
      </Paging>
    </EntitiesResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/entities/by-identifier"))
        .withQueryParams(
          Map(
            "type" -> equalTo("testIdentifier"),
            "value" -> equalTo("testValue")
          ).asJava
        )
        .willReturn(ok(pageResult.toString()))
    )

    val client = testClient(s"http://localhost:$preservicaPort")
    val response = valueFromF(
      client.entitiesByIdentifier(Identifier("testIdentifier", "testValue"))
    )

    val expectedEntity = response.head

    expectedEntity.ref.toString should equal("8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91")
    expectedEntity.path.get should equal("structural-objects")
    expectedEntity.title.get should be("page1File.txt")
    expectedEntity.deleted should be(false)
  }

  "entitiesByIdentifier" should "return an empty list if no entities have the identifier specified" in {
    val pageResult = <EntitiesResponse>
      <Entities></Entities>
    </EntitiesResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/entities/by-identifier"))
        .withQueryParams(
          Map(
            "type" -> equalTo("testIdentifier"),
            "value" -> equalTo("testValueDoesNotExist")
          ).asJava
        )
        .willReturn(ok(pageResult.toString()))
    )

    val client = testClient(s"http://localhost:$preservicaPort")
    val response = valueFromF(
      client.entitiesByIdentifier(Identifier("testIdentifier", "testValueDoesNotExist"))
    )

    response.size should equal(0)
  }

  "entitiesByIdentifier" should "return an error if the request is malformed" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/entities/by-identifier"))
        .withQueryParams(
          Map(
            "type" -> equalTo("testIdentifier"),
            "value" -> equalTo("testValue")
          ).asJava
        )
        .willReturn(badRequest())
    )

    val client = testClient(s"http://localhost:$preservicaPort")
    val response = valueFromF(
      cme.attempt(
        client.entitiesByIdentifier(Identifier("testIdentifier", "testValue"))
      )
    )

    response.left.map { err =>
      err.getClass.getSimpleName should equal("PreservicaClientException")
    }
  }

  "addIdentifierForEntity" should s"make a correct request to add an identifier to an Entity" in {
    val entityResponse =
      <IdentifiersResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
        <Identifiers>
          <xip:Identifier>
            <xip:ApiId>65862d40f40440de14c1b75e5f342e99</xip:ApiId>
            <xip:Type>TestIdentifierName</xip:Type>
            <xip:Value>TestIdentifierValue</xip:Value>
            <xip:Entity>{ref}</xip:Entity>
          </xip:Identifier>
        </Identifiers>
        <Paging>
          <TotalResults>1</TotalResults>
        </Paging>
        <AdditionalInformation>
          <Self>http://mockapi.com/api/entity/structural-objects/$ref/identifiers</Self>
        </AdditionalInformation>
      </IdentifiersResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      post(urlEqualTo(s"/api/entity/structural-objects/$ref/identifiers")).willReturn(ok(entityResponse))
    )

    val client = testClient(s"http://localhost:$preservicaPort")
    val addIdentifierForEntityResponse: F[String] =
      client.addIdentifierForEntity(ref, StructuralObject, identifier)

    val _ = valueFromF(addIdentifierForEntityResponse)

    val requestMade = getRequestMade(preservicaServer)

    val expectedXml =
      new PrettyPrinter(80, 2).format(
        <Identifier xmlns="http://preservica.com/XIP/v6.5">
          <Type>TestIdentifierName</Type>
          <Value>TestIdentifierValue</Value>
        </Identifier>
      )

    requestMade should be(s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n""" + expectedXml)
  }

  "addIdentifierForEntity" should s"return an exception if the API call does" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      post(urlEqualTo(s"/api/entity/structural-objects/$ref/identifiers")).willReturn(badRequest())
    )

    val client = testClient(s"http://localhost:$preservicaPort")
    val addIdentifierForEntityResponse: F[String] =
      client.addIdentifierForEntity(ref, StructuralObject, identifier)

    val error = intercept[PreservicaClientException] {
      valueFromF(addIdentifierForEntityResponse)
    }

    error.getMessage should equal(
      s"Status code 400 calling http://localhost:$preservicaPort/api/entity/structural-objects/$ref/identifiers with method POST "
    )
  }

  "addIdentifierForEntity" should s"return a message confirmation if the Identifier was added" in {
    val entityResponse =
      <IdentifiersResponse xmlns="http://preservica.com/EntityAPI/v6.5" xmlns:xip="http://preservica.com/XIP/v6.5">
      <Identifiers>
        <xip:Identifier>
          <xip:ApiId>65862d40f40440de14c1b75e5f342e99</xip:ApiId>
          <xip:Type>TestIdentifierName</xip:Type>
          <xip:Value>TestIdentifierValue</xip:Value>
          <xip:Entity>
            {ref}
          </xip:Entity>
        </xip:Identifier>
      </Identifiers>
      <Paging>
        <TotalResults>1</TotalResults>
      </Paging>
      <AdditionalInformation>
        <Self>http://mockapi.com/api/entity/structural-objects/$ref/identifiers</Self>
      </AdditionalInformation>
    </IdentifiersResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      post(urlEqualTo(s"/api/entity/structural-objects/$ref/identifiers")).willReturn(ok(entityResponse))
    )

    val client = testClient(s"http://localhost:$preservicaPort")
    val addIdentifierForEntityResponse: F[String] =
      client.addIdentifierForEntity(ref, StructuralObject, identifier)

    val response = valueFromF(addIdentifierForEntityResponse)

    response should be("The Identifier was added")
  }

  private def getRequestMade(preservicaServer: WireMockServer) =
    preservicaServer.getServeEvents.getServeEvents.get(0).getRequest.getBodyAsString
}
