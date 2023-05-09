package uk.gov.nationalarchives.dp.client

import cats.MonadError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._

abstract class DataProcessorTest[F[_]](implicit cme: MonadError[F, Throwable]) extends AnyFlatSpec {
  def valueFromF[T](value: F[T]): T

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

  "allBitstreamInfo" should "return the correct names and urls" in {
    val input = Seq(
      <GenerationResponse>
        <Bitstreams>
          <Bitstream filename="test1">http://test1</Bitstream>
        </Bitstreams>
      </GenerationResponse>,
      <GenerationResponse>
        <Bitstreams>
          <Bitstream filename="test2">http://test2</Bitstream>
        </Bitstreams>
      </GenerationResponse>
    )
    val generationsF = new DataProcessor[F]().allBitstreamInfo(input)
    val generations = valueFromF(generationsF)

    generations.size should equal(2)
    generations.head.url should equal("http://test1")
    generations.head.name should equal("test1")
    generations.last.url should equal("http://test2")
    generations.last.name should equal("test2")
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

  "updatedEntities" should "return the correct entity objects" in {
    val input = <EntitiesResponse>
      <Entities>
        <Entity title="file1.txt" ref="8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91" type="IO">http://localhost/file1/object</Entity>
        <Entity title="file2.txt" ref="2d8a9935-3a1a-45ce-aadb-f01f2ddc9405" type="SO">http://localhost/file2/object</Entity>
        <Entity title="file3.txt" ref="99fb8809-be86-4636-9b3f-4a181de0bc36" type="CO" deleted="true">http://localhost/file3/object</Entity>
      </Entities>
    </EntitiesResponse>
    val entitiesF = new DataProcessor[F]().getUpdatedEntities(input)
    val entities = valueFromF(entitiesF)

    def checkResponse(
        entity: Entity,
        uuid: String,
        entityType: String,
        fileNumber: Int,
        deleted: Boolean = false
    ) = {
      entity.path should equal(entityType)
      entity.ref.toString should equal(uuid)
      entity.deleted should equal(deleted)
      entity.title should equal(s"file$fileNumber.txt")
    }

    checkResponse(entities.head, "8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91", "information-objects", 1)
    checkResponse(
      entities.tail.head,
      "2d8a9935-3a1a-45ce-aadb-f01f2ddc9405",
      "structural-objects",
      2
    )
    checkResponse(
      entities.last,
      "99fb8809-be86-4636-9b3f-4a181de0bc36",
      "content-objects",
      3,
      deleted = true
    )
  }
}
