package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Async
import cats.implicits.catsSyntaxOptionId
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.stubbing.{Scenario, ServeEvent}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.prop.TableDrivenPropertyChecks.forAll
import org.scalatest.prop.TableFor2
import org.scalatest.prop.Tables.Table
import org.scalatest.{Assertion, BeforeAndAfterEach}
import sttp.capabilities.Streams
import uk.gov.nationalarchives.dp.client.Client.*
import uk.gov.nationalarchives.dp.client.Entities.EntityRef.*
import uk.gov.nationalarchives.dp.client.Entities.{Entity, EntityRef, IdentifierResponse, fromType}
import uk.gov.nationalarchives.dp.client.EntityClient.*
import uk.gov.nationalarchives.dp.client.EntityClient.EntityType.*
import uk.gov.nationalarchives.dp.client.EntityClient.GenerationType.*
import uk.gov.nationalarchives.dp.client.EntityClient.RepresentationType.*
import uk.gov.nationalarchives.dp.client.EntityClient.SecurityTag.*
import uk.gov.nationalarchives.dp.client.utils.MockPreservicaAPI
import uk.gov.nationalarchives.dp.client.utils.MockPreservicaAPI.*

import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.xml.{Elem, Utility, XML}

abstract class EntityClientTest[F[_]: Async, S](preservicaPort: Int, secretsManagerPort: Int, stream: Streams[S])(using
    cme: MonadError[F, Throwable]
) extends AnyFlatSpec
    with BeforeAndAfterEach {

  val zeroSeconds: FiniteDuration = FiniteDuration(0, TimeUnit.SECONDS)
  val secretsManagerServer = new WireMockServer(secretsManagerPort)
  val secretsResponse =
    s"""{"SecretString":"{\\"userName\\":\\"test\\",\\"password\\":\\"test\\",\\"apiUrl\\":\\"http://localhost:$preservicaPort\\"}"}"""
  val preservicaServer = new WireMockServer(preservicaPort)

  val updateRequestPermutations: TableFor2[String, Option[String]] = Table(
    ("title", "description"),
    ("page1File&Correction.txt", Some("A new description")),
    ("page1File&Correction.txt", None)
  )

  private val entityShortNameToLong =
    Map("SO" -> "StructuralObject", "IO" -> "InformationObject", "CO" -> "ContentObject")
  private val identifier = Identifier(
    "TestIdentifierName",
    "TestIdentifierValue"
  )
  private val defaultAddEntityRequest = AddEntityRequest(
    Some(UUID.randomUUID()),
    "page1File&Correction.txt",
    Some("A description"),
    StructuralObject,
    Unknown,
    Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
  )
  private val defaultUpdateEntityRequest = UpdateEntityRequest(
    UUID.fromString("6380a397-294b-4b02-990f-db5fc20b113f"),
    "page1File&Correction.txt",
    Some("A new description"),
    StructuralObject,
    Unknown,
    Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1"))
  )

  def valueFromF[T](value: F[T]): T

  def createClient(): F[EntityClient[F, S]]

  def testClient: EntityClient[F, S] = valueFromF(createClient())

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

  private def serverRequestUrls =
    preservicaServer.getAllServeEvents.asScala.map(_.getRequest.getUrl).toList

  private def verifyServerRequests(
      additionalExpectedRequestUrls: List[List[String] | String],
      numOfTokenRequests: Int = 1
  ) = {
    val expectedRequestUrlsFlattened: List[String] = additionalExpectedRequestUrls.flatMap {
      case requests: List[String] => requests
      case request: String        => List(request)
    }
    serverRequestUrls.sorted.toSet should equal(
      (expectedRequestUrlsFlattened ++ List.fill(numOfTokenRequests)(tokenUrl)).sorted.toSet
    )
  }

  private def verifyZeroServerRequests = serverRequestUrls.length should be(0)

  List((Some(createEntity()), "with"), (None, "without")).foreach { case (potentialEntity, withOrWithout) =>
    "addEntity" should s"make a correct request $withOrWithout a predefined reference to add an entity" in {
      val (addEntityRequest, addEntityUrl) =
        potentialEntity match {
          case None =>
            (defaultAddEntityRequest.copy(ref = None), EntityClientEndpoints(preservicaServer).stubAddEntity())
          case Some(entity) =>
            (
              defaultAddEntityRequest.copy(ref = Some(entity.ref)),
              EntityClientEndpoints(preservicaServer, potentialEntity).stubAddEntity()
            )
        }

      val client = testClient
      val addEntityResponse: F[UUID] = client.addEntity(addEntityRequest)

      val _ = valueFromF(addEntityResponse)

      val requestMade = getRequestMade(preservicaServer)
      requestMade should be(
        s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
++++++++++++
            <StructuralObject xmlns="http://preservica.com/XIP/v$xipVersion">
              ${addEntityRequest.ref.map(ref => s"<Ref>$ref</Ref>").getOrElse("")}
              <Title>page1File&amp;Correction.txt</Title>
              <Description>A description</Description>
              <SecurityTag>unknown</SecurityTag>
              <Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</Parent>
            </StructuralObject>""".replace("++++++++++++", "            ")
      )
      verifyServerRequests(List(addEntityUrl))
    }
  }

  "addEntity" should s"make a correct request with the object details inside a XIP node if entity path to add is an 'information object'" in {
    val informationObject = createEntity(InformationObject)
    val addEntityRequest = defaultAddEntityRequest.copy(entityType = informationObject.entityType.get)
    val addEntityUrl = EntityClientEndpoints(preservicaServer, Some(informationObject)).stubAddEntity()

    val client = testClient
    val addEntityResponse: F[UUID] = client.addEntity(addEntityRequest)

    val _ = valueFromF(addEntityResponse)

    val requestMade = getRequestMade(preservicaServer)
    requestMade should be(
      s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <XIP xmlns="http://preservica.com/XIP/v$xipVersion">
            <InformationObject xmlns="http://preservica.com/XIP/v$xipVersion">
              <Ref>${addEntityRequest.ref.get}</Ref>
              <Title>page1File&amp;Correction.txt</Title>
              <Description>A description</Description>
              <SecurityTag>unknown</SecurityTag>
              <Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</Parent>
            </InformationObject>
            </XIP>"""
    )
    verifyServerRequests(List(addEntityUrl))
  }

  "addEntity" should "return an error if a content-object entity path was passed in" in {
    val addEntityRequest = defaultAddEntityRequest.copy(entityType = ContentObject)

    val client = testClient
    val addEntityResponse: F[UUID] = client.addEntity(addEntityRequest)

    val error = intercept[PreservicaClientException] {
      valueFromF(addEntityResponse)
    }

    error.getMessage should be("You currently cannot create a content object via the API.")
    verifyZeroServerRequests
  }

  "addEntity" should "return an error if a non-structural object was passed in without a parent" in {
    val client = testClient
    val addEntityRequest = defaultAddEntityRequest.copy(entityType = InformationObject, parentRef = None)
    val addEntityResponse: F[UUID] = client.addEntity(addEntityRequest)

    val error = intercept[PreservicaClientException] {
      valueFromF(addEntityResponse)
    }

    error.getMessage should be(
      "You must pass in the parent ref if you would like to add/update a non-structural object."
    )
    verifyZeroServerRequests
  }

  "addEntity" should s"return a message confirmation if the object got added" in {
    val structuralObject = createEntity()
    val addEntityRequest = defaultAddEntityRequest.copy(ref = Some(structuralObject.ref))
    val addEntityUrl = EntityClientEndpoints(preservicaServer, Some(structuralObject)).stubAddEntity()

    val client = testClient
    val addEntityResponse: F[UUID] = client.addEntity(addEntityRequest)

    val response = valueFromF(addEntityResponse)
    response should be(addEntityRequest.ref.get)
    verifyServerRequests(List(addEntityUrl))
  }

  "addEntity" should s"return an exception if the API call does" in {
    val addEntityUrl = EntityClientEndpoints(preservicaServer).stubAddEntity(false)

    val client = testClient

    val error = intercept[PreservicaClientException] {
      valueFromF(client.addEntity(defaultAddEntityRequest))
    }

    error.getMessage should equal(
      s"Status code 400 calling http://localhost:$preservicaPort/api/entity/v$apiVersion/structural-objects with method POST "
    )
    verifyServerRequests(List(addEntityUrl, addEntityUrl))
  }

  forAll(updateRequestPermutations) { (title, potentialDescription) =>
    {
      val indexesOfChanges = List(Some(0), potentialDescription.map(_ => 1)).flatten
      val nodesToUpdate = indexesOfChanges.map(index => updateRequestPermutations.heading.productElement(index))

      "updateEntity" should s"make a correct request to update the ${nodesToUpdate.mkString(" and ")}" in {
        val structuralObject = createEntity()
        val updateEntityRequest =
          defaultUpdateEntityRequest.copy(
            ref = structuralObject.ref,
            title = title,
            descriptionToChange = potentialDescription
          )

        val updateEntityUrl = EntityClientEndpoints(preservicaServer, Some(structuralObject)).stubUpdateEntity()

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
              <SecurityTag>unknown</SecurityTag>
              <Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</Parent>
            </StructuralObject>""".replace("++++++++++++", "            ")
        )
        verifyServerRequests(List(updateEntityUrl))
      }
    }
  }

  "updateEntity" should "return an error if a non-structural object was passed in without a parent" in {
    val client = testClient
    val updateEntityRequest = defaultUpdateEntityRequest.copy(entityType = InformationObject, parentRef = None)

    val updateEntityResponse: F[String] = client.updateEntity(updateEntityRequest)

    val error = intercept[PreservicaClientException] {
      valueFromF(updateEntityResponse)
    }
    error.getMessage should be(
      "You must pass in the parent ref if you would like to add/update a non-structural object."
    )
    verifyZeroServerRequests
  }

  "updateEntity" should s"return a message confirmation if the object got updated" in {
    val structuralObject = createEntity()
    val updateEntityUrl = EntityClientEndpoints(preservicaServer, Some(structuralObject)).stubUpdateEntity()

    val client = testClient
    val updateEntityResponse: F[String] =
      client.updateEntity(defaultUpdateEntityRequest.copy(ref = structuralObject.ref))

    val response = valueFromF(updateEntityResponse)
    response should be("Entity was updated")
    verifyServerRequests(List(updateEntityUrl))
  }

  "updateEntity" should s"return an exception if the API call does" in {
    val structuralObject = createEntity()
    val updateEntityUrl = EntityClientEndpoints(preservicaServer, Some(structuralObject)).stubUpdateEntity(false)

    val client = testClient

    val error = intercept[PreservicaClientException] {
      valueFromF(client.updateEntity(defaultUpdateEntityRequest.copy(ref = structuralObject.ref)))
    }
    error.getMessage should equal(
      s"Status code 400 calling http://localhost:$preservicaPort/api/entity/v$apiVersion/structural-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c with method PUT "
    )
    verifyServerRequests(List(updateEntityUrl, updateEntityUrl))
  }

  "getBitstreamInfo" should "call the correct API endpoints and return the bitstream info" in {
    val entity = createEntity(ContentObject)
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))

    val requestUrls: List[List[String] | String] = List(
      endpoints.stubGetEntity(),
      endpoints.stubCoGenerations(),
      endpoints.stubCoGeneration(),
      endpoints.stubCoBitstreamInfo()
    )

    val client = testClient
    val response: F[Seq[BitStreamInfo]] = client.getBitstreamInfo(entity.ref)

    val bitStreamInfo = valueFromF(response)
    bitStreamInfo.head.url should equal(
      s"http://localhost:9002/api/entity/v7.7/content-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/generations/1/bitstreams/1/content"
    )
    bitStreamInfo.head.name should equal("test1.txt")
    bitStreamInfo.head.fileSize should equal(1234)
    bitStreamInfo.head.fixities.size should equal(2)
    bitStreamInfo.head.fixities.find(_.algorithm == "SHA1").get.value should equal(
      "0c16735b03fe46b931060858e8cd5ca9c5101565"
    )
    bitStreamInfo.head.fixities.find(_.algorithm == "MD5").get.value should equal("4985298cbf6b2b74c522ced8b128ebe3")
    bitStreamInfo.head.generationVersion should equal(1)
    bitStreamInfo.head.generationType should equal(Original)
    bitStreamInfo.head.potentialCoTitle should equal(Some("page1File.txt"))
    bitStreamInfo.head.parentRef should equal(Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1")))

    bitStreamInfo.last.url should equal(
      s"http://localhost:9002/api/entity/v7.7/content-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/generations/2/bitstreams/1/content"
    )
    bitStreamInfo.last.name should equal("test1.txt")
    bitStreamInfo.last.fileSize should equal(1234)
    bitStreamInfo.last.fixities.size should equal(2)
    bitStreamInfo.last.fixities.find(_.algorithm == "SHA1").get.value should equal(
      "0c16735b03fe46b931060858e8cd5ca9c5101565"
    )
    bitStreamInfo.last.fixities.find(_.algorithm == "MD5").get.value should equal("4985298cbf6b2b74c522ced8b128ebe3")
    bitStreamInfo.last.generationVersion should equal(2)
    bitStreamInfo.last.generationType should equal(Derived)
    bitStreamInfo.last.potentialCoTitle should equal(Some("page1File.txt"))
    bitStreamInfo.last.parentRef should equal(Some(UUID.fromString("58412111-c73d-4414-a8fc-495cfc57f7e1")))

    verifyServerRequests(requestUrls)
  }

  "getBitstreamInfo" should "return an error if no generations are available" in {
    val entity = createEntity(ContentObject)
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    val getEntityUrl = endpoints.stubGetEntity(emptyResponse = true)

    val client = testClient
    val response: F[Seq[BitStreamInfo]] = client.getBitstreamInfo(entity.ref)

    val expectedError = valueFromF(cme.attempt(response))

    expectedError.isLeft should be(true)
    expectedError.left.map { err =>
      err.getMessage should equal("Generation URL not found")
    }
    verifyServerRequests(List(getEntityUrl))
  }

  "getBitstreamInfo" should "return an error if the server is unavailable" in {
    val endpoints = EntityClientEndpoints(preservicaServer)
    endpoints.serverErrorStub(MockPreservicaAPI.tokenUrl)

    val client = testClient
    val response = client.getBitstreamInfo(UUID.randomUUID())

    val expectedError = valueFromF(cme.attempt(response))

    expectedError.isLeft should be(true)
    expectedError.left.map { err =>
      err.getMessage should equal(
        s"Status code 500 calling http://localhost:$preservicaPort/api/accesstoken/login with method POST statusCode: 500, response: "
      )
    }
    verifyServerRequests(Nil)
  }

  "streamBitstreamContent" should "stream content to the provided function" in {
    val endpoints = EntityClientEndpoints(preservicaServer)
    val bitstreamUrl = endpoints.bitstreamOneContentUrl
    endpoints.stubStreamBitstreamContent()
    val bitstreamUrlToStreamFrom = endpoints.preservicaUrl + bitstreamUrl

    val client = testClient

    val response =
      client.streamBitstreamContent[String](stream)(bitstreamUrlToStreamFrom, _ => cme.pure(s"Test return value"))
    val responseValue = valueFromF(response)
    responseValue should equal("Test return value")
    verifyServerRequests(List(bitstreamUrl))
  }

  "streamBitstreamContent" should "return an error if the server is unavailable" in {
    val endpoints = EntityClientEndpoints(preservicaServer)
    endpoints.serverErrorStub(MockPreservicaAPI.tokenUrl)

    val client = testClient
    val response =
      client.streamBitstreamContent[Unit](stream)(s"https://unusedTestUrl.com", _ => cme.unit)

    val expectedError = valueFromF(cme.attempt(response))

    expectedError.isLeft should be(true)
    expectedError.left.map { err =>
      err.getMessage should equal(
        s"Status code 500 calling http://localhost:$preservicaPort/api/accesstoken/login with method POST statusCode: 500, response: "
      )
    }
    verifyServerRequests(Nil)
  }

  "metadataForEntity" should "return the correct metadata for a CO" in {
    val entity = createEntity(ContentObject)
    val client = testClient
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))

    val requestUrls = List(
      endpoints.stubGetEntity(),
      endpoints.stubGetIdentifierForEntity(),
      endpoints.stubMetadataFragment(1, endpoints.metadataFragmentOneUrl),
      endpoints.stubMetadataFragment(2, endpoints.metadataFragmentTwoUrl),
      endpoints.stubLinks(),
      endpoints.stubCoGenerations(),
      endpoints.stubCoGeneration(),
      endpoints.stubCoBitstreamInfo(),
      endpoints.stubEventActions()
    )

    val res = client.metadataForEntity(entity)
    val metadata: CoMetadata = valueFromF(res).asInstanceOf[CoMetadata]

    metadata.entityNode.toString should equal(
      <xip:ContentObject xmlns="http://preservica.com/EntityAPI/v7.7" xmlns:xip="http://preservica.com/XIP/v7.7">
              <xip:Ref>{entity.ref}</xip:Ref>
            <xip:Title>page1File.txt</xip:Title>
            <xip:Description>A description</xip:Description>
            <xip:SecurityTag>unknown</xip:SecurityTag>
            <xip:Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</xip:Parent>
            </xip:ContentObject>.toString
    )
    metadata.identifiers.head.toString should equal(
      <Identifier xmlns="http://preservica.com/EntityAPI/v7.7" xmlns:xip="http://preservica.com/XIP/v7.7">
            <xip:ApiId>acb1e74b1ad5c4bfc360ef5d44228c9f</xip:ApiId>
            <xip:Type>Test Type 1</xip:Type>
            <xip:Value>Test Value 1</xip:Value>
            <xip:Entity>{entity.ref}</xip:Entity>
          </Identifier>.toString
    )
    Utility.trim(metadata.links.head) should equal(
      Utility.trim(
        <xip:Link xmlns:xip="http://preservica.com/XIP/v7.7" xmlns="http://preservica.com/EntityAPI/v7.7"><xip:Type>VirtualChild</xip:Type><xip:ToEntity>{
          entity.ref.toString
        }</xip:ToEntity><xip:FromEntity>758ef5c5-a364-40e5-bd78-e40f72f5a1f0</xip:FromEntity></xip:Link>
      )
    )
    Utility.trim(metadata.links.last) should equal(
      Utility.trim(
        <xip:Link xmlns:xip="http://preservica.com/XIP/v7.7" xmlns="http://preservica.com/EntityAPI/v7.7"><xip:Type>CitedBy</xip:Type><xip:ToEntity>866d4c6e-ee51-467a-b7a3-e4b65709cf95</xip:ToEntity><xip:FromEntity>{
          entity.ref.toString
        }</xip:FromEntity></xip:Link>
      )
    )
    metadata.metadataNodes.head.toString should equal(
      <Metadata xmlns:xip="http://preservica.com/XIP/v7.7" xmlns="http://preservica.com/EntityAPI/v7.7">
            <Content>
              <Test1>
                <Test1Value>Test1Value</Test1Value>
              </Test1>
            </Content>
          </Metadata>.toString
    )
    metadata.metadataNodes.last.toString should equal(
      <Metadata xmlns:xip="http://preservica.com/XIP/v7.7" xmlns="http://preservica.com/EntityAPI/v7.7">
            <Content>
              <Test2>
                <Test2Value>Test2Value</Test2Value>
              </Test2>
            </Content>
          </Metadata>.toString
    )

    metadata.eventActions.head.toString should equal(
      <xip:EventAction commandType="command_create">
            <xip:Event type="Ingest">
              <xip:Ref>6da319fa-07e0-4a83-9c5a-b6bad08445b1</xip:Ref>
              <xip:Date>2023-06-26T08:14:08.441Z</xip:Date>
              <xip:User>test user</xip:User>
            </xip:Event>
            <xip:Date>2023-06-26T08:14:07.441Z</xip:Date>
            <xip:Entity>a9e1cae8-ea06-4157-8dd4-82d0525b031c</xip:Entity>
          </xip:EventAction>.toString
    )

    metadata.generationNodes.head.toString should equal(
      <Generation original="true" active="true" xmlns="http://preservica.com/EntityAPI/v7.7" xmlns:xip="http://preservica.com/XIP/v7.7" >
        </Generation>.toString
    )

    Utility.trim(metadata.bitstreamNodes.head).toString should equal(
      Utility
        .trim(<xip:Bitstream>
        <xip:Filename>test1.txt</xip:Filename>
        <xip:FileSize>1234</xip:FileSize>
        <xip:Fixities>
          <xip:Fixity>
            <xip:FixityAlgorithmRef>MD5</xip:FixityAlgorithmRef>
            <xip:FixityValue>4985298cbf6b2b74c522ced8b128ebe3</xip:FixityValue>
          </xip:Fixity>
          <xip:Fixity>
            <xip:FixityAlgorithmRef>SHA1</xip:FixityAlgorithmRef>
            <xip:FixityValue>0c16735b03fe46b931060858e8cd5ca9c5101565</xip:FixityValue>
          </xip:Fixity>
        </xip:Fixities>
      </xip:Bitstream>)
        .toString
    )

    verifyServerRequests(requestUrls)
  }

  "metadataForEntity" should "return the correct metadata for an IO" in {
    val entityId = UUID.randomUUID()
    val entity = valueFromF(fromType("IO", entityId, Option("title"), Option("description"), deleted = false))
    val client = testClient

    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))

    val requestUrls = List(
      endpoints.stubGetEntity(),
      endpoints.stubGetIdentifierForEntity(),
      endpoints.stubIoRepresentationUrls(),
      endpoints.stubIoRepresentations(Preservation),
      endpoints.stubIoRepresentations(Access),
      endpoints.stubIoRepresentations(Access, 2),
      endpoints.stubMetadataFragment(1, endpoints.metadataFragmentOneUrl),
      endpoints.stubMetadataFragment(2, endpoints.metadataFragmentTwoUrl),
      endpoints.stubLinks(),
      endpoints.stubEventActions()
    )

    val res = client.metadataForEntity(entity)
    val metadata: IoMetadata = valueFromF(res).asInstanceOf[IoMetadata]

    metadata.entityNode.toString should equal(
      <xip:InformationObject xmlns="http://preservica.com/EntityAPI/v7.7" xmlns:xip="http://preservica.com/XIP/v7.7">
              <xip:Ref>{entity.ref}</xip:Ref>
            <xip:Title>page1File.txt</xip:Title>
            <xip:Description>A description</xip:Description>
            <xip:SecurityTag>unknown</xip:SecurityTag>
            <xip:Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</xip:Parent>
            </xip:InformationObject>.toString
    )
    metadata.identifiers.head.toString should equal(
      <Identifier xmlns="http://preservica.com/EntityAPI/v7.7" xmlns:xip="http://preservica.com/XIP/v7.7">
            <xip:ApiId>acb1e74b1ad5c4bfc360ef5d44228c9f</xip:ApiId>
            <xip:Type>Test Type 1</xip:Type>
            <xip:Value>Test Value 1</xip:Value>
            <xip:Entity>{entity.ref}</xip:Entity>
          </Identifier>.toString
    )
    Utility.trim(metadata.links.head) should equal(
      Utility.trim(
        <xip:Link xmlns:xip="http://preservica.com/XIP/v7.7" xmlns="http://preservica.com/EntityAPI/v7.7"><xip:Type>VirtualChild</xip:Type><xip:ToEntity>{
          entity.ref.toString
        }</xip:ToEntity><xip:FromEntity>758ef5c5-a364-40e5-bd78-e40f72f5a1f0</xip:FromEntity></xip:Link>
      )
    )
    Utility.trim(metadata.links.last) should equal(
      Utility.trim(
        <xip:Link xmlns:xip="http://preservica.com/XIP/v7.7" xmlns="http://preservica.com/EntityAPI/v7.7"><xip:Type>CitedBy</xip:Type><xip:ToEntity>866d4c6e-ee51-467a-b7a3-e4b65709cf95</xip:ToEntity><xip:FromEntity>{
          entity.ref.toString
        }</xip:FromEntity></xip:Link>
      )
    )
    metadata.metadataNodes.head.toString should equal(
      <Metadata xmlns:xip="http://preservica.com/XIP/v7.7" xmlns="http://preservica.com/EntityAPI/v7.7">
            <Content>
              <Test1>
                <Test1Value>Test1Value</Test1Value>
              </Test1>
            </Content>
          </Metadata>.toString
    )
    metadata.metadataNodes.last.toString should equal(
      <Metadata xmlns:xip="http://preservica.com/XIP/v7.7" xmlns="http://preservica.com/EntityAPI/v7.7">
            <Content>
              <Test2>
                <Test2Value>Test2Value</Test2Value>
              </Test2>
            </Content>
          </Metadata>.toString
    )

    metadata.eventActions.head.toString should equal(
      <xip:EventAction commandType="command_create">
            <xip:Event type="Ingest">
              <xip:Ref>6da319fa-07e0-4a83-9c5a-b6bad08445b1</xip:Ref>
              <xip:Date>2023-06-26T08:14:08.441Z</xip:Date>
              <xip:User>test user</xip:User>
            </xip:Event>
            <xip:Date>2023-06-26T08:14:07.441Z</xip:Date>
            <xip:Entity>{entityId}</xip:Entity>
          </xip:EventAction>.toString
    )

    metadata.representations.head.toString should equal(
      <xip:Representation xmlns="http://preservica.com/EntityAPI/v7.7" xmlns:xip="http://preservica.com/XIP/v7.7">
          <xip:InformationObject>{entityId}</xip:InformationObject>
          <xip:Name>Preservation</xip:Name>
          <xip:Type>Preservation</xip:Type>
          <xip:ContentObjects>
            <xip:ContentObject>ad30d41e-b75c-4195-b569-91e820f430ac</xip:ContentObject>
            <xip:ContentObject>354f47cf-3ca2-4a4e-8181-81b714334f00</xip:ContentObject>
          </xip:ContentObjects>
          <xip:RepresentationFormats/>
          <xip:RepresentationProperties/>
        </xip:Representation>.toString
    )

    verifyServerRequests(requestUrls)
  }

  "metadataForEntity" should "return 'standard' metadata if the Entity type in the request is not an IO nor CO" in {
    val entityId = UUID.randomUUID()
    val entity = valueFromF(fromType("SO", entityId, Option("title"), Option("description"), deleted = false))
    val client = testClient

    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))

    val requestUrls = List(
      endpoints.stubGetEntity(),
      endpoints.stubGetIdentifierForEntity(),
      endpoints.stubMetadataFragment(1, endpoints.metadataFragmentOneUrl),
      endpoints.stubMetadataFragment(2, endpoints.metadataFragmentTwoUrl),
      endpoints.stubLinks(),
      endpoints.stubEventActions()
    )

    val res = client.metadataForEntity(entity)
    val metadata = valueFromF(res)

    metadata.getClass.getSimpleName should equal("StandardEntityMetadata")

    verifyServerRequests(requestUrls)
  }

  "metadataForEntity" should "return an empty list when the object has no fragments" in {
    val entityId = UUID.randomUUID()
    val entity = valueFromF(fromType("IO", entityId, Option("title"), Option("description"), deleted = false))
    val client = testClient

    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))

    val requestUrls = List(
      endpoints.stubGetEntity(returnMetadataFragmentUrls = false),
      endpoints.stubGetIdentifierForEntity(),
      endpoints.stubIoRepresentationUrls(),
      endpoints.stubIoRepresentations(Preservation),
      endpoints.stubIoRepresentations(Access),
      endpoints.stubIoRepresentations(Access, 2),
      endpoints.stubLinks(),
      endpoints.stubEventActions()
    )

    val res = client.metadataForEntity(entity)
    val metadata = valueFromF(res)

    metadata.metadataNodes should equal(Nil)

    verifyServerRequests(requestUrls)
  }

  "metadataForEntity" should "return an error if even one metadata response element is empty" in {
    val entityId = UUID.randomUUID()
    val entity = valueFromF(fromType("IO", entityId, Option("title"), Option("description"), deleted = false))
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    val client = testClient

    val requestUrls = List(
      endpoints.stubGetEntity(),
      endpoints.stubGetIdentifierForEntity(),
      endpoints.stubMetadataFragment(1, endpoints.metadataFragmentOneUrl),
      endpoints.stubMetadataFragment(2, endpoints.metadataFragmentTwoUrl, false),
      endpoints.stubLinks()
    )

    val res = client.metadataForEntity(entity)
    val error = intercept[PreservicaClientException] {
      valueFromF(res)
    }
    error.getMessage should equal(
      "Could not be retrieve all 'MetadataContainer' Nodes from:\n" + """<MetadataResponse xmlns="http://preservica.com/EntityAPI/v7.7" xmlns:xip="http://preservica.com/XIP/v7.7">
                                                                        |          <MetadataContainer>
                                                                        |            <Content>
                                                                        |              <Test1>
                                                                        |                <Test1Value>Test1Value</Test1Value>
                                                                        |              </Test1>
                                                                        |            </Content>
                                                                        |          </MetadataContainer>
                                                                        |        </MetadataResponse>
                                                                        |<MetadataResponse xmlns:xip="http://preservica.com/XIP/v7.7" xmlns="http://preservica.com/EntityAPI/v7.7">
                                                                        |        </MetadataResponse>""".stripMargin
    )

    verifyServerRequests(requestUrls)
  }

  "metadataForEntity" should "return an error if the server is unavailable" in {
    val entity = valueFromF(fromType("IO", UUID.randomUUID(), Option("title"), Option("description"), deleted = false))
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    endpoints.serverErrorStub(MockPreservicaAPI.tokenUrl)
    val client = testClient
    val response = client.metadataForEntity(entity)

    val expectedError = valueFromF(cme.attempt(response))

    expectedError.isLeft should be(true)
    expectedError.left.map(err => {
      err.getMessage should equal(
        s"Status code 500 calling http://localhost:$preservicaPort/api/accesstoken/login with method POST statusCode: 500, response: "
      )
    })
    verifyServerRequests(Nil)
  }

  "metadataForEntity" should "return an error if the entity path is empty" in {
    val id = UUID.randomUUID()
    val entity = Entity(None, id, None, None, deleted = true, None)
    EntityClientEndpoints(preservicaServer)
    val client = testClient
    val ex = intercept[PreservicaClientException] {
      valueFromF(client.metadataForEntity(entity))
    }
    ex.getMessage should equal(s"No path found for entity id $id. Could this entity have been deleted?")
    verifyServerRequests(Nil, 0)
  }

  "entitiesUpdatedSince" should "return an entity if one was updated since the datetime specified" in {
    val date = ZonedDateTime.of(2023, 4, 25, 0, 0, 0, 0, ZoneId.of("UTC"))
    val entitiesUpdatedSinceUrl = EntityClientEndpoints(preservicaServer).stubEntitiesUpdatedSince(date)

    val client = testClient
    val response = valueFromF(client.entitiesUpdatedSince(date, 0))

    val expectedEntity = response.entities.head

    response.hasNext should equal(true)
    expectedEntity.ref.toString should equal("8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91")
    expectedEntity.path.get should equal("information-objects")
    expectedEntity.title.get should be("page1File.txt")
    expectedEntity.deleted should be(false)

    verifyServerRequests(List(entitiesUpdatedSinceUrl))
  }

  "entitiesUpdatedSince" should "return hasNext false if there is no Next element in the response" in {
    val date = ZonedDateTime.of(2023, 4, 25, 0, 0, 0, 0, ZoneId.of("UTC"))
    val entitiesUpdatedSinceUrl =
      EntityClientEndpoints(preservicaServer).stubEntitiesUpdatedSince(date, nextPage = false)

    val client = testClient
    val response = valueFromF(client.entitiesUpdatedSince(date, 0))

    response.hasNext should equal(false)
    verifyServerRequests(List(entitiesUpdatedSinceUrl))
  }

  "entitiesUpdatedSince" should "return an entity if the entity is before the end date specified" in {
    val date = ZonedDateTime.of(2023, 4, 25, 0, 0, 0, 0, ZoneId.of("UTC"))
    val potentialEndDate = Option(ZonedDateTime.of(2024, 4, 25, 0, 0, 0, 0, ZoneId.of("UTC")))
    val entitiesUpdatedSinceUrl =
      EntityClientEndpoints(preservicaServer).stubEntitiesUpdatedSince(date, potentialEndDate = potentialEndDate)

    val client = testClient
    val response = valueFromF(client.entitiesUpdatedSince(date, 0, potentialEndDate = potentialEndDate))

    val expectedEntity = response.entities.head

    expectedEntity.ref.toString should equal("8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91")
    expectedEntity.path.get should equal("information-objects")
    expectedEntity.title.get should be("page1File.txt")
    expectedEntity.deleted should be(false)

    verifyServerRequests(List(entitiesUpdatedSinceUrl))
  }

  "entitiesUpdatedSince" should "return an empty list if none have been updated" in {
    val date = ZonedDateTime.of(2023, 4, 25, 0, 0, 0, 0, ZoneId.of("UTC"))
    val entitiesUpdatedSinceUrl =
      EntityClientEndpoints(preservicaServer).stubEntitiesUpdatedSince(date, emptyResponse = true)

    val client = testClient
    val response = valueFromF(client.entitiesUpdatedSince(date, 0))

    response.entities.size should equal(0)
    verifyServerRequests(List(entitiesUpdatedSinceUrl))
  }

  "entitiesUpdatedSince" should "return an error if the request is malformed" in {
    val date = ZonedDateTime.of(2023, 4, 25, 0, 0, 0, 0, ZoneId.of("UTC"))
    val entitiesUpdatedSinceUrl = EntityClientEndpoints(preservicaServer).stubEntitiesUpdatedSince(date, false)

    val client = testClient
    val response = valueFromF(cme.attempt(client.entitiesUpdatedSince(date, 0)))

    response.left.map { err =>
      err.getClass.getSimpleName should equal("PreservicaClientException")
    }
    verifyServerRequests(List(entitiesUpdatedSinceUrl, entitiesUpdatedSinceUrl))
  }

  "entityEventActions" should "return all paginated values in reverse chronological order (most recent EventAction first)" in {
    val contentObject = Entities.Entity(
      ContentObject.some,
      UUID.fromString("a9e1cae8-ea06-4157-8dd4-82d0525b031c"),
      None,
      None,
      deleted = false,
      ContentObject.entityPath.some
    )
    val endpoints = EntityClientEndpoints(preservicaServer, Some(contentObject))
    val eventActionsUrl = endpoints.stubEventActions()

    val client = testClient
    val response = valueFromF(client.entityEventActions(contentObject))

    val firstResult = response.head
    val secondResult = response.last

    firstResult.dateOfEvent.toString should equal("2023-06-27T08:14:07.442Z")
    firstResult.eventRef should equal(UUID.fromString("efe9b25d-c3b4-476a-8ff1-d52fb01ad96b"))
    firstResult.eventType should be("Modified")

    secondResult.dateOfEvent.toString should equal("2023-06-26T08:14:07.441Z")
    secondResult.eventRef should equal(UUID.fromString("6da319fa-07e0-4a83-9c5a-b6bad08445b1"))
    secondResult.eventType should be("Ingest")

    verifyServerRequests(List(eventActionsUrl))
  }

  "entityEventActions" should "return an error if the request is malformed" in {
    val contentObject = createEntity(ContentObject)
    val endpoints = EntityClientEndpoints(preservicaServer, Some(contentObject))
    val eventActionsUrl = endpoints.stubEventActions(false).head

    val client = testClient
    val response = valueFromF(cme.attempt(client.entityEventActions(contentObject)))

    response.left.map { err =>
      err.getClass.getSimpleName should equal("PreservicaClientException")
    }
    verifyServerRequests(List(eventActionsUrl, eventActionsUrl))
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
    verifyZeroServerRequests
  }

  "entitiesByIdentifiers" should "return a complete entity if it has the identifier specified" in {
    val identifiers = List(Identifier("testIdentifier1", "testValue1"), Identifier("testIdentifier2", "testValue2"))
    val structuralObject = createEntity()

    val endpoints = EntityClientEndpoints(preservicaServer, Some(structuralObject))

    val requestUrls =
      List(endpoints.stubGetEntity(), endpoints.stubGetEntity()) ++ endpoints.stubEntitiesByIdentifiers(identifiers)

    val client = testClient
    val response = valueFromF(client.entitiesPerIdentifier(identifiers))

    identifiers.foreach { identifier =>
      val expectedEntity = response(identifier).head
      expectedEntity.ref.toString should equal("a9e1cae8-ea06-4157-8dd4-82d0525b031c")
      expectedEntity.path.get should equal("structural-objects")
      expectedEntity.title.get should be("page1File.txt")
      expectedEntity.description.get should be("A description")
      expectedEntity.securityTag.get should be(Unknown)
      expectedEntity.deleted should be(false)
    }

    verifyServerRequests(List(requestUrls))
  }

  "entitiesByIdentifiers" should "return two entities if they both have the same identifier" in {
    val identifier = Identifier("testIdentifier", "testValue")
    val structuralObjectOne = createEntity()
    val structuralObjectTwo = createEntity()

    val endpointsOne = EntityClientEndpoints(preservicaServer, Some(structuralObjectOne))
    val endpointsTwo = EntityClientEndpoints(preservicaServer, Some(structuralObjectTwo))
    val apiUrl = s"/api/entity/v$apiVersion"

    val xmlResponse = <EntitiesResponse>
      <Entities>
        <Entity title="page1File.txt" ref={
      structuralObjectOne.ref.toString
    } type="SO">http://localhost/page1/object</Entity>
        <Entity title="page1File.txt" ref={
      structuralObjectTwo.ref.toString
    } type="SO">http://localhost/page1/object</Entity>
      </Entities>
      <Paging>
        <TotalResults>1</TotalResults>
      </Paging>
    </EntitiesResponse>

    val entitiesByIdUrl = s"$apiUrl/entities/by-identifier?type=${identifier.identifierName}&value=${identifier.value}"
    preservicaServer.stubFor(get(urlEqualTo(entitiesByIdUrl)).willReturn(okXml(xmlResponse.toString)))

    val requestUrls = List(endpointsOne.stubGetEntity(), endpointsTwo.stubGetEntity(), entitiesByIdUrl)

    val client = testClient
    val response = valueFromF(client.entitiesPerIdentifier(List(identifier)))

    response(identifier).foreach { expectedEntity =>
      expectedEntity.ref.toString should equal("a9e1cae8-ea06-4157-8dd4-82d0525b031c")
      expectedEntity.path.get should equal("structural-objects")
      expectedEntity.title.get should be("page1File.txt")
      expectedEntity.description.get should be("A description")
      expectedEntity.securityTag.get should be(Unknown)
      expectedEntity.deleted should be(false)
    }

    verifyServerRequests(List(requestUrls))
  }

  "entitiesByIdentifiers" should "return an empty list if no entities have the identifiers specified" in {
    val identifiers = List(Identifier("testIdentifier", "testValueDoesNotExist"))
    val structuralObject = createEntity()
    val endpoints = EntityClientEndpoints(preservicaServer, Some(structuralObject))

    val entitiesByIdentifierUrl =
      endpoints.stubEntitiesByIdentifiers(identifiers, emptyResponse = true)

    val client = testClient
    val response = valueFromF(
      client.entitiesPerIdentifier(identifiers)
    )

    response(identifiers.head).size should equal(0)
    verifyServerRequests(List(entitiesByIdentifierUrl))
  }

  "entitiesByIdentifiers" should "return an error if the request is malformed" in {
    val identifiers = List(Identifier("testIdentifier", "testValue"))
    val structuralObject = createEntity()

    val endpoints = EntityClientEndpoints(preservicaServer, Some(structuralObject))
    val entitiesByIdentifierUrl =
      endpoints.stubEntitiesByIdentifiers(identifiers, false)

    val client = testClient
    val response = valueFromF(cme.attempt(client.entitiesPerIdentifier(identifiers)))

    response.left.map { err =>
      err.getClass.getSimpleName should equal("PreservicaClientException")
    }
    verifyServerRequests(List(entitiesByIdentifierUrl, entitiesByIdentifierUrl))
  }

  "addIdentifierForEntity" should s"make a correct request to add an identifier to an Entity" in {
    val structuralObject = createEntity()
    val endpoints = EntityClientEndpoints(preservicaServer, Some(structuralObject))
    val addEntityUrl = endpoints.stubAddIdentifierForEntity(identifier)

    val client = testClient
    val addIdentifierForEntityResponse: F[String] =
      client.addIdentifierForEntity(structuralObject.ref, StructuralObject, identifier)

    val _ = valueFromF(addIdentifierForEntityResponse)

    val requestMade = getRequestMade(preservicaServer)

    val expectedXml =
      s"""<Identifier xmlns="$xipUrl"><Type>TestIdentifierName</Type><Value>TestIdentifierValue</Value></Identifier>"""

    requestMade should be(s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n$expectedXml""")
    verifyServerRequests(List(addEntityUrl))
  }

  "addIdentifierForEntity" should s"return an exception if the API call does" in {
    val structuralObject = createEntity()
    val endpoints = EntityClientEndpoints(preservicaServer, Some(structuralObject))
    val addEntityUrl = endpoints.stubAddIdentifierForEntity(identifier, false)

    val client = testClient
    val addIdentifierForEntityResponse: F[String] =
      client.addIdentifierForEntity(structuralObject.ref, StructuralObject, identifier)

    val error = intercept[PreservicaClientException] {
      valueFromF(addIdentifierForEntityResponse)
    }

    error.getMessage should equal(
      s"Status code 400 calling http://localhost:$preservicaPort/api/entity/v$apiVersion/structural-objects/${structuralObject.ref}/identifiers with method POST "
    )
    verifyServerRequests(List(addEntityUrl, addEntityUrl))
  }

  "addIdentifierForEntity" should s"return a message confirmation if the Identifier was added" in {
    val structuralObject = createEntity()
    val endpoints = EntityClientEndpoints(preservicaServer, Some(structuralObject))
    val addEntityUrl = endpoints.stubAddIdentifierForEntity(identifier)

    val client = testClient
    val addIdentifierForEntityResponse: F[String] =
      client.addIdentifierForEntity(structuralObject.ref, StructuralObject, identifier)

    val response = valueFromF(addIdentifierForEntityResponse)

    response should be("The Identifier was added")
    verifyServerRequests(List(addEntityUrl))
  }

  "getEntity" should "return the requested entity" in {
    val client = testClient
    val structuralObject = createEntity()
    val endpoints = EntityClientEndpoints(preservicaServer, Some(structuralObject))
    val getEntityUrl = endpoints.stubGetEntity()

    val entity = valueFromF(client.getEntity(structuralObject.ref, structuralObject.entityType.get))

    entity.entityType.get should be(structuralObject.entityType.get)
    entity.ref should be(structuralObject.ref)
    entity.title.get should be("page1File.txt")
    entity.description.get should be("A description")
    entity.securityTag.get should be(Unknown)
    verifyServerRequests(List(getEntityUrl))
  }

  "getEntity" should "return an error if the returned entity has a different entity type" in {
    val client = testClient
    val id = UUID.randomUUID()
    EntityClientEndpoints(preservicaServer)
    val getEntityUrl = s"/api/entity/v$apiVersion/information-objects/$id"

    val fullEntityResponse = <EntityResponse>
      <xip:StructuralObject>
        <xip:Ref>
          {id}
        </xip:Ref>
        <xip:Title>title.txt</xip:Title>
        <xip:Description>A description</xip:Description>
        <xip:SecurityTag>unknown</xip:SecurityTag>
      </xip:StructuralObject>
    </EntityResponse>

    preservicaServer.stubFor(
      get(urlEqualTo(getEntityUrl))
        .willReturn(ok(fullEntityResponse.toString))
    )
    val ex = intercept[PreservicaClientException] {
      valueFromF(client.getEntity(id, InformationObject))
    }
    ex.getMessage should be(s"Entity type 'InformationObject' not found for id $id")
    verifyServerRequests(List(getEntityUrl))
  }

  "getEntity" should "return an error if the entity is not found" in {
    val host = s"http://localhost:$preservicaPort"
    val client = testClient
    val id = UUID.randomUUID()
    val getEntitiesUrl = s"/api/entity/v$apiVersion/information-objects/$id"
    EntityClientEndpoints(preservicaServer)

    preservicaServer.stubFor(
      get(urlEqualTo(getEntitiesUrl))
        .willReturn(notFound())
    )
    val ex = intercept[PreservicaClientException] {
      valueFromF(client.getEntity(id, InformationObject))
    }
    ex.getMessage should be(s"Status code 404 calling $host$getEntitiesUrl with method GET ")
    verifyServerRequests(List(getEntitiesUrl, getEntitiesUrl))
  }

  "getEntity" should "return the entity if the first call fails but the second succeeds" in {
    val host = s"http://localhost:$preservicaPort"
    val client = testClient
    val id = UUID.randomUUID()
    val getEntitiesUrl = s"/api/entity/v$apiVersion/structural-objects/$id"
    val fullEntityResponse = <EntityResponse>
      <xip:StructuralObject>
        <xip:Ref>
          {id}
        </xip:Ref>
        <xip:Title>title.txt</xip:Title>
        <xip:Description>A description</xip:Description>
        <xip:SecurityTag>unknown</xip:SecurityTag>
      </xip:StructuralObject>
    </EntityResponse>
    EntityClientEndpoints(preservicaServer)

    val firstGetMapping: MappingBuilder = get(urlEqualTo(getEntitiesUrl))
      .inScenario("RetryCall")
      .whenScenarioStateIs(Scenario.STARTED)
      .willReturn(notFound())
      .willSetStateTo("Next call")
    val secondGetMapping: MappingBuilder = get(urlEqualTo(getEntitiesUrl))
      .inScenario("RetryCall")
      .whenScenarioStateIs("Next call")
      .willReturn(ok(fullEntityResponse.toString))

    preservicaServer.stubFor(firstGetMapping)
    preservicaServer.stubFor(secondGetMapping)

    val response = valueFromF(client.getEntity(id, StructuralObject))

    response.ref should be(id)
    response.title should be(Some("title.txt"))
    response.description should be(Some("A description"))
    response.securityTag should be(Some(Unknown))
    verifyServerRequests(List(getEntitiesUrl, getEntitiesUrl))
  }

  "updateEntityIdentifiers" should "not send a request if no identifiers are passed" in {
    val client = testClient
    val entity = createEntity()
    EntityClientEndpoints(preservicaServer, Some(entity))
    valueFromF(client.updateEntityIdentifiers(entity, Nil))
    verifyServerRequests(Nil, 0)
  }

  "updateEntityIdentifiers" should "send a request to Preservica for each identifier" in {
    val entity: Entity = createEntity()
    val client = testClient
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    val identifiers = List(
      IdentifierResponse("1", "Name1", "Value1"),
      IdentifierResponse("2", "Name2", "Value2")
    )

    val updateEntityIdentifiersUrls =
      identifiers.map(identifierResponse => endpoints.stubUpdateEntityIdentifiers(identifierResponse))

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

      (xml \ "Type").text.trim should equal(s"Name$id")
      (xml \ "Value").text.trim should equal(s"Value$id")
      serveEvent.getRequest.getUrl.endsWith(s"/$id") should be(true)
    }
    checkEvent(putEvents.head, "2")
    checkEvent(putEvents.last, "1")
    verifyServerRequests(List(updateEntityIdentifiersUrls))
  }

  "updateEntityIdentifiers" should "return an error if the entity path is missing" in {
    val client = testClient
    val entity = createEntity().copy(path = None)
    EntityClientEndpoints(preservicaServer, Some(entity))
    val identifiers = List(IdentifierResponse("1", "Name1", "Value1"))
    val ex = intercept[PreservicaClientException] {
      valueFromF(client.updateEntityIdentifiers(entity, identifiers))
    }
    ex.getMessage should equal(s"No path found for entity id ${entity.ref}. Could this entity have been deleted?")
    verifyServerRequests(Nil, 0)
  }

  "updateEntityIdentifiers" should "return an error if the update request returns an error" in {
    val client = testClient
    val entity = createEntity()
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    val identifierResponse = IdentifierResponse("1", "Name1", "Value1")
    val updateEntityIdentifiersUrl = endpoints.stubUpdateEntityIdentifiers(identifierResponse, false)

    val ex = intercept[PreservicaClientException] {
      valueFromF(client.updateEntityIdentifiers(entity, List(identifierResponse)))
    }
    ex.getMessage should equal(
      s"Status code 500 calling http://localhost:$preservicaPort$updateEntityIdentifiersUrl with method PUT "
    )
    verifyServerRequests(List(updateEntityIdentifiersUrl, updateEntityIdentifiersUrl))
  }

  "getEntityIdentifiers" should "return an empty list if there are no identifiers" in {
    val client = testClient
    val entity = createEntity()
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    val getEntityIdentifiersUrl = endpoints.stubGetIdentifierForEntity(emptyResponse = true)

    val identifiers = valueFromF(client.getEntityIdentifiers(entity))
    identifiers.size should equal(0)
    verifyServerRequests(List(getEntityIdentifiersUrl))
  }

  "getEntityIdentifiers" should "return the identifiers for an entity" in {
    val client = testClient
    val entity = createEntity()
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    val getEntityIdentifiersUrl = endpoints.stubGetIdentifierForEntity()

    val identifiers = valueFromF(client.getEntityIdentifiers(entity)).sortBy(_.id)
    identifiers.size should equal(2)

    def checkIdentifier(identifier: IdentifierResponse, id: String, apiId: String) = {
      identifier.identifierName should equal(s"Test Type $id")
      identifier.value should equal(s"Test Value $id")
      identifier.id should equal(apiId)
    }
    checkIdentifier(identifiers.head, "2", "65862d40f40440de14c1b75e5f342e99")
    checkIdentifier(identifiers.last, "1", "acb1e74b1ad5c4bfc360ef5d44228c9f")
    verifyServerRequests(List(getEntityIdentifiersUrl))
  }

  "getEntityIdentifiers" should "return an error if the entity path is missing" in {
    val client = testClient
    val entity = createEntity().copy(path = None)
    val ex = intercept[PreservicaClientException] {
      valueFromF(client.getEntityIdentifiers(entity))
    }
    ex.getMessage should equal(s"No path found for entity id ${entity.ref}. Could this entity have been deleted?")
    verifyZeroServerRequests
  }

  "getEntityIdentifiers" should "return an error if the get request returns an error" in {
    val client = testClient
    val entity = createEntity()
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    val getEntityIdentifiersUrl = endpoints.stubGetIdentifierForEntity(successfulResponse = false)

    val ex = intercept[PreservicaClientException] {
      valueFromF(client.getEntityIdentifiers(entity))
    }
    ex.getMessage should equal(
      s"Status code 500 calling http://localhost:$preservicaPort${endpoints.identifiersUrl} with method GET "
    )
    verifyServerRequests(List(getEntityIdentifiersUrl, getEntityIdentifiersUrl))
  }

  "getUrlsToIoRepresentations" should "return an empty list if there are no representations" in {
    val client = testClient
    val entity = createEntity(InformationObject)
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    val ioRepresentationUrls = endpoints.stubIoRepresentationUrls(emptyResponse = true)

    val urls = valueFromF(client.getUrlsToIoRepresentations(entity.ref, Some(Preservation)))
    urls.size should equal(0)
    verifyServerRequests(List(ioRepresentationUrls))
  }

  "getUrlsToIoRepresentations" should "return the url of a Preservation representation" in {
    val client = testClient
    val entity = createEntity(InformationObject)
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    val ioRepresentationUrls = endpoints.stubIoRepresentationUrls()

    val urls = valueFromF(client.getUrlsToIoRepresentations(entity.ref, Some(Preservation)))

    urls.size should equal(1)
    urls should equal(
      Seq(
        s"http://localhost:$preservicaPort/api/entity/v$apiVersion/information-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/representations/Preservation/1"
      )
    )
    verifyServerRequests(List(ioRepresentationUrls))
  }

  "getUrlsToIoRepresentations" should "return a url for each representation if 'representationType' filter passed in, was 'None'" in {
    val client = testClient
    val entity = createEntity(InformationObject)
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    val ioRepresentationUrls = endpoints.stubIoRepresentationUrls()

    val urls = valueFromF(client.getUrlsToIoRepresentations(entity.ref, None))

    urls.size should equal(3)
    urls should equal(
      Seq(
        s"http://localhost:$preservicaPort/api/entity/v$apiVersion/information-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/representations/Preservation/1",
        s"http://localhost:$preservicaPort/api/entity/v$apiVersion/information-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/representations/Access/1",
        s"http://localhost:$preservicaPort/api/entity/v$apiVersion/information-objects/a9e1cae8-ea06-4157-8dd4-82d0525b031c/representations/Access/2"
      )
    )
    verifyServerRequests(List(ioRepresentationUrls))
  }

  "getUrlsToIoRepresentations" should "return an error if the get request returns an error" in {
    val client = testClient
    val entity = createEntity(InformationObject)
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    val ioRepresentationUrls = endpoints.stubIoRepresentationUrls(false)

    val ex = intercept[PreservicaClientException] {
      valueFromF(client.getUrlsToIoRepresentations(entity.ref, None))
    }
    ex.getMessage should equal(
      s"Status code 500 calling http://localhost:$preservicaPort${endpoints.representationsUrl} with method GET "
    )
    verifyServerRequests(List(ioRepresentationUrls, ioRepresentationUrls))
  }

  "getContentObjectsFromRepresentation" should "return an empty list if there are no representations" in {
    val client = testClient
    val entity = createEntity(InformationObject)
    val repType = Preservation
    val repCount = 1
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    val ioRepresentationUrls = endpoints.stubIoRepresentations(repType, repCount, emptyResponse = true)

    val contentObjects = valueFromF(client.getContentObjectsFromRepresentation(entity.ref, repType, repCount))
    contentObjects.size should equal(0)
    verifyServerRequests(List(ioRepresentationUrls))
  }

  "getContentObjectsFromRepresentation" should "return the Content Objects of a Preservation Representation" in {
    val client = testClient
    val entity = createEntity(InformationObject)
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    val repType = Preservation
    val repCount = 1
    val ioRepresentationUrls = endpoints.stubIoRepresentations(repType, repCount)
    val expectedEntity =
      Entity(
        Some(ContentObject),
        UUID.fromString("ad30d41e-b75c-4195-b569-91e820f430ac"),
        None,
        None,
        false,
        Some(ContentObject.entityPath),
        None,
        Some(UUID.fromString("a9e1cae8-ea06-4157-8dd4-82d0525b031c"))
      )

    val contentObjects = valueFromF(client.getContentObjectsFromRepresentation(entity.ref, repType, repCount))

    contentObjects.size should equal(2)
    contentObjects should equal(
      List(
        expectedEntity,
        expectedEntity.copy(ref = UUID.fromString("354f47cf-3ca2-4a4e-8181-81b714334f00"))
      )
    )
    verifyServerRequests(List(ioRepresentationUrls))
  }

  "getContentObjectsFromRepresentation" should "return an error if the get request returns an error" in {
    val client = testClient
    val entity = createEntity(InformationObject)
    val endpoints = EntityClientEndpoints(preservicaServer, Some(entity))
    val repType = Preservation
    val repCount = 1
    val ioRepresentationUrls = endpoints.stubIoRepresentations(repType, repCount, false)
    val url = endpoints.representationTypeUrl(repType, repCount)

    val ex = intercept[PreservicaClientException] {
      valueFromF(client.getContentObjectsFromRepresentation(entity.ref, Preservation, repCount))
    }
    ex.getMessage should equal(s"Status code 500 calling http://localhost:$preservicaPort$url with method GET ")
    verifyServerRequests(List(ioRepresentationUrls, ioRepresentationUrls))
  }

  "getPreservicaNamespaceVersion" should "extract and return the version, as a float, from a namespace" in {
    val client = testClient
    val endpoints = EntityClientEndpoints(preservicaServer)
    val endpoint = "retention-policies"
    val retentionPoliciesUrl = endpoints.stubRetentionPolicies()

    val version = valueFromF(client.getPreservicaNamespaceVersion(endpoint))
    version should equal(7.7f)
    verifyServerRequests(List(retentionPoliciesUrl))
  }

  "streamAllEntityRefs" should "recursively collect and return the correct entityRefs" in {
    val client = testClient
    val endpoints = EntityClientEndpoints(preservicaServer)
    val stubbedUrls = endpoints.stubRootChildren()
    val rootSoRef = UUID.fromString("a9e1cae8-ea06-4157-8dd4-82d0525b031c")

    val entityRefs = valueFromF(client.streamAllEntityRefs(Some(Preservation)).compile.toList)

    entityRefs should equal(
      List(
        StructuralObjectRef(rootSoRef, None),
        StructuralObjectRef(UUID.fromString("71143bfd-b29f-4548-871c-8334f2d2bcb8"), None),
        StructuralObjectRef(UUID.fromString("dd672e2c-6248-43d7-81ff-c632acfc8fd7"), None),
        InformationObjectRef(endpoints.entity.ref, rootSoRef),
        StructuralObjectRef(UUID.fromString("b107677d-745f-4cb8-94c7-e31383f2eb0b"), Some(rootSoRef)),
        ContentObjectRef(UUID.fromString("ad30d41e-b75c-4195-b569-91e820f430ac"), endpoints.entity.ref),
        ContentObjectRef(UUID.fromString("354f47cf-3ca2-4a4e-8181-81b714334f00"), endpoints.entity.ref)
      )
    )
    val expectedUrlRequests = stubbedUrls.filterNot(_.contains("174eb617-2d05-4920-a764-99cdbdae94a1"))

    verifyServerRequests(List(expectedUrlRequests), expectedUrlRequests.length)
  }

  "streamAllEntityRefs" should "return an error if the get request returns an error" in {
    val client = testClient
    val endpoints = EntityClientEndpoints(preservicaServer)
    val stubbedUrls = endpoints.stubRootChildren(false)

    val ex = intercept[PreservicaClientException] {
      valueFromF(client.streamAllEntityRefs().compile.toList)
    }
    ex.getMessage should equal(
      s"Status code 400 calling http://localhost:$preservicaPort${endpoints.rootChildrenUrl}?max=1000&start=0 with method GET "
    )

    val expectedUrlRequests = stubbedUrls.take(1)
    verifyServerRequests(List(expectedUrlRequests, expectedUrlRequests), expectedUrlRequests.length)
  }

  private def getRequestMade(preservicaServer: WireMockServer) =
    preservicaServer.getServeEvents.getServeEvents.get(0).getRequest.getBodyAsString
}
