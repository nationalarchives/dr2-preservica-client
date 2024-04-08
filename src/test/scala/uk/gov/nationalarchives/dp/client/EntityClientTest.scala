package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.implicits.catsSyntaxOptionId
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.prop.TableDrivenPropertyChecks.forAll
import org.scalatest.prop.TableFor2
import org.scalatest.prop.Tables.Table
import org.scalatest.{Assertion, BeforeAndAfterEach}
import sttp.capabilities.Streams
import uk.gov.nationalarchives.dp.client.Entities.{Entity, IdentifierResponse, fromType}
import uk.gov.nationalarchives.DynamoFormatters.Identifier
import uk.gov.nationalarchives.dp.client.Client.*
import uk.gov.nationalarchives.dp.client.EntityClient.*
import uk.gov.nationalarchives.dp.client.EntityClient.EntityType.*
import uk.gov.nationalarchives.dp.client.EntityClient.SecurityTag.*
import uk.gov.nationalarchives.dp.client.EntityClient.RepresentationType.*
import uk.gov.nationalarchives.dp.client.EntityClient.GenerationType.*

import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.xml.{Utility, XML}

abstract class EntityClientTest[F[_], S](preservicaPort: Int, secretsManagerPort: Int, stream: Streams[S])(using
    cme: MonadError[F, Throwable]
) extends AnyFlatSpec
    with BeforeAndAfterEach {

  def valueFromF[T](value: F[T]): T

  def createClient(url: String): F[EntityClient[F, S]]

  def testClient: EntityClient[F, S] = valueFromF(createClient(s"http://localhost:$preservicaPort"))

  private val apiVersion = 7.0f
  private val xipVersion = 7.0f
  private val xipUrl = s"http://preservica.com/XIP/v$xipVersion"
  private val namespaceUrl = s"http://preservica.com/EntityAPI/v$apiVersion"

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
  val entityUrl = s"/api/entity/v$apiVersion/content-objects/$ref"
  val ioRef: UUID = UUID.randomUUID()

  private val identifier = Identifier(
    "TestIdentifierName",
    "TestIdentifierValue"
  )

  val updateRequestPermutations: TableFor2[String, Option[String]] = Table(
    ("title", "description"),
    ("page1File&Correction.txt", Some("A new description")),
    ("page1File&Correction.txt", None)
  )

  List((Some(ref), "with"), (None, "without")).foreach { case (reference, withOrWithout) =>
    "addEntity" should s"make a correct request $withOrWithout a predefined reference to add an entity" in {
      val entityResponse =
        <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
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
      preservicaServer.stubFor(
        post(urlEqualTo(s"/api/entity/v$apiVersion/structural-objects")).willReturn(ok(entityResponse))
      )

      val addEntityRequest = AddEntityRequest(
        reference,
        "page1File&Correction.txt",
        Some("A new description"),
        StructuralObject,
        Open,
        Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
      )

      val client = testClient
      val addEntityResponse: F[UUID] = client.addEntity(addEntityRequest)

      val _ = valueFromF(addEntityResponse)

      val requestMade = getRequestMade(preservicaServer)

      requestMade should be(
        s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
++++++++++++
            <StructuralObject xmlns="http://preservica.com/XIP/v$xipVersion">
              ${if (addEntityRequest.ref.nonEmpty) s"<Ref>${addEntityRequest.ref.get}</Ref>" else ""}
              <Title>page1File&amp;Correction.txt</Title>
              <Description>A new description</Description>
              <SecurityTag>open</SecurityTag>
              <Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</Parent>
            </StructuralObject>""".replace("++++++++++++", "            ")
      )
    }
  }

  "addEntity" should s"make a correct request with the object details inside a XIP node if entity path to add is an 'information object'" in {
    val entityResponse =
      <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
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
    preservicaServer.stubFor(
      post(urlEqualTo(s"/api/entity/v$apiVersion/information-objects")).willReturn(ok(entityResponse))
    )

    val addEntityRequest = AddEntityRequest(
      None,
      "page1File&Correction.txt",
      Some("A new description"),
      InformationObject,
      Open,
      Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
    )

    val client = testClient
    val addEntityResponse: F[UUID] = client.addEntity(addEntityRequest)

    val _ = valueFromF(addEntityResponse)

    val requestMade = getRequestMade(preservicaServer)

    requestMade should be(
      s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <XIP xmlns="http://preservica.com/XIP/v$xipVersion">
            <InformationObject xmlns="http://preservica.com/XIP/v$xipVersion">
              ${if (addEntityRequest.ref.nonEmpty) s"<Ref>${addEntityRequest.ref}</Ref>" else ""}
              <Title>page1File&amp;Correction.txt</Title>
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
      "page1File&Correction.txt",
      Some("A new description"),
      ContentObject,
      Open,
      Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
    )

    val client = testClient
    val addEntityResponse: F[UUID] = client.addEntity(addEntityRequest)

    val error = intercept[PreservicaClientException] {
      valueFromF(addEntityResponse)
    }

    error.getMessage should be("You currently cannot create a content object via the API.")
  }

  "addEntity" should "return an error if a non-structural object was passed in without a parent" in {
    val client = testClient
    val addEntityRequest = AddEntityRequest(
      Some(ref),
      "page1File&Correction.txt",
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
      <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
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
    preservicaServer.stubFor(
      post(urlEqualTo(s"/api/entity/v$apiVersion/structural-objects")).willReturn(ok(entityResponse))
    )

    val addEntityRequest = AddEntityRequest(
      Some(ref),
      "page1File&Correction.txt",
      Some("A new description"),
      StructuralObject,
      Open,
      Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
    )

    val client = testClient
    val addEntityResponse: F[UUID] = client.addEntity(addEntityRequest)

    val response = valueFromF(addEntityResponse)

    response should be(ref)
  }

  "addEntity" should s"return an exception if the API call does" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(post(urlEqualTo(s"/api/entity/v$apiVersion/structural-objects")).willReturn(badRequest()))

    val updateEntityRequest = AddEntityRequest(
      Some(UUID.fromString("6380a397-294b-4b02-990f-db5fc20b113f")),
      "page1File&Correction.txt",
      Some("A new description"),
      StructuralObject,
      Open,
      Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
    )

    val client = testClient

    val error = intercept[PreservicaClientException] {
      valueFromF(client.addEntity(updateEntityRequest))
    }

    error.getMessage should equal(
      s"Status code 400 calling http://localhost:$preservicaPort/api/entity/v$apiVersion/structural-objects with method POST "
    )
  }

  forAll(updateRequestPermutations) { (title, potentialDescription) =>
    {
      val indexesOfChanges = List(Some(0), potentialDescription.map(_ => 1)).flatten
      val nodesToUpdate = indexesOfChanges.map(index => updateRequestPermutations.heading.productElement(index))

      "updateEntity" should s"make a correct request to update the ${nodesToUpdate.mkString(" and ")}" in {
        val entityResponse =
          <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
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
        preservicaServer.stubFor(
          put(urlEqualTo(s"/api/entity/v$apiVersion/structural-objects/$ref")).willReturn(ok(entityResponse))
        )

        val updateEntityRequest = UpdateEntityRequest(
          ref,
          title,
          potentialDescription,
          StructuralObject,
          Open,
          Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
        )

        val client = testClient
        val updateEntityResponse: F[String] = client.updateEntity(updateEntityRequest)

        val _ = valueFromF(updateEntityResponse)

        val requestMade = getRequestMade(preservicaServer)

        requestMade should be(
          s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
++++++++++++
            <StructuralObject xmlns="http://preservica.com/XIP/v$xipVersion">
              <Ref>${updateEntityRequest.ref}</Ref>
              <Title>${Utility.escape(updateEntityRequest.title)}</Title>
              ${
              if (updateEntityRequest.descriptionToChange.nonEmpty)
                s"<Description>${updateEntityRequest.descriptionToChange.get}</Description>"
              else ""
            }
              <SecurityTag>open</SecurityTag>
              <Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</Parent>
            </StructuralObject>""".replace("++++++++++++", "            ")
        )
      }
    }
  }

  "updateEntity" should "return an error if a non-structural object was passed in without a parent" in {
    val client = testClient
    val updateEntityRequest = UpdateEntityRequest(
      ref,
      "page1File&Correction.txt",
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
      <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
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
    preservicaServer.stubFor(
      put(urlEqualTo(s"/api/entity/v$apiVersion/structural-objects/$ref")).willReturn(ok(entityResponse))
    )

    val updateEntityRequest = UpdateEntityRequest(
      ref,
      "page1File&Correction.txt",
      Some("A new description"),
      StructuralObject,
      Open,
      Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
    )

    val client = testClient
    val updateEntityResponse: F[String] = client.updateEntity(updateEntityRequest)

    val response = valueFromF(updateEntityResponse)

    response should be("Entity was updated")
  }

  "updateEntity" should s"return an exception if the API call does" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      put(urlEqualTo(s"/api/entity/v$apiVersion/structural-objects/6380a397-294b-4b02-990f-db5fc20b113f"))
        .willReturn(badRequest())
    )

    val updateEntityRequest = UpdateEntityRequest(
      UUID.fromString("6380a397-294b-4b02-990f-db5fc20b113f"),
      "page1File&Correction.txt",
      Some("A new description"),
      StructuralObject,
      Open,
      Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
    )

    val client = testClient

    val error = intercept[PreservicaClientException] {
      valueFromF(client.updateEntity(updateEntityRequest))
    }

    error.getMessage should equal(
      s"Status code 400 calling http://localhost:$preservicaPort/api/entity/v$apiVersion/structural-objects/6380a397-294b-4b02-990f-db5fc20b113f with method PUT "
    )
  }

  "getBitstreamInfo" should "call the correct API endpoints and return the bitstream info" in {
    val fileName = "test.txt"
    val generationsUrl = s"/api/entity/v$apiVersion/content-objects/$ref/generations"
    val generationUrl = s"$generationsUrl/1"
    val bitstreamUrl = s"$generationUrl/bitstreams/1"

    val entityResponse =
      <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <xip:ContentObject>
          <xip:Ref>b2f07829-e167-499a-9f3e-727cf3f64468</xip:Ref>
          <xip:Title>page1File.txt</xip:Title>
          <xip:Description>A description</xip:Description>
          <xip:SecurityTag>open</xip:SecurityTag>
          <xip:Parent>a6771bd9-a4df-47fb-bcc1-982f0b42f7cb</xip:Parent>
        </xip:ContentObject>
      <AdditionalInformation>
        <Generations>http://localhost:{preservicaPort}{generationsUrl}</Generations>
      </AdditionalInformation>
    </EntityResponse>.toString()
    val generationsResponse =
      <GenerationsResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Generations>
          <Generation active="true">http://localhost:{preservicaPort}{generationUrl}</Generation>
        </Generations>
      </GenerationsResponse>.toString()
    val generationResponse =
      <GenerationResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Generation original="false" active="true">
        </Generation>
        <Bitstreams>
          <Bitstream filename="test.txt">http://localhost:{preservicaPort}{bitstreamUrl}</Bitstream>
        </Bitstreams>
      </GenerationResponse>.toString()
    val bitstreamResponse = <BitstreamResponse>
      <xip:Bitstream>
        <xip:Filename>{fileName}</xip:Filename>
        <xip:FileSize>1234</xip:FileSize>
        <xip:Fixities>
          <xip:Fixity>
            <xip:FixityAlgorithmRef>SHA1</xip:FixityAlgorithmRef>
            <xip:FixityValue>0c16735b03fe46b931060858e8cd5ca9c5101565</xip:FixityValue>
          </xip:Fixity>
        </xip:Fixities>
      </xip:Bitstream>
      <AdditionalInformation>
        <Self>http://test/generations/1/bitstreams/1</Self>
        <Content>http://test</Content>
      </AdditionalInformation>
    </BitstreamResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))
    preservicaServer.stubFor(get(urlEqualTo(generationsUrl)).willReturn(ok(generationsResponse)))
    preservicaServer.stubFor(get(urlEqualTo(generationUrl)).willReturn(ok(generationResponse)))
    preservicaServer.stubFor(get(urlEqualTo(bitstreamUrl)).willReturn(ok(bitstreamResponse)))

    val client = testClient
    val response: F[Seq[BitStreamInfo]] = client.getBitstreamInfo(ref)

    val bitStreamInfo = valueFromF(response).head
    bitStreamInfo.url should equal(s"http://test")
    bitStreamInfo.name should equal(fileName)
    bitStreamInfo.fileSize should equal(1234)
    bitStreamInfo.fixity.algorithm should equal("SHA1")
    bitStreamInfo.fixity.value should equal("0c16735b03fe46b931060858e8cd5ca9c5101565")
    bitStreamInfo.generationVersion should equal(1)
    bitStreamInfo.generationType should equal(Derived)
    bitStreamInfo.potentialCoTitle should equal(Some("page1File.txt"))
    bitStreamInfo.parentRef should equal(Some(UUID.fromString("a6771bd9-a4df-47fb-bcc1-982f0b42f7cb")))

    checkServerCall(entityUrl)
    checkServerCall(generationsUrl)
    checkServerCall(generationUrl)
  }

  "getBitstreamInfo" should "call the correct API endpoints and return the bitstream info for multiple generations" in {
    val generationsUrl = s"/api/entity/v$apiVersion/content-objects/$ref/generations"
    val generationOneUrl = s"$generationsUrl/1"
    val generationTwoUrl = s"$generationsUrl/2"
    val bitstreamOneUrl = s"$generationOneUrl/bitstreams/1"
    val bitstreamTwoUrl = s"$generationTwoUrl/bitstreams/1"

    val entityResponse =
      <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <xip:ContentObject>
          <xip:Ref>b2f07829-e167-499a-9f3e-727cf3f64468</xip:Ref>
          <xip:Title>page1File.txt</xip:Title>
          <xip:Description>A description</xip:Description>
          <xip:SecurityTag>open</xip:SecurityTag>
          <xip:Parent>a6771bd9-a4df-47fb-bcc1-982f0b42f7cb</xip:Parent>
        </xip:ContentObject>
        <AdditionalInformation>
          <Generations>http://localhost:{preservicaPort}{generationsUrl}</Generations>
        </AdditionalInformation>
      </EntityResponse>.toString()
    val generationsResponse =
      <GenerationsResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
      <Generations>
        <Generation active="true">http://localhost:{preservicaPort}{generationOneUrl}</Generation>
        <Generation active="true">http://localhost:{preservicaPort}{generationTwoUrl}</Generation>
      </Generations>
    </GenerationsResponse>.toString()
    val generationOneResponse =
      <GenerationResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Generation original="true" active="true">
        </Generation>
        <Bitstreams>
          <Bitstream filename="test1.txt">http://localhost:{preservicaPort}{bitstreamOneUrl}</Bitstream>
        </Bitstreams>
      </GenerationResponse>.toString()

    val generationTwoResponse =
      <GenerationResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Generation original="false" active="true">
        </Generation>
        <Bitstreams>
          <Bitstream filename="test2.txt">http://localhost:{preservicaPort}{bitstreamTwoUrl}</Bitstream>
        </Bitstreams>
      </GenerationResponse>.toString()

    val bitstreamOneResponse = <BitstreamResponse>
      <xip:Bitstream>
        <xip:Filename>test1.txt</xip:Filename>
        <xip:FileSize>1234</xip:FileSize>
        <xip:Fixities>
          <xip:Fixity>
            <xip:FixityAlgorithmRef>SHA1</xip:FixityAlgorithmRef>
            <xip:FixityValue>0c16735b03fe46b931060858e8cd5ca9c5101565</xip:FixityValue>
          </xip:Fixity>
        </xip:Fixities>
      </xip:Bitstream>
      <AdditionalInformation>
        <Self>http://test/generations/1/bitstreams/1</Self>
        <Content>http://test</Content>
      </AdditionalInformation>
    </BitstreamResponse>.toString()

    val bitstreamTwoResponse = <BitstreamResponse>
      <xip:Bitstream>
        <xip:Filename>test2.txt</xip:Filename>
        <xip:FileSize>1234</xip:FileSize>
        <xip:Fixities>
          <xip:Fixity>
            <xip:FixityAlgorithmRef>SHA1</xip:FixityAlgorithmRef>
            <xip:FixityValue>5e0a0af2f597bf6b06c5295fea11be74cf89e1c1</xip:FixityValue>
          </xip:Fixity>
        </xip:Fixities>
      </xip:Bitstream>
      <AdditionalInformation>
        <Self>http://test/generations/2/bitstreams/1</Self>
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

    val client = testClient
    val response: F[Seq[BitStreamInfo]] = client.getBitstreamInfo(ref)

    val bitStreamInfo = valueFromF(response)
    bitStreamInfo.size should equal(2)
    bitStreamInfo.head.url should equal(s"http://test")
    bitStreamInfo.head.name should equal("test1.txt")
    bitStreamInfo.head.fileSize should equal(1234)
    bitStreamInfo.head.fixity.algorithm should equal("SHA1")
    bitStreamInfo.head.fixity.value should equal("0c16735b03fe46b931060858e8cd5ca9c5101565")
    bitStreamInfo.head.generationVersion should equal(1)
    bitStreamInfo.head.generationType should equal(Original)
    bitStreamInfo.head.potentialCoTitle should equal(Some("page1File.txt"))
    bitStreamInfo.head.parentRef should equal(Some(UUID.fromString("a6771bd9-a4df-47fb-bcc1-982f0b42f7cb")))

    bitStreamInfo.last.url should equal(s"http://test")
    bitStreamInfo.last.name should equal("test2.txt")
    bitStreamInfo.last.fileSize should equal(1234)
    bitStreamInfo.last.fixity.algorithm should equal("SHA1")
    bitStreamInfo.last.fixity.value should equal("5e0a0af2f597bf6b06c5295fea11be74cf89e1c1")
    bitStreamInfo.last.generationVersion should equal(2)
    bitStreamInfo.last.generationType should equal(Derived)
    bitStreamInfo.last.potentialCoTitle should equal(Some("page1File.txt"))
    bitStreamInfo.last.parentRef should equal(Some(UUID.fromString("a6771bd9-a4df-47fb-bcc1-982f0b42f7cb")))

    checkServerCall(entityUrl)
    checkServerCall(generationsUrl)
    checkServerCall(generationOneUrl)
    checkServerCall(generationTwoUrl)
  }

  "getBitstreamInfo" should "return an error if no generations are available" in {
    val entityResponse =
      <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
      <AdditionalInformation>
      </AdditionalInformation>
    </EntityResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))

    val client = testClient
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

    val client = testClient
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
    val client = testClient

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

    val client = testClient
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
    val entityUrl = s"/api/entity/v$apiVersion/${entity.path.get}/${entity.ref}"
    val identifiersUrl = s"/api/entity/v$apiVersion/${entity.path.get}/identifiers"
    val fragmentOneUrl = s"/api/entity/v$apiVersion/information-objects/$entityId/metadata/${UUID.randomUUID()}"
    val entityResponse =
      <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <InformationObject>
          <Ref>{entityId}</Ref>
          <Title>Title</Title>
          <Description>A description</Description>
          <SecurityTag>open</SecurityTag>
          <Deleted>true</Deleted>
          <Parent>f567352f-0874-49da-85aa-ac0fbfa3b335</Parent>
        </InformationObject>
      <AdditionalInformation>
        <Metadata>
          <Fragment>{s"$url$fragmentOneUrl"}</Fragment>
        </Metadata>
      </AdditionalInformation>
    </EntityResponse>.toString

    val identifiersResponse =
      <IdentifiersResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Identifiers>
          <xip:Identifier>
            <xip:ApiId>acb1e74b1ad5c4bfc360ef5d44228c9f</xip:ApiId>
            <xip:Type>identifier</xip:Type>
            <xip:Value>testValue</xip:Value>
            <xip:Entity>{entityId}</xip:Entity>
          </xip:Identifier>
        </Identifiers>
      </IdentifiersResponse>.toString

    val fragmentOneContent = <Test1>
      <Test1Value>Test1Value</Test1Value>
    </Test1>

    val fragmentOneResponse =
      <MetadataResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
      <MetadataContainer>
        <Content>
          {fragmentOneContent}
        </Content>
      </MetadataContainer>
    </MetadataResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))
    preservicaServer.stubFor(get(urlEqualTo(identifiersUrl)).willReturn(ok(identifiersResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(fragmentOneUrl)).willReturn(ok(fragmentOneResponse.toString))
    )

    val client = testClient

    val res = client.metadataForEntity(entity)
    val metadata = valueFromF(res)

    metadata.entityNode.toString should equal(
      <InformationObject xmlns="http://preservica.com/EntityAPI/v7.0" xmlns:xip="http://preservica.com/XIP/v7.0">
          <Ref>{entityId}</Ref>
          <Title>Title</Title>
          <Description>A description</Description>
          <SecurityTag>open</SecurityTag>
          <Deleted>true</Deleted>
          <Parent>f567352f-0874-49da-85aa-ac0fbfa3b335</Parent>
        </InformationObject>.toString
    )
    metadata.identifiersNode.toString should equal(
      <xip:Identifiers><xip:Identifier xmlns="http://preservica.com/EntityAPI/v7.0" xmlns:xip="http://preservica.com/XIP/v7.0">
            <xip:ApiId>acb1e74b1ad5c4bfc360ef5d44228c9f</xip:ApiId>
            <xip:Type>identifier</xip:Type>
            <xip:Value>testValue</xip:Value>
            <xip:Entity>{entityId}</xip:Entity>
          </xip:Identifier></xip:Identifiers>.toString
    )
    metadata.metadataContainerNode.head.toString should equal(
      <MetadataContainer xmlns="http://preservica.com/EntityAPI/v7.0" xmlns:xip="http://preservica.com/XIP/v7.0" >
        <Content>
          <Test1>
      <Test1Value>Test1Value</Test1Value>
    </Test1>
        </Content>
      </MetadataContainer>.toString
    )

    checkServerCall(entityUrl)
    checkServerCall(fragmentOneUrl)
  }

  "metadataForEntityUrl" should "return a multiple fragments when the object has multiple fragments" in {
    val url = s"http://localhost:$preservicaPort"
    val entityId = UUID.randomUUID()
    val entity = valueFromF(fromType("IO", entityId, Option("title"), Option("description"), deleted = false))
    val entityUrl = s"/api/entity/v$apiVersion/${entity.path.get}/${entity.ref}"
    val fragmentOneUrl = s"/api/entity/v$apiVersion/information-objects/$entityId/metadata/${UUID.randomUUID()}"
    val fragmentTwoUrl = s"/api/entity/v$apiVersion/information-objects/$entityId/metadata/${UUID.randomUUID()}"
    val identifiersUrl = s"/api/entity/v$apiVersion/${entity.path.get}/identifiers"
    val entityResponse =
      <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <InformationObject>
          <Ref>{entityId}</Ref>
          <Title>Title</Title>
          <Description>A description</Description>
          <SecurityTag>open</SecurityTag>
          <Deleted>true</Deleted>
          <Parent>f567352f-0874-49da-85aa-ac0fbfa3b335</Parent>
        </InformationObject>
      <AdditionalInformation>
        <Metadata>
          <Fragment>{s"$url$fragmentOneUrl"}</Fragment>
          <Fragment>{s"$url$fragmentTwoUrl"}</Fragment>
        </Metadata>
      </AdditionalInformation>
    </EntityResponse>.toString

    val identifiersResponse =
      <IdentifiersResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Identifiers>
          <xip:Identifier>
            <xip:ApiId>acb1e74b1ad5c4bfc360ef5d44228c9f</xip:ApiId>
            <xip:Type>identifier</xip:Type>
            <xip:Value>testValue</xip:Value>
            <xip:Entity>{entityId}</xip:Entity>
          </xip:Identifier>
        </Identifiers>
      </IdentifiersResponse>.toString

    val fragmentOneContent = <Test1>
      <Test1Value>Test1Value</Test1Value>
    </Test1>
    val fragmentOneResponse =
      <MetadataResponse xmlns={namespaceUrl} xmlns:xip={xipUrl} >
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
      <MetadataResponse xmlns={namespaceUrl} xmlns:xip={xipUrl} >
      <MetadataContainer>
        <Content>
          {fragmentTwoContent}
        </Content>
      </MetadataContainer>
    </MetadataResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))
    preservicaServer.stubFor(get(urlEqualTo(identifiersUrl)).willReturn(ok(identifiersResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(fragmentOneUrl)).willReturn(ok(fragmentOneResponse.toString))
    )
    preservicaServer.stubFor(
      get(urlEqualTo(fragmentTwoUrl)).willReturn(ok(fragmentTwoResponse.toString))
    )

    val client = testClient

    val res = client.metadataForEntity(entity)
    val metadata = valueFromF(res)

    metadata.entityNode.toString should equal(
      <InformationObject xmlns="http://preservica.com/EntityAPI/v7.0" xmlns:xip="http://preservica.com/XIP/v7.0">
          <Ref>{entityId}</Ref>
          <Title>Title</Title>
          <Description>A description</Description>
          <SecurityTag>open</SecurityTag>
          <Deleted>true</Deleted>
          <Parent>f567352f-0874-49da-85aa-ac0fbfa3b335</Parent>
        </InformationObject>.toString
    )
    metadata.identifiersNode.toString should equal(
      <xip:Identifiers><xip:Identifier xmlns="http://preservica.com/EntityAPI/v7.0" xmlns:xip="http://preservica.com/XIP/v7.0">
            <xip:ApiId>acb1e74b1ad5c4bfc360ef5d44228c9f</xip:ApiId>
            <xip:Type>identifier</xip:Type>
            <xip:Value>testValue</xip:Value>
            <xip:Entity>{entityId}</xip:Entity>
          </xip:Identifier></xip:Identifiers>.toString
    )
    metadata.metadataContainerNode(0).toString should equal(
      <MetadataContainer xmlns="http://preservica.com/EntityAPI/v7.0" xmlns:xip="http://preservica.com/XIP/v7.0" >
        <Content>
          <Test1>
      <Test1Value>Test1Value</Test1Value>
    </Test1>
        </Content>
      </MetadataContainer>.toString
    )
    metadata.metadataContainerNode.last.toString should equal(
      <MetadataContainer xmlns="http://preservica.com/EntityAPI/v7.0" xmlns:xip="http://preservica.com/XIP/v7.0" >
        <Content>
          <Test2>
      <Test2Value>Test2Value</Test2Value>
    </Test2>
        </Content>
      </MetadataContainer>.toString
    )

    checkServerCall(entityUrl)
    checkServerCall(fragmentOneUrl)
    checkServerCall(fragmentTwoUrl)
  }

  "metadataForEntityUrl" should "return an an empty list when the object has no fragments" in {
    val url = s"http://localhost:$preservicaPort"
    val entityId = UUID.randomUUID()
    val entity = valueFromF(fromType("IO", entityId, Option("title"), Option("description"), deleted = false))
    val entityUrl = s"/api/entity/v$apiVersion/${entity.path.get}/${entity.ref}"
    val identifiersUrl = s"/api/entity/v$apiVersion/${entity.path.get}/identifiers"

    val entityResponse =
      <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <InformationObject>
          <Ref>{entityId}</Ref>
          <Title>Title</Title>
          <Description>A description</Description>
          <SecurityTag>open</SecurityTag>
          <Deleted>true</Deleted>
          <Parent>f567352f-0874-49da-85aa-ac0fbfa3b335</Parent>
        </InformationObject>
        <AdditionalInformation>
          <Metadata>
          </Metadata>
        </AdditionalInformation>
      </EntityResponse>.toString

    val identifiersResponse =
      <IdentifiersResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Identifiers>
          <xip:Identifier>
            <xip:ApiId>acb1e74b1ad5c4bfc360ef5d44228c9f</xip:ApiId>
            <xip:Type>identifier</xip:Type>
            <xip:Value>testValue</xip:Value>
            <xip:Entity>{entityId}</xip:Entity>
          </xip:Identifier>
        </Identifiers>
      </IdentifiersResponse>.toString

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))
    preservicaServer.stubFor(get(urlEqualTo(identifiersUrl)).willReturn(ok(identifiersResponse)))

    val client = testClient

    val res = client.metadataForEntity(entity)
    val metadata = valueFromF(res)

    metadata.entityNode.toString should equal(
      <InformationObject xmlns="http://preservica.com/EntityAPI/v7.0" xmlns:xip="http://preservica.com/XIP/v7.0">
          <Ref>{entityId}</Ref>
          <Title>Title</Title>
          <Description>A description</Description>
          <SecurityTag>open</SecurityTag>
          <Deleted>true</Deleted>
          <Parent>f567352f-0874-49da-85aa-ac0fbfa3b335</Parent>
        </InformationObject>.toString
    )
    metadata.identifiersNode.toString should equal(
      <xip:Identifiers><xip:Identifier xmlns="http://preservica.com/EntityAPI/v7.0" xmlns:xip="http://preservica.com/XIP/v7.0">
            <xip:ApiId>acb1e74b1ad5c4bfc360ef5d44228c9f</xip:ApiId>
            <xip:Type>identifier</xip:Type>
            <xip:Value>testValue</xip:Value>
            <xip:Entity>{entityId}</xip:Entity>
          </xip:Identifier></xip:Identifiers>.toString
    )
    checkServerCall(entityUrl)
  }

  "metadataForEntityUrl" should "return an error if even one metadata response element is empty" in {
    val url = s"http://localhost:$preservicaPort"
    val entityId = UUID.randomUUID()
    val entity = valueFromF(fromType("IO", entityId, Option("title"), Option("description"), deleted = false))
    val entityUrl = s"/api/entity/v$apiVersion/${entity.path.get}/${entity.ref}"
    val identifiersUrl = s"/api/entity/v$apiVersion/${entity.path.get}/identifiers"
    val fragmentOneUrl = s"/api/entity/v$apiVersion/information-objects/$entityId/metadata/${UUID.randomUUID()}"
    val fragmentTwoUrl = s"/api/entity/v$apiVersion/information-objects/$entityId/metadata/${UUID.randomUUID()}"
    val entityResponse =
      <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <InformationObject>
          <Ref>{entityId}</Ref>
          <Title>Title</Title>
          <Description>A description</Description>
          <SecurityTag>open</SecurityTag>
          <Deleted>true</Deleted>
          <Parent>f567352f-0874-49da-85aa-ac0fbfa3b335</Parent>
        </InformationObject>
        <AdditionalInformation>
          <Metadata>
            <Fragment>{s"$url$fragmentOneUrl"}</Fragment>
            <Fragment>{s"$url$fragmentTwoUrl"}</Fragment>
          </Metadata>
        </AdditionalInformation>
      </EntityResponse>.toString

    val identifiersResponse =
      <IdentifiersResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Identifiers>
          <xip:Identifier>
            <xip:ApiId>acb1e74b1ad5c4bfc360ef5d44228c9f</xip:ApiId>
            <xip:Type>identifier</xip:Type>
            <xip:Value>testValue</xip:Value>
            <xip:Entity>{entityId}</xip:Entity>
          </xip:Identifier>
        </Identifiers>
      </IdentifiersResponse>.toString

    val fragmentOneContent = <Test1>
      <Test1Value>Test1Value</Test1Value>
    </Test1>
    val fragmentOneResponse =
      <MetadataResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <MetadataContainer>
          <Content>
            {fragmentOneContent}
          </Content>
        </MetadataContainer>
      </MetadataResponse>

    val fragmentTwoResponse =
      <MetadataResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
      </MetadataResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(entityUrl)).willReturn(ok(entityResponse)))
    preservicaServer.stubFor(get(urlEqualTo(identifiersUrl)).willReturn(ok(identifiersResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(fragmentOneUrl)).willReturn(ok(fragmentOneResponse.toString))
    )
    preservicaServer.stubFor(
      get(urlEqualTo(fragmentTwoUrl)).willReturn(ok(fragmentTwoResponse.toString))
    )

    val client = testClient

    val res = client.metadataForEntity(entity)
    val error = intercept[PreservicaClientException] {
      valueFromF(res)
    }
    error.getMessage should equal(
      "Could not be retrieve all 'MetadataContainer' Nodes from:\n" + """<MetadataResponse xmlns:xip="http://preservica.com/XIP/v7.0" xmlns="http://preservica.com/EntityAPI/v7.0">
                                                                        |        <MetadataContainer>
                                                                        |          <Content>
                                                                        |            <Test1>
                                                                        |      <Test1Value>Test1Value</Test1Value>
                                                                        |    </Test1>
                                                                        |          </Content>
                                                                        |        </MetadataContainer>
                                                                        |      </MetadataResponse>
                                                                        |<MetadataResponse xmlns:xip="http://preservica.com/XIP/v7.0" xmlns="http://preservica.com/EntityAPI/v7.0">
                                                                         |      </MetadataResponse>""".stripMargin
    )

    checkServerCall(entityUrl)
  }

  "metadataForEntityUrl" should "return an error if the server is unavailable" in {
    val tokenUrl = "/api/accesstoken/login"
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(serverError()))
    val entity = valueFromF(fromType("IO", UUID.randomUUID(), Option("title"), Option("description"), deleted = false))
    val client = testClient
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
    val client = testClient
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
    }/api/entity/v$apiVersion/entities/updated-since?date=2023-04-25T00%3A00%3A00.000Z
          &amp;
          start=100
          &amp;
          max=1000</Next>
      </Paging>
    </EntitiesResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/v$apiVersion/entities/updated-since"))
        .withQueryParams(
          Map(
            "date" -> equalTo("2023-04-25T00:00:00.000Z"),
            "max" -> equalTo("1000"),
            "start" -> equalTo("0")
          ).asJava
        )
        .willReturn(ok(pageResult.toString()))
    )

    val client = testClient
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
      get(urlPathMatching(s"/api/entity/v$apiVersion/entities/updated-since"))
        .withQueryParams(
          Map(
            "date" -> equalTo("2023-04-25T00:00:00.000Z"),
            "max" -> equalTo("1000"),
            "start" -> equalTo("0")
          ).asJava
        )
        .willReturn(ok(input.toString()))
    )

    val client = testClient
    val response = valueFromF(client.entitiesUpdatedSince(date, 0))

    response.size should equal(0)
  }

  "entitiesUpdatedSince" should "return an error if the request is malformed" in {
    val date = ZonedDateTime.of(2023, 4, 25, 0, 0, 0, 0, ZoneId.of("UTC"))

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/v$apiVersion/entities/updated-since"))
        .withQueryParams(
          Map(
            "date" -> equalTo("2023-04-25T00:00:00.000Z"),
            "max" -> equalTo("100"),
            "start" -> equalTo("0")
          ).asJava
        )
        .willReturn(badRequest())
    )

    val client = testClient
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
    }/api/entity/v{
      apiVersion
    }/content-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/event-actions?max=1000&amp;start=1000</Next>
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
      get(
        urlPathMatching(s"/api/entity/v$apiVersion/content-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/event-actions")
      )
        .withQueryParams(
          Map(
            "max" -> equalTo("1000"),
            "start" -> equalTo("0")
          ).asJava
        )
        .willReturn(ok(firstPage.toString()))
    )

    preservicaServer.stubFor(
      get(
        urlPathMatching(s"/api/entity/v$apiVersion/content-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/event-actions")
      )
        .withQueryParams(
          Map(
            "max" -> equalTo("1000"),
            "start" -> equalTo("1000")
          ).asJava
        )
        .willReturn(ok(secondPage.toString()))
    )
    val client = testClient
    val response = valueFromF(
      client.entityEventActions(
        Entities.Entity(
          ContentObject.some,
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
      get(
        urlPathMatching(s"/api/entity/v$apiVersion/content-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/event-actions")
      )
        .withQueryParams(
          Map(
            "max" -> equalTo("1000"),
            "start" -> equalTo("0")
          ).asJava
        )
        .willReturn(badRequest())
    )

    val client = testClient
    val response = valueFromF(
      cme.attempt(
        client.entityEventActions(
          Entities.Entity(
            ContentObject.some,
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
    val client = testClient
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

  "entitiesByIdentifier" should "return a complete entity if it has the identifier specified" in {
    val id = "8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91"
    val pageResult = <EntitiesResponse>
      <Entities>
        <Entity title="page1File.txt" ref={id} type="SO">http://localhost/page1/object</Entity>
      </Entities>
      <Paging>
        <Next>http://localhost:{
      preservicaPort
    }/api/entity/v$apiVersion/entities/by-identifier?type=testIdentifier&amp;value=testValue
        </Next>
      </Paging>
    </EntitiesResponse>

    val fullEntityResponse = <EntityResponse>
      <xip:StructuralObject>
        <xip:Ref>{id}</xip:Ref>
        <xip:Title>page1File.txt</xip:Title>
        <xip:Description>A description</xip:Description>
        <xip:SecurityTag>open</xip:SecurityTag>
      </xip:StructuralObject>
    </EntityResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/v$apiVersion/entities/by-identifier"))
        .withQueryParams(
          Map(
            "type" -> equalTo("testIdentifier"),
            "value" -> equalTo("testValue")
          ).asJava
        )
        .willReturn(ok(pageResult.toString()))
    )
    preservicaServer.stubFor(
      get(urlEqualTo(s"/api/entity/v$apiVersion/structural-objects/$id"))
        .willReturn(ok(fullEntityResponse.toString))
    )

    val client = testClient
    val response = valueFromF(
      client.entitiesByIdentifier(Identifier("testIdentifier", "testValue"))
    )

    val expectedEntity = response.head

    expectedEntity.ref.toString should equal("8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91")
    expectedEntity.path.get should equal("structural-objects")
    expectedEntity.title.get should be("page1File.txt")
    expectedEntity.description.get should be("A description")
    expectedEntity.securityTag.get should be(Open)
    expectedEntity.deleted should be(false)
  }

  "entitiesByIdentifier" should "return an empty list if no entities have the identifier specified" in {
    val pageResult = <EntitiesResponse>
      <Entities></Entities>
    </EntitiesResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/v$apiVersion/entities/by-identifier"))
        .withQueryParams(
          Map(
            "type" -> equalTo("testIdentifier"),
            "value" -> equalTo("testValueDoesNotExist")
          ).asJava
        )
        .willReturn(ok(pageResult.toString()))
    )

    val client = testClient
    val response = valueFromF(
      client.entitiesByIdentifier(Identifier("testIdentifier", "testValueDoesNotExist"))
    )

    response.size should equal(0)
  }

  "entitiesByIdentifier" should "return an error if the request is malformed" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlPathMatching(s"/api/entity/v$apiVersion/entities/by-identifier"))
        .withQueryParams(
          Map(
            "type" -> equalTo("testIdentifier"),
            "value" -> equalTo("testValue")
          ).asJava
        )
        .willReturn(badRequest())
    )

    val client = testClient
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
      <IdentifiersResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
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
          <Self>http://mockapi.com/api/entity/v$apiVersion/structural-objects/$ref/identifiers</Self>
        </AdditionalInformation>
      </IdentifiersResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      post(urlEqualTo(s"/api/entity/v$apiVersion/structural-objects/$ref/identifiers")).willReturn(ok(entityResponse))
    )

    val client = testClient
    val addIdentifierForEntityResponse: F[String] =
      client.addIdentifierForEntity(ref, StructuralObject, identifier)

    val _ = valueFromF(addIdentifierForEntityResponse)

    val requestMade = getRequestMade(preservicaServer)

    val expectedXml =
      <Identifier xmlns={xipUrl}>
          <Type>TestIdentifierName</Type>
          <Value>TestIdentifierValue</Value>
        </Identifier>.toString()

    requestMade should be(s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n""" + expectedXml)
  }

  "addIdentifierForEntity" should s"return an exception if the API call does" in {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      post(urlEqualTo(s"/api/entity/v$apiVersion/structural-objects/$ref/identifiers")).willReturn(badRequest())
    )

    val client = testClient
    val addIdentifierForEntityResponse: F[String] =
      client.addIdentifierForEntity(ref, StructuralObject, identifier)

    val error = intercept[PreservicaClientException] {
      valueFromF(addIdentifierForEntityResponse)
    }

    error.getMessage should equal(
      s"Status code 400 calling http://localhost:$preservicaPort/api/entity/v$apiVersion/structural-objects/$ref/identifiers with method POST "
    )
  }

  "addIdentifierForEntity" should s"return a message confirmation if the Identifier was added" in {
    val entityResponse =
      <IdentifiersResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
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
        <Self>http://mockapi.com/api/entity/v$apiVersion/structural-objects/$ref/identifiers</Self>
      </AdditionalInformation>
    </IdentifiersResponse>.toString()

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      post(urlEqualTo(s"/api/entity/v$apiVersion/structural-objects/$ref/identifiers")).willReturn(ok(entityResponse))
    )

    val client = testClient
    val addIdentifierForEntityResponse: F[String] =
      client.addIdentifierForEntity(ref, StructuralObject, identifier)

    val response = valueFromF(addIdentifierForEntityResponse)

    response should be("The Identifier was added")
  }

  "getEntity" should "return the requested entity" in {
    val client = testClient
    val id = UUID.randomUUID()
    val fullEntityResponse = <EntityResponse>
      <xip:StructuralObject>
        <xip:Ref>{id}</xip:Ref>
        <xip:Title>title.txt</xip:Title>
        <xip:Description>A description</xip:Description>
        <xip:SecurityTag>open</xip:SecurityTag>
      </xip:StructuralObject>
    </EntityResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(s"/api/entity/v$apiVersion/structural-objects/$id"))
        .willReturn(ok(fullEntityResponse.toString))
    )
    val entity = valueFromF(client.getEntity(id, StructuralObject))

    entity.entityType.get should be(StructuralObject)
    entity.ref should be(id)
    entity.title.get should be("title.txt")
    entity.description.get should be("A description")
    entity.securityTag.get should be(Open)
  }

  "getEntity" should "return an error if the returned entity has a different entity type" in {
    val client = testClient
    val id = UUID.randomUUID()
    val fullEntityResponse = <EntityResponse>
      <xip:StructuralObject>
        <xip:Ref>
          {id}
        </xip:Ref>
        <xip:Title>title.txt</xip:Title>
        <xip:Description>A description</xip:Description>
        <xip:SecurityTag>open</xip:SecurityTag>
      </xip:StructuralObject>
    </EntityResponse>

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(s"/api/entity/v$apiVersion/information-objects/$id"))
        .willReturn(ok(fullEntityResponse.toString))
    )
    val ex = intercept[PreservicaClientException] {
      valueFromF(client.getEntity(id, InformationObject))
    }
    ex.getMessage should be(s"Entity type 'InformationObject' not found for id $id")
  }

  "getEntity" should "return an error if the entity is not found" in {
    val host = s"http://localhost:$preservicaPort"
    val client = testClient
    val id = UUID.randomUUID()
    val getUrl = s"/api/entity/v$apiVersion/information-objects/$id"

    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(getUrl))
        .willReturn(notFound())
    )
    val ex = intercept[PreservicaClientException] {
      valueFromF(client.getEntity(id, InformationObject))
    }
    ex.getMessage should be(s"Status code 404 calling $host$getUrl with method GET ")
  }

  "updateEntityIdentifiers" should "not send a request if no identifiers are passed" in {
    val client = testClient
    val entity = createEntity()
    valueFromF(client.updateEntityIdentifiers(entity, Nil))
    preservicaServer.getAllServeEvents.size() should equal(0)
  }

  "updateEntityIdentifiers" should "send a request to Preservica for each identifier" in {
    val response = <IdentifierResponse></IdentifierResponse>
    val entity: Entity = createEntity()
    val client = testClient
    val identifiers = List(
      IdentifierResponse("1", "Name1", "Value1"),
      IdentifierResponse("2", "Name2", "Value2")
    )
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    identifiers.foreach { identifier =>
      preservicaServer.stubFor(
        put(urlEqualTo(s"/api/entity/v$apiVersion/structural-objects/${entity.ref}/identifiers/${identifier.id}"))
          .willReturn(ok(response.toString))
      )
    }
    valueFromF(client.updateEntityIdentifiers(entity, identifiers))

    val putEvents = preservicaServer.getAllServeEvents.asScala
      .filter { e =>
        e.getRequest.getMethod == RequestMethod.PUT && e.getRequest.getUrl.startsWith(
          s"/api/entity/v$apiVersion/structural-objects/${entity.ref}/identifiers/"
        )

      }
    putEvents.size should equal(2)

    def checkEvent(serveEvent: ServeEvent, id: String) = {
      val xml = XML.loadString(serveEvent.getRequest.getBodyAsString)

      (xml \ "Type").text should equal(s"Name$id")
      (xml \ "Value").text should equal(s"Value$id")
      serveEvent.getRequest.getUrl.endsWith(s"/$id") should be(true)
    }
    checkEvent(putEvents.head, "2")
    checkEvent(putEvents.last, "1")
  }

  "updateEntityIdentifiers" should "return an error if the entity path is missing" in {
    val client = testClient
    val entity = createEntity().copy(path = None)
    val identifiers = List(IdentifierResponse("1", "Name1", "Value1"))
    val ex = intercept[PreservicaClientException] {
      valueFromF(client.updateEntityIdentifiers(entity, identifiers))
    }
    ex.getMessage should equal(s"No path found for entity id ${entity.ref}. Could this entity have been deleted?")
  }

  "updateEntityIdentifiers" should "return an error if the update request returns an error" in {
    val client = testClient
    val entity = createEntity()
    val identifiers = List(IdentifierResponse("1", "Name1", "Value1"))
    val url = s"/api/entity/v$apiVersion/structural-objects/${entity.ref}/identifiers/1"
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      put(urlEqualTo(url))
        .willReturn(serverError())
    )
    val ex = intercept[PreservicaClientException] {
      valueFromF(client.updateEntityIdentifiers(entity, identifiers))
    }
    ex.getMessage should equal(s"Status code 500 calling http://localhost:$preservicaPort$url with method PUT ")
  }

  "getEntityIdentifiers" should "return an empty list if there are no identifiers" in {
    val client = testClient
    val entity = createEntity()
    val response = <IdentifiersResponse></IdentifiersResponse>
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(s"/api/entity/v$apiVersion/structural-objects/${entity.ref}/identifiers"))
        .willReturn(ok(response.toString))
    )
    val identifiers = valueFromF(client.getEntityIdentifiers(entity))
    identifiers.size should equal(0)
  }

  "getEntityIdentifiers" should "return the identifiers for an entity" in {
    val client = testClient
    val entity = createEntity()
    val response =
      <IdentifiersResponse>
      <Identifiers>
        <Identifier>
          <xip:ApiId>1</xip:ApiId>
          <xip:Type>Test Type 1</xip:Type>
          <xip:Value>Test Value 1</xip:Value>
          <xip:Entity>{entity.ref.toString}</xip:Entity>
        </Identifier>
        <Identifier>
          <xip:ApiId>2</xip:ApiId>
          <xip:Type>Test Type 2</xip:Type>
          <xip:Value>Test Value 2</xip:Value>
          <xip:Entity>{entity.ref.toString}</xip:Entity>
        </Identifier>
      </Identifiers>
    </IdentifiersResponse>
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(s"/api/entity/v$apiVersion/structural-objects/${entity.ref}/identifiers"))
        .willReturn(ok(response.toString))
    )
    val identifiers = valueFromF(client.getEntityIdentifiers(entity)).sortBy(_.id)
    identifiers.size should equal(2)

    def checkIdentifier(identifier: IdentifierResponse, id: String) = {
      identifier.identifierName should equal(s"Test Type $id")
      identifier.value should equal(s"Test Value $id")
      identifier.id should equal(id)
    }
    checkIdentifier(identifiers.head, "1")
    checkIdentifier(identifiers.last, "2")
  }

  private def createEntity(entityType: EntityType = StructuralObject): Entity = {
    Entities.Entity(
      entityType.some,
      UUID.fromString("a9e1cae8-ea06-4157-8dd4-82d0525b031c"),
      None,
      None,
      deleted = false,
      entityType.entityPath.some
    )
  }

  "getEntityIdentifiers" should "return an error if the entity path is missing" in {
    val client = testClient
    val entity = createEntity().copy(path = None)
    val ex = intercept[PreservicaClientException] {
      valueFromF(client.getEntityIdentifiers(entity))
    }
    ex.getMessage should equal(s"No path found for entity id ${entity.ref}. Could this entity have been deleted?")
  }

  "getEntityIdentifiers" should "return an error if the get request returns an error" in {
    val client = testClient
    val entity = createEntity()
    val url = s"/api/entity/v$apiVersion/structural-objects/${entity.ref}/identifiers"
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(url))
        .willReturn(serverError())
    )
    val ex = intercept[PreservicaClientException] {
      valueFromF(client.getEntityIdentifiers(entity))
    }
    ex.getMessage should equal(s"Status code 500 calling http://localhost:$preservicaPort$url with method GET ")
  }

  "getUrlsToIoRepresentations" should "return an empty list if there are no representations" in {
    val client = testClient
    val entity = createEntity(InformationObject)
    val response = <RepresentationResponse></RepresentationResponse>
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(s"/api/entity/v$apiVersion/information-objects/${entity.ref}/representations"))
        .willReturn(ok(response.toString))
    )
    val urls = valueFromF(client.getUrlsToIoRepresentations(entity.ref, Some(Preservation)))
    urls.size should equal(0)
  }

  "getUrlsToIoRepresentations" should "return the url of a Preservation representation" in {
    val client = testClient
    val entity = createEntity(InformationObject)

    val response =
      <RepresentationsResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Representations>
          <Representation type="Preservation">http://localhost/api/entity/v$apiVersion/information-objects/{
        entity.ref
      }/representations/Preservation/1</Representation>
          <Representation type="Access" name="Access name1">http://localhost/api/entity/v$apiVersion/information-objects/{
        entity.ref
      }/representations/Access/1</Representation>
          <Representation type="Access" name="Access name2">http://localhost/api/entity/v$apiVersion/information-objects/{
        entity.ref
      }/representations/Access/2</Representation>
        </Representations>
        <Paging>
          <TotalResults>3</TotalResults>
        </Paging>
        <AdditionalInformation>
          <Self>http://localhost/api/entity/v$apiVersion/information-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/representations</Self>
        </AdditionalInformation>
      </RepresentationsResponse>
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(s"/api/entity/v$apiVersion/information-objects/${entity.ref}/representations"))
        .willReturn(ok(response.toString))
    )
    val urls = valueFromF(client.getUrlsToIoRepresentations(entity.ref, Some(Preservation)))

    urls.size should equal(1)
    urls should equal(
      Seq(
        "http://localhost/api/entity/v$apiVersion/information-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/representations/Preservation/1"
      )
    )
  }

  "getUrlsToIoRepresentations" should "return a url for each representation if 'representationType' filter passed in, was 'None'" in {
    val client = testClient
    val entity = createEntity(InformationObject)

    val response =
      <RepresentationsResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Representations>
          <Representation type="Preservation">http://localhost/api/entity/v$apiVersion/information-objects/{
        entity.ref
      }/representations/Preservation/1</Representation>
          <Representation type="Access" name="Access name1">http://localhost/api/entity/v$apiVersion/information-objects/{
        entity.ref
      }/representations/Access/1</Representation>
          <Representation type="Access" name="Access name2">http://localhost/api/entity/v$apiVersion/information-objects/{
        entity.ref
      }/representations/Access/2</Representation>
        </Representations>
        <Paging>
          <TotalResults>3</TotalResults>
        </Paging>
        <AdditionalInformation>
          <Self>http://localhost/api/entity/v$apiVersion/information-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/representations</Self>
        </AdditionalInformation>
      </RepresentationsResponse>
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(s"/api/entity/v$apiVersion/information-objects/${entity.ref}/representations"))
        .willReturn(ok(response.toString))
    )
    val urls = valueFromF(client.getUrlsToIoRepresentations(entity.ref, None))

    urls.size should equal(3)
    urls should equal(
      Seq(
        "http://localhost/api/entity/v$apiVersion/information-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/representations/Preservation/1",
        "http://localhost/api/entity/v$apiVersion/information-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/representations/Access/1",
        "http://localhost/api/entity/v$apiVersion/information-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/representations/Access/2"
      )
    )
  }

  "getUrlsToIoRepresentations" should "return an error if the get request returns an error" in {
    val client = testClient
    val entity = createEntity(InformationObject)
    val url = s"/api/entity/v$apiVersion/information-objects/${entity.ref}/representations"
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(url))
        .willReturn(serverError())
    )
    val ex = intercept[PreservicaClientException] {
      valueFromF(client.getUrlsToIoRepresentations(entity.ref, None))
    }
    ex.getMessage should equal(s"Status code 500 calling http://localhost:$preservicaPort$url with method GET ")
  }

  "getContentObjectsFromRepresentation" should "return an empty list if there are no representations" in {
    val client = testClient
    val entity = createEntity(InformationObject)
    val response =
      <RepresentationResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <xip:Representation>
          <xip:InformationObject>14e54a24-db26-4c00-852c-f28045e51828</xip:InformationObject>
          <xip:Name>Preservation</xip:Name>
          <xip:Type>Preservation</xip:Type>
          <xip:ContentObjects/>
          <xip:RepresentationFormats/>
          <xip:RepresentationProperties/>
        </xip:Representation>
        <ContentObjects/>
        <AdditionalInformation>
          <Self>http://localhost/api/entity/v$apiVersion/information-objects/14e54a24-db26-4c00-852c-f28045e51828/representations/Preservation/1</Self>
        </AdditionalInformation>
      </RepresentationResponse>
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(s"/api/entity/v$apiVersion/information-objects/${entity.ref}/representations/Preservation/1"))
        .willReturn(ok(response.toString))
    )
    val contentObjects = valueFromF(client.getContentObjectsFromRepresentation(entity.ref, Preservation, 1))
    contentObjects.size should equal(0)
  }

  "getContentObjectsFromRepresentation" should "return the Content Objects of a Preservation Representation" in {
    val client = testClient
    val entity = createEntity(InformationObject)

    val response =
      <RepresentationResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <xip:Representation>
          <xip:InformationObject>14e54a24-db26-4c00-852c-f28045e51828</xip:InformationObject>
          <xip:Name>Preservation</xip:Name>
          <xip:Type>Preservation</xip:Type>
          <xip:ContentObjects>
            <xip:ContentObject>ad30d41e-b75c-4195-b569-91e820f430ac</xip:ContentObject>
            <xip:ContentObject>354f47cf-3ca2-4a4e-8181-81b714334f00</xip:ContentObject>
          </xip:ContentObjects>
          <xip:RepresentationFormats/>
          <xip:RepresentationProperties/>
        </xip:Representation>
        <ContentObjects/>
        <AdditionalInformation>
          <Self>http://localhost/api/entity/v$apiVersion/information-objects/14e54a24-db26-4c00-852c-f28045e51828/representations/Preservation/1</Self>
        </AdditionalInformation>
      </RepresentationResponse>
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(s"/api/entity/v$apiVersion/information-objects/${entity.ref}/representations/Preservation/1"))
        .willReturn(ok(response.toString))
    )
    val contentObjects = valueFromF(client.getContentObjectsFromRepresentation(entity.ref, Preservation, 1))

    contentObjects.size should equal(2)
    contentObjects should equal(
      List(
        Entity(
          Some(ContentObject),
          UUID.fromString("ad30d41e-b75c-4195-b569-91e820f430ac"),
          None,
          None,
          false,
          Some(ContentObject.entityPath),
          None,
          Some(UUID.fromString("a9e1cae8-ea06-4157-8dd4-82d0525b031c"))
        ),
        Entity(
          Some(ContentObject),
          UUID.fromString("354f47cf-3ca2-4a4e-8181-81b714334f00"),
          None,
          None,
          false,
          Some(ContentObject.entityPath),
          None,
          Some(UUID.fromString("a9e1cae8-ea06-4157-8dd4-82d0525b031c"))
        )
      )
    )
  }

  "getContentObjectsFromRepresentation" should "return an error if the get request returns an error" in {
    val client = testClient
    val entity = createEntity(InformationObject)
    val url = s"/api/entity/v$apiVersion/information-objects/${entity.ref}/representations/Preservation/1"
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(
      get(urlEqualTo(url))
        .willReturn(serverError())
    )
    val ex = intercept[PreservicaClientException] {
      valueFromF(client.getContentObjectsFromRepresentation(entity.ref, Preservation, 1))
    }
    ex.getMessage should equal(s"Status code 500 calling http://localhost:$preservicaPort$url with method GET ")
  }

  "getPreservicaNamespaceVersion" should "extract and return the version, as a float, from a namespace" in {
    val client = testClient
    val endpoint = "retention-policies"
    val response =
      <RetentionPoliciesResponse xmlns="http://preservica.com/EntityAPI/v7.0" xmlns:xip="http://preservica.com/XIP/v6.9" xmlns:retention="http://preservica.com/RetentionManagement/v6.2">
      </RetentionPoliciesResponse>
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))
    preservicaServer.stubFor(get(urlEqualTo(s"/api/entity/retention-policies")).willReturn(ok(response.toString)))

    val version = valueFromF(client.getPreservicaNamespaceVersion(endpoint))
    version should equal(7.0)
  }

  private def getRequestMade(preservicaServer: WireMockServer) =
    preservicaServer.getServeEvents.getServeEvents.get(0).getRequest.getBodyAsString
}
