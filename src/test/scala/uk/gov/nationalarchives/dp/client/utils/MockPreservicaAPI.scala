package uk.gov.nationalarchives.dp.client.utils

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.*
import uk.gov.nationalarchives.dp.client.Entities
import uk.gov.nationalarchives.dp.client.Entities.{Entity, IdentifierResponse}
import uk.gov.nationalarchives.dp.client.EntityClient.EntityType.*
import uk.gov.nationalarchives.dp.client.EntityClient.RepresentationType.{Access, Preservation}
import uk.gov.nationalarchives.dp.client.EntityClient.{EntityType, Identifier, RepresentationType, apiVersion}

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

object MockPreservicaAPI {
  val tokenResponse: String = """{"token": "abcde"}"""
  val tokenUrl = "/api/accesstoken/login"

  val xipVersion: Float = apiVersion
  val xipUrl = s"http://preservica.com/XIP/v$xipVersion"
  val namespaceUrl = s"http://preservica.com/EntityAPI/v$apiVersion"
  val entityShortNameToLong: Map[String, String] =
    Map("SO" -> "StructuralObject", "IO" -> "InformationObject", "CO" -> "ContentObject")

  def createEntity(
      entityType: EntityType = StructuralObject
  ): Entity = {
    Entities.Entity(
      Some(entityType),
      UUID.fromString("a9e1cae8-ea06-4157-8dd4-82d0525b031c"),
      Option("title"),
      Option("description"),
      deleted = false,
      Some(entityType.entityPath)
    )
  }

  case class EntityClientEndpoints(preservicaServer: WireMockServer, potentialEntityInfoToUse: Option[Entity] = None) {
    preservicaServer.stubFor(post(urlEqualTo(tokenUrl)).willReturn(ok(tokenResponse)))

    lazy val preservicaUrl = s"http://localhost:$preservicaPort"
    lazy val apiUrl = s"/api/entity/v$apiVersion"
    lazy val entity: Entity = potentialEntityInfoToUse.getOrElse(createEntity())
    lazy val entityUrl = s"$apiUrl/${entity.path.get}"
    lazy val specificEntityUrl = s"$entityUrl/${entity.ref}"
    lazy val representationsUrl = s"$apiUrl/information-objects/${entity.ref}/representations"
    lazy val generationsUrl = s"$apiUrl/content-objects/${entity.ref}/generations"
    lazy val generationOneUrl = s"$generationsUrl/1"
    lazy val generationTwoUrl = s"$generationsUrl/2"
    lazy val bitstreamOneInfoUrl = s"$generationOneUrl/bitstreams/1"
    lazy val bitstreamTwoInfoUrl = s"$generationTwoUrl/bitstreams/1"
    lazy val bitstreamOneContentUrl = s"$bitstreamOneInfoUrl/content"
    lazy val bitstreamTwoContentUrl = s"$bitstreamTwoInfoUrl/content"
    lazy val metadataFragmentOneUrl = s"$specificEntityUrl/metadata/28d79967-24f8-4bfd-b420-a29be8694d66"
    lazy val metadataFragmentTwoUrl = s"$specificEntityUrl/metadata/63e50476-3387-451a-814b-354b5969407a"
    lazy val identifiersUrl = s"$specificEntityUrl/identifiers"
    lazy val linksUrl = s"$specificEntityUrl/links"
    lazy val eventActionsUrl = s"$specificEntityUrl/event-actions"
    lazy val updatedSinceUrl = s"$apiUrl/entities/updated-since"
    lazy val rootChildrenUrl = s"$apiUrl/root/children"
    lazy val retentionPoliciesUrl = "/api/entity/retention-policies"
    lazy private val preservicaPort = preservicaServer.port()

    def specificIdentifierUrl(id: String) = s"$identifiersUrl/$id"

    def representationTypeUrl(repType: RepresentationType, count: Int = 1) =
      s"$representationsUrl/${repType.toString}/$count"
    def entitiesByIdentifierUrl(identifier: Identifier) =
      s"$apiUrl/entities/by-identifier?type=${identifier.identifierName}&value=${identifier.value}"

    def serverErrorStub(url: String): String = {
      preservicaServer.stubFor(post(urlEqualTo(url)).willReturn(serverError()))
      url
    }

    def stubAddEntity(successfulResponse: Boolean = true): String = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse) ok(entityResponse()) else badRequest()
      preservicaServer.stubFor(post(urlEqualTo(entityUrl)).willReturn(response))
      entityUrl
    }

    def stubUpdateEntity(successfulResponse: Boolean = true): String = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse) ok(entityResponse()) else badRequest()
      preservicaServer.stubFor(put(urlEqualTo(specificEntityUrl)).willReturn(response))
      specificEntityUrl
    }

    def stubGetEntity(
        successfulResponse: Boolean = true,
        emptyResponse: Boolean = false,
        returnMetadataFragmentUrls: Boolean = true
    ): String = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse)
          if (emptyResponse)
            ok(<EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}></EntityResponse>.toString())
          else ok(entityResponse(returnMetadataFragmentUrls))
        else serverError()
      preservicaServer.stubFor(get(urlEqualTo(specificEntityUrl)).willReturn(response))
      specificEntityUrl
    }

    def stubIoRepresentationUrls(successfulResponse: Boolean = true, emptyResponse: Boolean = false): String = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse)
          if (emptyResponse)
            ok(<RepresentationResponse></RepresentationResponse>.toString)
          else
            ok(urlsToRepresentationsResponse)
        else serverError()
      preservicaServer.stubFor(get(urlEqualTo(representationsUrl)).willReturn(response))
      representationsUrl
    }

    def stubIoRepresentations(
        representationType: RepresentationType,
        count: Int = 1,
        successfulResponse: Boolean = true,
        emptyResponse: Boolean = false
    ): String = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse)
          if (emptyResponse)
            ok(<RepresentationResponse></RepresentationResponse>.toString)
          else
            ok(representationTypeResponse(representationType))
        else serverError()
      val repTypeUrl = representationTypeUrl(representationType, count)
      preservicaServer.stubFor(get(urlEqualTo(repTypeUrl)).willReturn(response))
      repTypeUrl
    }

    def stubCoGenerations(successfulResponse: Boolean = true): String = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse) ok(urlsToGenerationsResponse) else badRequest()
      preservicaServer.stubFor(get(urlEqualTo(generationsUrl)).willReturn(response))
      generationsUrl
    }

    def stubCoGeneration(successfulResponse: Boolean = true): List[String] = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse) ok(generationResponse(generationOneUrl)) else badRequest()

      preservicaServer.stubFor(get(urlEqualTo(generationOneUrl)).willReturn(response))
      preservicaServer.stubFor(get(urlEqualTo(generationTwoUrl)).willReturn(ok(generationResponse(generationTwoUrl))))
      List(generationOneUrl, generationTwoUrl)
    }

    def stubCoBitstreamInfo(successfulResponse: Boolean = true, emptyResponse: Boolean = false): List[String] = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse)
          if (emptyResponse)
            ok(<EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}></EntityResponse>.toString())
          else ok(bitstreamResponse(bitstreamOneInfoUrl, bitstreamOneContentUrl))
        else serverError()

      preservicaServer.stubFor(get(urlEqualTo(bitstreamOneInfoUrl)).willReturn(response))
      preservicaServer.stubFor(
        get(urlEqualTo(bitstreamTwoInfoUrl)).willReturn(
          ok(bitstreamResponse(bitstreamTwoInfoUrl, bitstreamTwoContentUrl))
        )
      )
      List(bitstreamOneInfoUrl, bitstreamTwoInfoUrl)
    }

    def stubStreamBitstreamContent(successfulResponse: Boolean = true): List[String] = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse) ok() else badRequest()

      preservicaServer.stubFor(get(urlEqualTo(bitstreamOneContentUrl)).willReturn(response))
      preservicaServer.stubFor(get(urlEqualTo(bitstreamTwoContentUrl)).willReturn(ok()))
      List(bitstreamOneContentUrl, bitstreamTwoContentUrl)
    }

    def stubLinks(successfulResponse: Boolean = true): List[String] = {
      val firstResponse: ResponseDefinitionBuilder =
        if (successfulResponse) ok(linksUrlFirstPageResponse()) else badRequest()

      val linksFirstPage = s"$linksUrl?max=1000&start=0"
      val linksSecondPage = s"$linksUrl?max=1000&start=1000"

      preservicaServer.stubFor(get(urlEqualTo(linksFirstPage)).willReturn(firstResponse))
      preservicaServer.stubFor(get(urlEqualTo(linksSecondPage)).willReturn(ok(linksUrlSecondPageResponse())))
      List(linksFirstPage, linksSecondPage)
    }

    def stubMetadataFragment(
        fragmentNum: Int,
        metadataFragmentUrl: String,
        returnFragmentResponse: Boolean = true,
        successfulResponse: Boolean = true
    ): String = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse) ok(metadataFragmentResponse(fragmentNum, returnFragmentResponse)) else badRequest()

      preservicaServer.stubFor(get(urlEqualTo(metadataFragmentUrl)).willReturn(response))
      metadataFragmentUrl
    }

    def stubEventActions(successfulResponse: Boolean = true): List[String] = {
      val firstResponse: ResponseDefinitionBuilder =
        if (successfulResponse) ok(eventActionsFirstPageResponse) else badRequest()

      val eventActionsFirstPageUrl = s"$eventActionsUrl?max=1000&start=0"
      val eventActionsSecondPageUrl = s"$eventActionsUrl?max=1000&start=1000"

      preservicaServer.stubFor(get(urlEqualTo(eventActionsFirstPageUrl)).willReturn(firstResponse))
      preservicaServer.stubFor(
        get(urlEqualTo(eventActionsSecondPageUrl)).willReturn(ok(eventActionsSecondPageResponse))
      )
      List(eventActionsFirstPageUrl, eventActionsSecondPageUrl)
    }

    def stubEntitiesByIdentifiers(
        identifiers: List[Identifier],
        successfulResponse: Boolean = true,
        emptyResponse: Boolean = false
    ): List[String] = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse)
          if (emptyResponse)
            ok(<EntitiesResponse><Entities></Entities></EntitiesResponse>.toString)
          else
            ok(entitiesByIdentifierPageResponse)
        else badRequest()

      identifiers.map { identifier =>
        val entitiesByIdUrl = entitiesByIdentifierUrl(identifier)
        preservicaServer.stubFor(get(urlEqualTo(entitiesByIdUrl)).willReturn(response))
        entitiesByIdUrl
      }
    }

    def stubEntitiesUpdatedSince(
        updatedSince: ZonedDateTime,
        successfulResponse: Boolean = true,
        emptyResponse: Boolean = false,
        potentialEndDate: Option[ZonedDateTime] = None
    ): String = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse)
          if (emptyResponse)
            ok(<EntitiesResponse><Entities></Entities></EntitiesResponse>.toString)
          else
            ok(entitiesUpdatedSincePageResponse)
        else badRequest()

      val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
      val dateString = updatedSince.format(formatter)
      val baseUrl = s"$updatedSinceUrl?date=$dateString&max=1000&start=0"
      val updatedSinceReqUrl = potentialEndDate
        .map(endDate => s"$baseUrl&endDate=${endDate.format(formatter)}")
        .getOrElse(baseUrl)

      preservicaServer.stubFor(get(urlEqualTo(updatedSinceReqUrl)).willReturn(response))
      updatedSinceReqUrl
    }

    def stubGetIdentifierForEntity(successfulResponse: Boolean = true, emptyResponse: Boolean = false): String = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse)
          if (emptyResponse)
            ok(<IdentifiersResponse></IdentifiersResponse>.toString)
          else
            ok(getIdentifierForEntityResponse)
        else serverError()
      preservicaServer.stubFor(get(urlEqualTo(identifiersUrl)).willReturn(response))
      identifiersUrl
    }

    def stubAddIdentifierForEntity(identifier: Identifier, successfulResponse: Boolean = true): String = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse) ok(addIdentifierForEntityResponse(identifier)) else badRequest()
      preservicaServer.stubFor(post(urlEqualTo(identifiersUrl)).willReturn(response))
      identifiersUrl
    }

    def stubUpdateEntityIdentifiers(
        identifierResponse: IdentifierResponse,
        successfulResponse: Boolean = true
    ): String = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse) ok(updateIdentifierForEntityResponse(identifierResponse))
        else serverError()
      val specificIdUrl = specificIdentifierUrl(identifierResponse.id)
      preservicaServer.stubFor(put(urlEqualTo(specificIdUrl)).willReturn(response))
      specificIdUrl
    }

    def stubGetEntityIdentifiers(entityType: EntityType, successfulResponse: Boolean = true): String = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse) ok(entityResponse()) else badRequest()
      preservicaServer.stubFor(get(urlEqualTo(specificEntityUrl)).willReturn(response))
      specificEntityUrl
    }

    def stubRetentionPolicies(successfulResponse: Boolean = true): String = {
      val response: ResponseDefinitionBuilder =
        if (successfulResponse) ok(retentionPoliciesResponse()) else badRequest()
      preservicaServer.stubFor(get(urlEqualTo(retentionPoliciesUrl)).willReturn(response))
      retentionPoliciesUrl
    }

    def stubRootChildren(successfulResponse: Boolean = true): List[String] = {
      val firstResponse: ResponseDefinitionBuilder =
        if (successfulResponse) ok(rootChildrenFirstPageResponse) else badRequest()

      val rootChildrenFirstPageUrl = s"$rootChildrenUrl?max=1000&start=0"
      val rootChildrenSecondPageUrl = s"$rootChildrenUrl?max=1000&start=1000"
      val so1FirstPageUrl = s"$entityUrl/a9e1cae8-ea06-4157-8dd4-82d0525b031c/children?max=1000&start=0"
      val so2FirstPageUrl = s"$entityUrl/71143bfd-b29f-4548-871c-8334f2d2bcb8/children?max=1000&start=0"
      val so3FirstPageUrl = s"$entityUrl/dd672e2c-6248-43d7-81ff-c632acfc8fd7/children?max=1000&start=0"

      val ioOne1FirstPageUrl =
        s"$apiUrl/information-object/174eb617-2d05-4920-a764-99cdbdae94a1/children?max=1000&start=0"
      val soOne2FirstPageUrl = s"$entityUrl/b107677d-745f-4cb8-94c7-e31383f2eb0b/children?max=1000&start=0"

      preservicaServer.stubFor(get(urlEqualTo(rootChildrenFirstPageUrl)).willReturn(firstResponse))
      preservicaServer.stubFor(
        get(urlEqualTo(rootChildrenSecondPageUrl)).willReturn(ok(rootChildrenSecondPageResponse))
      )
      preservicaServer.stubFor(get(urlEqualTo(so1FirstPageUrl)).willReturn(ok(so1ChildrenFirstPageResponse)))
      preservicaServer.stubFor(get(urlEqualTo(so2FirstPageUrl)).willReturn(ok(secondPageEmptyResponse)))
      preservicaServer.stubFor(get(urlEqualTo(so3FirstPageUrl)).willReturn(ok(secondPageEmptyResponse)))

      preservicaServer.stubFor(get(urlEqualTo(ioOne1FirstPageUrl)).willReturn(ok(ioOne1ChildrenFirstPageResponse)))
      preservicaServer.stubFor(get(urlEqualTo(soOne2FirstPageUrl)).willReturn(ok(secondPageEmptyResponse)))

      List(
        rootChildrenFirstPageUrl,
        rootChildrenSecondPageUrl,
        so1FirstPageUrl,
        so2FirstPageUrl,
        so3FirstPageUrl,
        ioOne1FirstPageUrl,
        stubIoRepresentationUrls(),
        stubIoRepresentations(Preservation),
        soOne2FirstPageUrl
      )
    }

    private def urlsToRepresentationsResponse: String =
      <RepresentationsResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Representations>
          <Representation type="Preservation">{preservicaUrl + representationTypeUrl(Preservation)}</Representation>
          <Representation type="Access" name="Access name1">{
        preservicaUrl + representationTypeUrl(Access)
      }</Representation>
          <Representation type="Access" name="Access name2">{
        preservicaUrl + representationTypeUrl(Access, 2)
      }</Representation>
        </Representations>
      </RepresentationsResponse>.toString()

    private def representationTypeResponse(representationType: RepresentationType): String =
      <RepresentationResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <xip:Representation>
          <xip:InformationObject>{entity.ref}</xip:InformationObject>
          <xip:Name>{representationType.toString}</xip:Name>
          <xip:Type>{representationType.toString}</xip:Type>
          <xip:ContentObjects>
            <xip:ContentObject>ad30d41e-b75c-4195-b569-91e820f430ac</xip:ContentObject>
            <xip:ContentObject>354f47cf-3ca2-4a4e-8181-81b714334f00</xip:ContentObject>
          </xip:ContentObjects>
          <xip:RepresentationFormats/>
          <xip:RepresentationProperties/>
        </xip:Representation>
        <ContentObjects/>
        <AdditionalInformation>
        </AdditionalInformation>
      </RepresentationResponse>.toString()

    private def urlsToGenerationsResponse =
      <GenerationsResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Generations>
          <Generation active="true">{preservicaUrl + generationOneUrl}</Generation>
          <Generation active="true">{preservicaUrl + generationTwoUrl}</Generation>
        </Generations>
      </GenerationsResponse>.toString()

    private def generationResponse(generationUrl: String) =
      <GenerationResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Generation original={if (generationUrl.last == '1') "true" else "false"} active="true">
        </Generation>
        <Bitstreams>
          <Bitstream filename="test1.txt">{preservicaUrl + generationUrl + "/bitstreams/1"}</Bitstream>
        </Bitstreams>
      </GenerationResponse>.toString()

    private def bitstreamResponse(infoUrl: String, contentUrl: String) =
      <BitstreamResponse>
        <xip:Bitstream>
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
        </xip:Bitstream>
        <AdditionalInformation>
          <Self>{preservicaUrl + infoUrl}</Self>
          <Content>{preservicaUrl + contentUrl}</Content>
        </AdditionalInformation>
      </BitstreamResponse>.toString()

    private def metadataFragmentResponse(fragmentNum: Int, returnFragmentResponse: Boolean = true): String =
      if (returnFragmentResponse)
        s"""<MetadataResponse xmlns="$namespaceUrl" xmlns:xip="$xipUrl">
          <MetadataContainer>
            <Content>
              <Test$fragmentNum>
                <Test${fragmentNum}Value>Test${fragmentNum}Value</Test${fragmentNum}Value>
              </Test$fragmentNum>
            </Content>
          </MetadataContainer>
        </MetadataResponse>"""
      else
        <MetadataResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        </MetadataResponse>.toString

    private def eventActionsFirstPageResponse: String =
      <EventActionsResponse>
        <EventActions>
          <xip:EventAction commandType="command_create">
            <xip:Event type="Ingest">
              <xip:Ref>6da319fa-07e0-4a83-9c5a-b6bad08445b1</xip:Ref>
              <xip:Date>2023-06-26T08:14:08.441Z</xip:Date>
              <xip:User>test user</xip:User>
            </xip:Event>
            <xip:Date>2023-06-26T08:14:07.441Z</xip:Date>
            <xip:Entity>{entity.ref}</xip:Entity>
          </xip:EventAction>
        </EventActions>
        <Paging>
          <Next>{s"$preservicaUrl$eventActionsUrl"}?max=1000&amp;start=1000</Next>
        </Paging>
      </EventActionsResponse>.toString

    private def eventActionsSecondPageResponse: String =
      <EventActionsResponse>
        <EventActions>
          <xip:EventAction commandType="AddIdentifier">
            <xip:Event type="Modified">
              <xip:Ref>efe9b25d-c3b4-476a-8ff1-d52fb01ad96b</xip:Ref>
              <xip:Date>2023-06-27T08:14:08.442Z</xip:Date>
              <xip:User>test user</xip:User>
            </xip:Event>
            <xip:Date>2023-06-27T08:14:07.442Z</xip:Date>
            <xip:Entity>{entity.ref}</xip:Entity>
          </xip:EventAction>
        </EventActions>
        <Paging>
        </Paging>
      </EventActionsResponse>.toString

    private def entitiesByIdentifierPageResponse: String = {
      val entityRef = entity.ref.toString
      val entityTypeShort = entity.entityType.get.entityTypeShort

      <EntitiesResponse>
        <Entities>
          <Entity title="page1File.txt" ref={entityRef} type={entityTypeShort}>http://localhost/page1/object</Entity>
        </Entities>
        <Paging>
          <TotalResults>1</TotalResults>
        </Paging>
      </EntitiesResponse>.toString
    }

    private def entitiesUpdatedSincePageResponse: String =
      <EntitiesResponse>
        <Entities>
          <Entity title="page1File.txt" ref="8a8b1582-aa5f-4eb0-9c5d-2c16049fcb91" type="IO">http://localhost/page1/object</Entity>
        </Entities>
        <Paging>
          <Next>{updatedSinceUrl}?date=2023-04-25T00%3A00%3A00.000Z&amp;start=1000&amp;max=1000</Next>
        </Paging>
      </EntitiesResponse>.toString

    private def getIdentifierForEntityResponse =
      <IdentifiersResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Identifiers>
          <Identifier>
            <xip:ApiId>acb1e74b1ad5c4bfc360ef5d44228c9f</xip:ApiId>
            <xip:Type>Test Type 1</xip:Type>
            <xip:Value>Test Value 1</xip:Value>
            <xip:Entity>{entity.ref}</xip:Entity>
          </Identifier>
          <Identifier>
            <xip:ApiId>65862d40f40440de14c1b75e5f342e99</xip:ApiId>
            <xip:Type>Test Type 2</xip:Type>
            <xip:Value>Test Value 2</xip:Value>
            <xip:Entity>{entity.ref}</xip:Entity>
          </Identifier>
        </Identifiers>
      </IdentifiersResponse>.toString()

    private def addIdentifierForEntityResponse(identifier: Identifier) =
      <IdentifiersResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <xip:Identifier>
          <xip:ApiId>65862d40f40440de14c1b75e5f342e99</xip:ApiId>
          <xip:Type>{identifier.identifierName}</xip:Type>
          <xip:Value>{identifier.value}</xip:Value>
          <xip:Entity>{entity.ref}</xip:Entity>
        </xip:Identifier>
      </IdentifiersResponse>.toString()

    private def updateIdentifierForEntityResponse(identifier: IdentifierResponse) =
      <IdentifiersResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <xip:Identifier>
          <xip:ApiId>65862d40f40440de14c1b75e5f342e99</xip:ApiId>
          <xip:Type>{identifier.identifierName}</xip:Type>
          <xip:Value>{identifier.value}</xip:Value>
          <xip:Entity>{entity.ref}</xip:Entity>
        </xip:Identifier>
      </IdentifiersResponse>.toString()

    private def entityResponse(returnMetadataFragmentUrls: Boolean = true) = {
      val genericEntity =
        <GenericEntity>
            <xip:Ref>{entity.ref}</xip:Ref>
            <xip:Title>page1File.txt</xip:Title>
            <xip:Description>A description</xip:Description>
            <xip:SecurityTag>open</xip:SecurityTag>
            <xip:Parent>58412111-c73d-4414-a8fc-495cfc57f7e1</xip:Parent>
          </GenericEntity>

      val metadata =
        if (returnMetadataFragmentUrls)
          <Metadata>
            <Fragment>{preservicaUrl + metadataFragmentOneUrl}</Fragment>
            <Fragment>{preservicaUrl + metadataFragmentTwoUrl}</Fragment>
          </Metadata>
        else
          <Metadata></Metadata>

      entity.entityType.get match {
        case StructuralObject =>
          <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
            <xip:StructuralObject>
              {genericEntity.child.tail.dropRight(1)}
            </xip:StructuralObject>
            <AdditionalInformation>
              {metadata}
            </AdditionalInformation>
          </EntityResponse>.toString()
        case InformationObject =>
          <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
            <xip:InformationObject>
              {genericEntity.child.tail.dropRight(1)}
            </xip:InformationObject>
            <AdditionalInformation>
              {metadata}
            </AdditionalInformation>
          </EntityResponse>.toString()
        case ContentObject =>
          <EntityResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
            <xip:ContentObject>
              {genericEntity.child.tail.dropRight(1)}
            </xip:ContentObject>
            <AdditionalInformation>
              {metadata}
              <Generations>{preservicaUrl + generationsUrl}</Generations>
            </AdditionalInformation>
          </EntityResponse>.toString()
      }
    }

    private def linksUrlFirstPageResponse(): String =
      <LinksResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Links>
          <Link linkType="VirtualChild" linkDirection="From" title="Linked folder" ref="758ef5c5-a364-40e5-bd78-e40f72f5a1f0"
                type="SO" apiId="ccea29cb2c3254e753aa91939e9b5370" xmlns={namespaceUrl} xmlns:xip={xipUrl}>{
        preservicaUrl + specificEntityUrl
      }</Link>
        </Links>
        <Paging>
          <Next>{preservicaUrl + linksUrl}?max=1000&amp;start=1000</Next>
        </Paging>
      </LinksResponse>.toString

    private def linksUrlSecondPageResponse(): String =
      <LinksResponse xmlns={namespaceUrl} xmlns:xip={xipUrl}>
        <Links>
          <Link linkType="VirtualChild" linkDirection="From" title="Linked asset" ref="71143bfd-b29f-4548-871c-8334f2d2bcb8"
                type="IO" apiId="aa61c369f543056586779625143d3ca3" xmlns={namespaceUrl} xmlns:xip={xipUrl}>{
        preservicaUrl + specificEntityUrl
      }</Link>
          <Link linkType="CitedBy" linkDirection="To" title="Source material" ref="866d4c6e-ee51-467a-b7a3-e4b65709cf95"
                type="IO" apiId="16d02f195c1da0aac0f755ba599d5705" xmlns={namespaceUrl} xmlns:xip={xipUrl}>{
        preservicaUrl + specificEntityUrl
      }</Link>
        </Links>
        <Paging>
        </Paging>
      </LinksResponse>.toString

    private def rootChildrenFirstPageResponse: String =
      <ChildrenResponse>
        <Children>
          <Child title="SO 1 Title" ref="a9e1cae8-ea06-4157-8dd4-82d0525b031c" type="SO" overlays="lock" icon="folder">{
        entityUrl
      }/a9e1cae8-ea06-4157-8dd4-82d0525b031c</Child>
          <Child title="SO 2 Title" ref="71143bfd-b29f-4548-871c-8334f2d2bcb8" type="SO" icon="folder">{
        entityUrl
      }/71143bfd-b29f-4548-871c-8334f2d2bcb8</Child>
        </Children>
        <Paging>
          <Next>{s"$preservicaUrl$rootChildrenUrl"}?max=1000&amp;start=1000</Next>
          <TotalResults>2</TotalResults>
        </Paging>
      </ChildrenResponse>.toString

    private def rootChildrenSecondPageResponse: String =
      <ChildrenResponse>
        <Children>
          <Child title="SO 3 Title" ref="dd672e2c-6248-43d7-81ff-c632acfc8fd7" type="SO" overlays="lock" icon="folder">{
        entityUrl
      }/dd672e2c-6248-43d7-81ff-c632acfc8fd7</Child>
        </Children>
        <Paging>
          <TotalResults>1</TotalResults>
        </Paging>
      </ChildrenResponse>.toString

    private def secondPageEmptyResponse: String =
      <ChildrenResponse>
        <Children>
        </Children>
        <Paging>
          <TotalResults>0</TotalResults>
        </Paging>
      </ChildrenResponse>.toString

    private def so1ChildrenFirstPageResponse: String =
      <ChildrenResponse>
        <Children>
          <Child title="IO 1_1 Title" ref={entity.ref.toString} type="IO" overlays="lock" icon="folder">
            {entityUrl}/{entity.ref}</Child>
          <Child title="SO 1_2 Title" ref="b107677d-745f-4cb8-94c7-e31383f2eb0b" type="SO" icon="folder">
            {entityUrl}/b107677d-745f-4cb8-94c7-e31383f2eb0b</Child>
        </Children>
        <Paging>
          <TotalResults>2</TotalResults>
        </Paging>
      </ChildrenResponse>.toString

    private def ioOne1ChildrenFirstPageResponse: String =
      <ChildrenResponse>
        <Children>
          <Child title="CO 1_1_1 Title" ref="4131ab3d-2549-482d-948f-adf9478ff4cb" type="IO" overlays="lock" icon="folder">
            {entityUrl}/4131ab3d-2549-482d-948f-adf9478ff4cb</Child>
        </Children>
        <Paging>
          <TotalResults>1</TotalResults>
        </Paging>
      </ChildrenResponse>.toString

    private def retentionPoliciesResponse(): String =
      <RetentionPoliciesResponse  xmlns={
        namespaceUrl
      }  xmlns:xip="http://preservica.com/XIP/v6.9" xmlns:retention="http://preservica.com/RetentionManagement/v6.2">
      </RetentionPoliciesResponse>.toString
  }
}
