package uk.gov.nationalarchives.dp.client

import cats.MonadError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._
import uk.gov.nationalarchives.dp.client.Entities.Entity
import uk.gov.nationalarchives.dp.client.EntityClient.{Open, StructuralObject}

import java.time.ZonedDateTime
import java.util.UUID

abstract class DataProcessorTest[F[_]](implicit cme: MonadError[F, Throwable]) extends AnyFlatSpec {
  def valueFromF[T](value: F[T]): T

  "childNodeFromEntity" should "return the node requested even if it is lowercase" in {
    val input =
      <MetadataResponse>
        <xip:Object>
          <xip:Ref>6da319fa-07e0-4a83-9c5a-b6bad08445b1</xip:Ref>
        </xip:Object>
        <AdditionalInformation>
          <Metadata>
            <Fragment>test1</Fragment>
            <Fragment>test2</Fragment>
          </Metadata>
        </AdditionalInformation>
      </MetadataResponse>

    val childNodeResponseF = new DataProcessor[F]().childNodeFromEntity(input, "Object", "ref")
    val value = valueFromF(childNodeResponseF)

    value should equal("6da319fa-07e0-4a83-9c5a-b6bad08445b1")
  }

  "childNodeFromEntity" should "return an error if child node does not exist" in {
    val input =
      <MetadataResponse>
        <xip:Object>
          <xip:Ref>6da319fa-07e0-4a83-9c5a-b6bad08445b1</xip:Ref>
        </xip:Object>
        <AdditionalInformation>
          <Metadata>
            <Fragment>test1</Fragment>
            <Fragment>test2</Fragment>
          </Metadata>
        </AdditionalInformation>
      </MetadataResponse>

    val childNodeResponseF = new DataProcessor[F]().childNodeFromEntity(input, "Object", "InvalidNode")

    val error = intercept[PreservicaClientException] {
      valueFromF(childNodeResponseF)
    }
    val expectedMessage = "Either Object or InvalidNode does not exist on entity"

    error.getMessage should equal(expectedMessage)
  }

  "fragmentUrls" should "return fragments" in {
    val input =
      <MetadataResponse>
        <AdditionalInformation>
          <Metadata>
            <Fragment>test1</Fragment>
            <Fragment>test2</Fragment>
          </Metadata>
        </AdditionalInformation>
      </MetadataResponse>
    val response = new DataProcessor[F]().fragmentUrls(input)
    val values = valueFromF(response)

    values.size should equal(2)
    values.head should equal("test1")
    values.last should equal("test2")
  }

  "fragmentUrls" should "return an empty list when there are no fragments" in {
    val input =
      <MetadataResponse>
        <AdditionalInformation>
          <Metadata>
          </Metadata>
        </AdditionalInformation>
      </MetadataResponse>
    val response = new DataProcessor[F]().fragmentUrls(input)
    val values = valueFromF(response)

    values.size should equal(0)
  }

  "fragments" should "return multiple fragments" in {
    def fragment(i: Int) = <Fragment>Fragment
      {i}
    </Fragment>

    def fragmentContainer(i: Int) =
      <MetadataResponse>
        <MetadataContainer>
          <Content>
            {fragment(i)}
          </Content>
        </MetadataContainer>
      </MetadataResponse>

    val fragmentsF =
      new DataProcessor[F]().fragments(Seq(fragmentContainer(1), fragmentContainer(2)))
    val fragments = valueFromF(fragmentsF)

    fragments.size should equal(2)
    fragments.head.trim should equal(fragment(1).toString)
    fragments.last.trim should equal(fragment(2).toString)
  }

  "fragments" should "return an error if there is no content" in {
    val input =
      <MetadataResponse>
        <MetadataContainer>
        </MetadataContainer>
      </MetadataResponse>

    val fragmentsF = new DataProcessor[F]().fragments(Seq(input))
    val error = intercept[PreservicaClientException] {
      valueFromF(fragmentsF)
    }
    val expectedMessage = """No content found for elements:
                            |<MetadataResponse>
                            |        <MetadataContainer>
                            |        </MetadataContainer>
                            |      </MetadataResponse>""".stripMargin
    error.getMessage should equal(expectedMessage)

  }

  "generationUrlFromEntity" should "return a generation url" in {
    val input = <EntityResponse>
      <AdditionalInformation>
        <Generations>http://localhost:test</Generations>
      </AdditionalInformation>
    </EntityResponse>
    val generationsF = new DataProcessor[F]().generationUrlFromEntity(input)
    val generations = valueFromF(generationsF)

    generations should equal("http://localhost:test")
  }

  "generationUrlFromEntity" should "raise an error if the generation is not found" in {
    val input = <EntityResponse>
      <AdditionalInformation>
      </AdditionalInformation>
    </EntityResponse>
    val generationsF = new DataProcessor[F]().generationUrlFromEntity(input)
    val generationsError = intercept[Throwable] {
      valueFromF(generationsF)
    }
    generationsError.getMessage should equal("Generation not found")
  }

  "allGenerationUrls" should "return a sequence of generation urls" in {
    val input =
      <GenerationsResponse>
        <Generations>
          <Generation>http://localhost/generation1</Generation>
          <Generation>http://localhost/generation2</Generation>
        </Generations>
      </GenerationsResponse>
    val generationsF = new DataProcessor[F]().allGenerationUrls(input)
    val generations = valueFromF(generationsF)

    generations.size should equal(2)
    generations.head should equal("http://localhost/generation1")
    generations.last should equal("http://localhost/generation2")
  }

  "allGenerationUrls" should "return an error if there are no generation urls" in {
    val input =
      <GenerationsResponse>
        <Generations>
        </Generations>
      </GenerationsResponse>
    val generationsF = new DataProcessor[F]().allGenerationUrls(input)
    val error = intercept[PreservicaClientException] {
      valueFromF(generationsF)
    }
    val expectedErrorMessage = """No generations found for entity:
                                 |<GenerationsResponse>
                                 |        <Generations>
                                 |        </Generations>
                                 |      </GenerationsResponse>""".stripMargin
    error.getMessage should equal(expectedErrorMessage)
  }

  "allBitstreamUrls" should "return the correct urls" in {
    val input = Seq(
      <GenerationResponse>
        <Bitstreams>
          <Bitstream>http://test1</Bitstream>
        </Bitstreams>
      </GenerationResponse>,
      <GenerationResponse>
        <Bitstreams>
          <Bitstream>http://test2</Bitstream>
        </Bitstreams>
      </GenerationResponse>
    )
    val generationsF = new DataProcessor[F]().allBitstreamUrls(input)
    val generations = valueFromF(generationsF)

    generations.size should equal(2)
    generations.head should equal("http://test1")
    generations.last should equal("http://test2")
  }

  "allBitstreamInfo" should "return the correct bitstream information" in {
    val input = Seq(
      <BitstreamResponse>
        <xip:Bitstream>
          <xip:Filename>test.text</xip:Filename>
          <xip:FileSize>1234</xip:FileSize>
          <xip:Fixities>
            <xip:Fixity>
              <xip:FixityAlgorithmRef>SHA1</xip:FixityAlgorithmRef>
              <xip:FixityValue>0c16735b03fe46b931060858e8cd5ca9c5101565</xip:FixityValue>
            </xip:Fixity>
          </xip:Fixities>
        </xip:Bitstream>
        <AdditionalInformation>
          <Content>http://test</Content>
        </AdditionalInformation>
      </BitstreamResponse>
    )

    val generationsF = new DataProcessor[F]().allBitstreamInfo(input)
    val response = valueFromF(generationsF)

    response.size should equal(1)
    response.head.name should equal("test.text")
    response.head.fileSize should equal(1234)
    response.head.url should equal("http://test")
    response.head.fixity.algorithm should equal("SHA1")
    response.head.fixity.value should equal("0c16735b03fe46b931060858e8cd5ca9c5101565")
  }

  "getNextPage" should "return the next page" in {
    val input = <EntitiesResponse>
      <Paging>
        <Next>http://test</Next>
      </Paging>
    </EntitiesResponse>

    val nextPageF = new DataProcessor[F]().nextPage(input)
    val nextPage = valueFromF(nextPageF)

    nextPage.isDefined should be(true)
    nextPage.get should be("http://test")
  }

  "getNextPage" should "return empty if there is no next page" in {
    val input = <EntitiesResponse>
    </EntitiesResponse>

    val nextPageF = new DataProcessor[F]().nextPage(input)
    val nextPage = valueFromF(nextPageF)

    nextPage.isDefined should be(false)
  }

  "getEntities" should "return the correct entity objects" in {
    val input = <EntitiesResponse>
      <Entities>
        <Entity title="file1.txt" ref="8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91" type="IO" description="A description">http://localhost/file1/object</Entity>
        <Entity title="file2.txt" ref="2d8a9935-3a1a-45ce-aadb-f01f2ddc9405" type="SO">http://localhost/file2/object</Entity>
        <Entity ref="99fb8809-be86-4636-9b3f-4a181de0bc36" deleted="true">http://localhost/file3/object</Entity>
      </Entities>
    </EntitiesResponse>
    val entitiesF = new DataProcessor[F]().getEntities(input)
    val entities = valueFromF(entitiesF)

    def checkResponse(
        entity: Entity,
        uuid: String,
        entityType: String,
        fileName: String,
        description: String,
        deleted: Boolean = false
    ) = {
      entity.path.getOrElse("") should equal(entityType)
      entity.ref.toString should equal(uuid)
      entity.deleted should equal(deleted)
      entity.title.getOrElse("") should equal(fileName)
      entity.description.getOrElse("") should equal(description)
    }

    checkResponse(
      entities.head,
      "8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91",
      "information-objects",
      "file1.txt",
      "A description"
    )
    checkResponse(
      entities.tail.head,
      "2d8a9935-3a1a-45ce-aadb-f01f2ddc9405",
      "structural-objects",
      "file2.txt",
      ""
    )
    checkResponse(
      entities.last,
      "99fb8809-be86-4636-9b3f-4a181de0bc36",
      "",
      "",
      "",
      deleted = true
    )
  }

  "getEventActions" should "return the correct event actions using the date value from the 'Event'" in {
    val input = <EventActionsResponse>
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
        <xip:EventAction commandType="AddIdentifier">
          <xip:Event type="Modified">
            <xip:Ref>efe9b25d-c3b4-476a-8ff1-d52fb01ad96b</xip:Ref>
            <xip:Date>2023-06-27T08:14:08.442Z</xip:Date>
            <xip:User>test user</xip:User>
          </xip:Event>
          <xip:Date>2023-06-27T08:14:07.442Z</xip:Date>
          <xip:Entity>a9e1cae8-ea06-4157-8dd4-82d0525b031c</xip:Entity>
        </xip:EventAction>
        <xip:EventAction commandType="Moved">
          <xip:Event type="Moved">
            <xip:Ref>68c3adcc-8e1f-40f7-84a0-ca80c5969ef7</xip:Ref>
            <xip:Date>2023-06-28T08:14:08.442Z</xip:Date>
            <xip:User>test user</xip:User>
          </xip:Event>
          <xip:Date>2023-06-28T08:14:07.442Z</xip:Date>
          <xip:Entity>a9e1cae8-ea06-4157-8dd4-82d0525b031c</xip:Entity>
        </xip:EventAction>
      </EventActions>
    </EventActionsResponse>

    val eventActionsF = new DataProcessor[F]().getEventActions(input)
    val eventActions = valueFromF(eventActionsF)

    eventActions should be(
      List(
        DataProcessor.EventAction(
          UUID.fromString("6da319fa-07e0-4a83-9c5a-b6bad08445b1"),
          "Ingest",
          ZonedDateTime.parse("2023-06-26T08:14:08.441Z")
        ),
        DataProcessor.EventAction(
          UUID.fromString("efe9b25d-c3b4-476a-8ff1-d52fb01ad96b"),
          "Modified",
          ZonedDateTime.parse("2023-06-27T08:14:08.442Z")
        ),
        DataProcessor.EventAction(
          UUID.fromString("68c3adcc-8e1f-40f7-84a0-ca80c5969ef7"),
          "Moved",
          ZonedDateTime.parse("2023-06-28T08:14:08.442Z")
        )
      )
    )
  }

  "getEntity" should "return the full entity if all fields are provided" in {
    val id = UUID.randomUUID()
    val entityResponse = <EntityResponse>
      <StructuralObject>
        <Title>Title</Title>
        <Description>A description</Description>
        <SecurityTag>open</SecurityTag>
        <Deleted>true</Deleted>
        <Parent>f567352f-0874-49da-85aa-ac0fbfa3b335</Parent>
      </StructuralObject>
    </EntityResponse>

    val response = valueFromF(new DataProcessor[F]().getEntity(id, entityResponse, StructuralObject))
    response.title.get should equal("Title")
    response.description.get should equal("A description")
    response.securityTag.get should equal(Open)
    response.deleted should equal(true)
    response.parent.get should equal(UUID.fromString("f567352f-0874-49da-85aa-ac0fbfa3b335"))
  }

  "getEntity" should "return deleted false if the deleted tag is missing" in {
    val id = UUID.randomUUID()
    val entityResponse = <EntityResponse>
      <StructuralObject>
      </StructuralObject>
    </EntityResponse>

    val response = valueFromF(new DataProcessor[F]().getEntity(id, entityResponse, StructuralObject))

    response.deleted should equal(false)
  }

  "getEntity" should "return a missing security tag if it isn't set to open or closed" in {
    val id = UUID.randomUUID()
    val entityResponse = <EntityResponse>
      <StructuralObject>
        <SecurityTag>test</SecurityTag>
      </StructuralObject>
    </EntityResponse>

    val response = valueFromF(new DataProcessor[F]().getEntity(id, entityResponse, StructuralObject))

    response.securityTag.isDefined should equal(false)
  }

  "getEntity" should "return a missing parent, title and description if they aren't set" in {
    val id = UUID.randomUUID()
    val entityResponse = <EntityResponse>
      <StructuralObject>
      </StructuralObject>
    </EntityResponse>

    val response = valueFromF(new DataProcessor[F]().getEntity(id, entityResponse, StructuralObject))

    response.title.isDefined should equal(false)
    response.description.isDefined should equal(false)
    response.parent.isDefined should equal(false)
  }

  "getEntity" should "return an error if the entity type in the response doesn't match" in {
    val id = UUID.randomUUID()
    val entityResponse = <EntityResponse>
      <InformationObject>
      </InformationObject>
    </EntityResponse>

    val exception = intercept[PreservicaClientException] {
      valueFromF(new DataProcessor[F]().getEntity(id, entityResponse, StructuralObject))
    }

    exception.getMessage should equal(s"Entity not found for id $id")
  }

  "childNodeFromWorkflowInstance" should "return the node requested" in {
    val input =
      <WorkflowInstance xmlns="http://workflow.preservica.com">
        <Id>3</Id>
      </WorkflowInstance>

    val childNodeResponseF = new DataProcessor[F]().childNodeFromWorkflowInstance(input, "Id")
    val value = valueFromF(childNodeResponseF)

    value should equal("3")
  }

  "childNodeFromWorkflowInstance" should "return an error if child node does not exist" in {
    val input =
      <WorkflowInstance xmlns="http://workflow.preservica.com">
        <Id>3</Id>
      </WorkflowInstance>

    val childNodeResponseF = new DataProcessor[F]().childNodeFromWorkflowInstance(input, "InvalidNode")

    val error = intercept[PreservicaClientException] {
      valueFromF(childNodeResponseF)
    }
    val expectedMessage = "'InvalidNode' does not exist on the workflowInstance response."

    error.getMessage should equal(expectedMessage)
  }

  "getIdentifiers" should "return an empty list if there are no identifiers" in {
    val input = <IdentifiersResponse></IdentifiersResponse>
    val identifiers = valueFromF(new DataProcessor[F]().getIdentifiers(input))
    identifiers.size should equal(0)
  }

  "getIdentifiers" should "return the identifiers" in {
    val input = <IdentifiersResponse>
      <Identifiers>
        <Identifier>
          <ApiId>1</ApiId>
          <Type>TestType1</Type>
          <Value>TestValue1</Value>
        </Identifier>
        <Identifier>
          <ApiId>2</ApiId>
          <Type>TestType2</Type>
          <Value>TestValue2</Value>
        </Identifier>
      </Identifiers>
    </IdentifiersResponse>
    val identifiers = valueFromF(new DataProcessor[F]().getIdentifiers(input)).sortBy(_.id)

    identifiers.size should equal(2)
    val firstIdentifier = identifiers.head
    val secondIdentifier = identifiers.last

    firstIdentifier.id should equal("1")
    firstIdentifier.identifierName should equal("TestType1")
    firstIdentifier.value should equal("TestValue1")

    secondIdentifier.id should equal("2")
    secondIdentifier.identifierName should equal("TestType2")
    secondIdentifier.value should equal("TestValue2")
  }

}
