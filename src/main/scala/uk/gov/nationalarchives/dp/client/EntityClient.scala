package uk.gov.nationalarchives.dp.client

import cats.Parallel
import cats.effect.Async
import cats.implicits.*
import sttp.capabilities.Streams
import sttp.client3.*
import sttp.model.Method
import uk.gov.nationalarchives.dp.client.Client.*
import uk.gov.nationalarchives.dp.client.DataProcessor.EventAction
import uk.gov.nationalarchives.dp.client.Entities.EntityRef.*
import uk.gov.nationalarchives.dp.client.Entities.{Entity, EntityRef, IdentifierResponse}
import uk.gov.nationalarchives.dp.client.EntityClient.*
import uk.gov.nationalarchives.dp.client.EntityClient.EntityType.*

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.util.Try
import scala.xml.Utility.{escape, trim}
import scala.xml.{Elem, Node, NodeSeq}

/** A client to create, get and update entities in Preservica
  *
  * @tparam F
  *   Type of the effect
  */
trait EntityClient[F[_], S] {

  /** Used to format dates.
    */
  val dateFormatter: DateTimeFormatter

  /** Returns metadata as an XML `Elem` for the provided entity
    *
    * @param entity
    *   The entity to return metadata for
    * @return
    *   An EntityMetadata object containing the metadata (Entity node, Identifiers and metadata) wrapped in the F effect
    */
  def metadataForEntity(entity: Entity): F[EntityMetadata]

  /** Returns a list of [[Client.BitStreamInfo]] representing the bitstreams for the content object reference
    *
    * @param contentRef
    *   The reference of the content object
    * @return
    *   A `Seq` of [[Client.BitStreamInfo]] containing the bitstream details
    */
  def getBitstreamInfo(contentRef: UUID): F[Seq[BitStreamInfo]]

  /** Returns an [[Entities.Entity]] for the given ref and type
    *
    * @param entityRef
    *   The reference of the entity
    * @param entityType
    *   The [[EntityClient.EntityType]] of the entity.
    * @return
    *   An [[Entities.Entity]] wrapped in the F effect
    */
  def getEntity(entityRef: UUID, entityType: EntityType): F[Entity]

  /** Returns a list of [[Entities.IdentifierResponse]] for a given entity
    *
    * @param entity
    *   The entity to find the identifiers for
    * @return
    *   A `Seq` of [[Entities.IdentifierResponse]] wrapped in the F effect
    */
  def getEntityIdentifiers(entity: Entity): F[Seq[IdentifierResponse]]

  /** Returns a String for the given ref and representationType
    *
    * @param ioEntityRef
    *   The reference of the Information Object
    * @param representationType
    *   The [[EntityClient.RepresentationType]] of the entity.
    * @return
    *   A String wrapped in the F effect
    */
  def getUrlsToIoRepresentations(
      ioEntityRef: UUID,
      representationType: Option[RepresentationType]
  ): F[Seq[String]]

  /** Returns a `Seq` of [[Entities.Entity]] for the given ref and representationType
    *
    * @param ioEntityRef
    *   The reference of the Information Object
    * @param representationType
    *   The [[EntityClient.RepresentationType]] of the entity.
    * @param repTypeIndex
    *   The index of the Representation.
    * @return
    *   A `Seq` of [[Entities.Entity]] wrapped in the F effect
    */
  def getContentObjectsFromRepresentation(
      ioEntityRef: UUID,
      representationType: RepresentationType,
      repTypeIndex: Int
  ): F[Seq[Entity]]

  /** Adds an entity to Preservica
    *
    * @param addEntityRequest
    *   An instance of [[EntityClient.AddEntityRequest]] with the details of the entity to add
    * @return
    *   The reference of the new entity wrapped in the F effect.
    */
  def addEntity(addEntityRequest: AddEntityRequest): F[UUID]

  /** Updates an entity in Preservice
    *
    * @param updateEntityRequest
    *   An instance of [[EntityClient.UpdateEntityRequest]] with the details of the entity to update
    * @return
    *   The string `"Entity was updated"`
    */
  def updateEntity(updateEntityRequest: UpdateEntityRequest): F[String]

  /** Updates identifiers for an entity
    *
    * @param entity
    *   The entity to update
    * @param identifiers
    *   A list of identifiers to update on the entity
    * @return
    *   The original identifiers argument.
    */
  def updateEntityIdentifiers(entity: Entity, identifiers: Seq[IdentifierResponse]): F[Seq[IdentifierResponse]]

  /** Streams the bitstream from the provided url into `streamFn`
    *
    * @param stream
    *   An instance of the sttp Stream type
    * @param url
    *   The url to stream the data from
    * @param streamFn
    *   The function to stream the data to
    * @tparam T
    *   The return type of the stream function, wrapped in the F effect
    * @return
    *   The return type of the stream function, wrapped in the F effect
    */
  def streamBitstreamContent[T](
      stream: Streams[S]
  )(url: String, streamFn: stream.BinaryStream => F[T]): F[T]

  /** Returns any entity updated since the provided dateTime
    *
    * @param sinceDateTime
    *   The date and time to pass to the API
    * @param startEntry
    *   The entry to start from. Used for pagination
    * @param maxEntries
    *   The maximum number of entries to return. Defaults to 1000
    * @param potentialEndDate
    *   If provided, no entities updated after this date will be returned
    * @return
    *   A `Seq` of [[Entities.Entity]] wrapped in the F effect
    */
  def entitiesUpdatedSince(
      sinceDateTime: ZonedDateTime,
      startEntry: Int,
      maxEntries: Int = 1000,
      potentialEndDate: Option[ZonedDateTime] = None
  ): F[Seq[Entity]]

  /** Returns a list of event actions for an entity
    *
    * @param entity
    *   The entity to return the actions for
    * @param startEntry
    *   The entry to start from. Used for pagination
    * @param maxEntries
    *   The maximum number of entries to return. Defaults to 1000
    * @return
    *   A `Seq` of [[DataProcessor.EventAction]]
    */
  def entityEventActions(
      entity: Entity,
      startEntry: Int = 0,
      maxEntries: Int = 1000
  ): F[Seq[EventAction]]

  /** Find entities per identifier
    *
    * @param identifiers
    *   A Seq of identifiers
    * @return
    *   A Map of Identifier -> Seq of Entities
    */
  def entitiesPerIdentifier(identifiers: Seq[Identifier]): F[Map[Identifier, Seq[Entity]]]

  /** Adds an identifier for an entity
    *
    * @param entityRef
    *   The reference of the entity
    * @param entityType
    *   The type of the entity
    * @param identifier
    *   The identifier to add to the entity
    * @return
    *   The string `"The Identifier was added"`
    */
  def addIdentifierForEntity(
      entityRef: UUID,
      entityType: EntityType,
      identifier: Identifier
  ): F[String]

  /** Gets the version of Preservica in the namespace
    *
    * @param endpoint
    *   The Entity endpoint to be called (this should exclude the baseUrl and path)
    * @return
    *   The version of Preservica in the namespace as a Float
    */

  def getPreservicaNamespaceVersion(endpoint: String): F[Float]

  /** Streams the refs of all StructuredObjects and InformationObjects in Preservica recursively, starting from the root
    * of the directory
    *
    * @param repTypeFilter
    *   An optional filter for the RepresentationType of the entities to be returned. If None, all entities will be
    *   returned.
    * @return
    *   a Stream of Entity refs
    */

  def streamAllEntityRefs(repTypeFilter: Option[RepresentationType] = None): fs2.Stream[F, EntityRef]
}

/** An object containing a method which returns an implementation of the EntityClient trait
  */
object EntityClient {

  /** The version of Preservica's Entity API that this client is using
    */
  val apiVersion: Float = 7.7f

  /** Creates a new `EntityClient` instance.
    *
    * @param clientConfig
    *   Configuration parameters needed to create the client
    * @tparam F
    *   The type of the effect
    * @tparam S
    *   The type of the Stream to be used for the streaming methods.
    * @return
    */
  def createEntityClient[F[_]: {Async, Parallel}, S](clientConfig: ClientConfig[F, S]): EntityClient[F, S] =
    new EntityClient[F, S] {
      val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
      private val apiBaseUrl: String = clientConfig.apiBaseUrl
      private val apiUrl = s"$apiBaseUrl/api/entity/v$apiVersion"
      private val namespaceUrl = s"http://preservica.com/XIP/v$apiVersion"

      private val missingPathExceptionMessage: UUID => String = ref =>
        s"No path found for entity id $ref. Could this entity have been deleted?"

      private val client: Client[F, S] = Client(clientConfig)

      import client.*

      override def getEntity(entityRef: UUID, entityType: EntityType): F[Entity] =
        for {
          entity <- getEntityWithRef(entityRef, entityType)
        } yield entity

      override def getUrlsToIoRepresentations(
          entityRef: UUID,
          representationType: Option[RepresentationType]
      ): F[Seq[String]] =
        for {
          urlsOfReps <- urlsToIoRepresentations(entityRef, representationType)
        } yield urlsOfReps

      override def getContentObjectsFromRepresentation(
          ioEntityRef: UUID,
          representationType: RepresentationType,
          repTypeIndex: Int
      ): F[Seq[Entity]] =
        for {
          representationsResponse <- ioRepresentations(ioEntityRef, representationType, repTypeIndex)
          contentObjects <- dataProcessor.getContentObjectsFromRepresentation(
            representationsResponse,
            ioEntityRef
          )
        } yield contentObjects

      override def getEntityIdentifiers(entity: Entity): F[Seq[IdentifierResponse]] = {
        for {
          path <- Async[F].fromOption(
            entity.path,
            PreservicaClientException(missingPathExceptionMessage(entity.ref))
          )
          url = uri"$apiUrl/$path/${entity.ref}/identifiers"
          identifiers <- entityIdentifiers(url.toString.some, Nil)
        } yield identifiers
      }

      override def addEntity(addEntityRequest: AddEntityRequest): F[UUID] = {
        val path = addEntityRequest.entityType.entityPath
        for {
          _ <-
            if (path == ContentObject.entityPath)
              Async[F].raiseError(
                PreservicaClientException("You currently cannot create a content object via the API.")
              )
            else Async[F].unit

          nodeName <- validateEntityUpdateInputs(
            addEntityRequest.entityType,
            addEntityRequest.parentRef
          )
          addXipTag = path == InformationObject.entityPath
          addRequestBody = createUpdateRequestBody(
            addEntityRequest.ref,
            addEntityRequest.title,
            addEntityRequest.description,
            addEntityRequest.parentRef,
            addEntityRequest.securityTag,
            nodeName,
            addXipTag
          )
          // "Representations" can be appended to an 'information-objects' request; for now, we'll exclude it and instead, just close the tag
          fullRequestBody = if (addXipTag) addRequestBody + "\n            </XIP>" else addRequestBody
          url = uri"$apiUrl/$path"
          addEntityResponse <- sendXMLApiRequest(url.toString, Method.POST, Some(fullRequestBody))
          ref <- dataProcessor.childNodeFromEntity(addEntityResponse, nodeName, "Ref")
        } yield UUID.fromString(ref.trim)
      }

      override def updateEntity(updateEntityRequest: UpdateEntityRequest): F[String] = {
        for {
          nodeName <- validateEntityUpdateInputs(
            updateEntityRequest.entityType,
            updateEntityRequest.parentRef
          )

          updateRequestBody = createUpdateRequestBody(
            Some(updateEntityRequest.ref),
            updateEntityRequest.title,
            updateEntityRequest.descriptionToChange,
            updateEntityRequest.parentRef,
            updateEntityRequest.securityTag,
            nodeName
          )
          path = updateEntityRequest.entityType.entityPath
          url = uri"$apiUrl/$path/${updateEntityRequest.ref}"
          _ <- sendXMLApiRequest(url.toString, Method.PUT, Some(updateRequestBody))
          response = "Entity was updated"
        } yield response
      }

      override def getBitstreamInfo(
          contentObjectRef: UUID
      ): F[Seq[BitStreamInfo]] =
        for {
          contentObjectElement <- sendXMLApiRequest(
            s"$apiUrl/${ContentObject.entityPath}/$contentObjectRef",
            Method.GET
          )

          generationsEndpointUrl <- dataProcessor.generationUrlFromEntity(contentObjectElement)
          allGenerationElements <- generationElements(generationsEndpointUrl, contentObjectRef)
          allBitstreamInfo <- allGenerationElements.map { generationElement =>
            for {
              generationType <- dataProcessor.generationType(generationElement, contentObjectRef)
              bitstreamElements <- bitstreamElements(generationElement)
              contentObject <- dataProcessor.getEntity(contentObjectRef, contentObjectElement, ContentObject)
              allBitstreamInfo <- dataProcessor.allBitstreamInfo(bitstreamElements, generationType, contentObject)
            } yield allBitstreamInfo
          }.flatSequence

        } yield allBitstreamInfo

      override def metadataForEntity(entity: Entity): F[EntityMetadata] = {
        val queryParams = Map("max" -> 1000, "start" -> 0)
        for {
          path <- Async[F].fromOption(
            entity.path,
            PreservicaClientException(missingPathExceptionMessage(entity.ref))
          )
          entityUrl = s"$apiUrl/$path/${entity.ref}"
          entityType <- getEntityType(entity)

          entityInfo <- sendXMLApiRequest(entityUrl, Method.GET)
          entityNode <- dataProcessor.getEntityXml(entity.ref, entityInfo, entityType)

          identifiers <- entityIdentifiersXml(Some(s"$entityUrl/identifiers"), Nil)

          entityLinks <- entityLinksXml(entity.ref, uri"$entityUrl/links?$queryParams".toString.some, Nil)

          fragmentUrls <- dataProcessor.fragmentUrls(entityInfo)
          fragmentResponses <- fragmentUrls.map(url => sendXMLApiRequest(url, Method.GET)).sequence
          fragments <- dataProcessor.fragments(fragmentResponses)
          fragmentsWithMetadataLabel = fragments.map { node =>
            new Elem(node.prefix, "Metadata", node.attributes, node.scope, false, node.child*)
          }

          eventActionResponseXmls <- eventActionsXml(
            uri"$entityUrl/event-actions?$queryParams".toString.some,
            Nil
          )
          eventActions <- eventActionResponseXmls.map(dataProcessor.getEventActionElements).flatSequence
          entityMetadata <-
            if (entityType.entityTypeShort == "CO")
              for {
                generationsEndpointUrl <- Async[F].pure(s"$entityUrl/generations")
                allGenerationsResponseElements <- generationElements(generationsEndpointUrl, entity.ref)
                allGenerationElements <- allGenerationsResponseElements
                  .map(dataProcessor.getGenerationElement)
                  .flatSequence
                bitstreamElements <- allGenerationsResponseElements.map(bitstreamElements).flatSequence
              } yield CoMetadata(
                entityNode,
                allGenerationElements,
                bitstreamElements.flatMap(_ \ "Bitstream"),
                identifiers,
                entityLinks,
                fragmentsWithMetadataLabel,
                eventActions
              )
            else if (entityType.entityTypeShort == "IO")
              for {
                urlsToIoReps <- urlsToIoRepresentations(entity.ref, None)
                representations <- urlsToIoReps.map { urlToIoRepresentation =>
                  val urlSplitOnForwardSlash = urlToIoRepresentation.split('/').reverse
                  val generationVersion = urlSplitOnForwardSlash.head.toInt
                  val representationType = RepresentationType.valueOf(urlSplitOnForwardSlash(1))

                  ioRepresentations(entity.ref, representationType, generationVersion).flatMap {
                    dataProcessor.getRepresentationElement
                  }
                }.flatSequence
              } yield IoMetadata(
                entityNode,
                representations,
                identifiers,
                entityLinks,
                fragmentsWithMetadataLabel,
                eventActions
              )
            else
              Async[F].pure(
                StandardEntityMetadata(entityNode, identifiers, entityLinks, fragmentsWithMetadataLabel, eventActions)
              )
        } yield entityMetadata
      }

      override def entitiesUpdatedSince(
          sinceDateTime: ZonedDateTime,
          startEntry: Int,
          maxEntries: Int = 1000,
          potentialEndDate: Option[ZonedDateTime] = None
      ): F[Seq[Entity]] = {
        val dateString = sinceDateTime.format(dateFormatter)
        val endDateParams =
          potentialEndDate.map(endDate => Map("endDate" -> endDate.format(dateFormatter))).getOrElse(Map())
        val queryParams = Map("date" -> dateString, "max" -> maxEntries, "start" -> startEntry) ++ endDateParams
        val url = uri"$apiUrl/entities/updated-since?$queryParams"
        for {
          updatedEntities <- getEntities(url.toString)
        } yield updatedEntities
      }

      override def entityEventActions(
          entity: Entity,
          startEntry: Int = 0,
          maxEntries: Int = 1000
      ): F[Seq[EventAction]] = {
        val queryParams = Map("max" -> maxEntries, "start" -> startEntry)
        for {
          path <- Async[F].fromOption(
            entity.path,
            PreservicaClientException(missingPathExceptionMessage(entity.ref))
          )
          url = uri"$apiUrl/$path/${entity.ref}/event-actions?$queryParams"
          allEventActionsResponseXml <- eventActionsXml(url.toString.some, Nil)
          eventActions <- allEventActionsResponseXml
            .map(eventActionsResponseXml => dataProcessor.getEventActions(eventActionsResponseXml))
            .flatSequence
        } yield eventActions.reverse // most recent event first
      }

      override def entitiesPerIdentifier(identifiers: Seq[Identifier]): F[Map[Identifier, Seq[Entity]]] =
        for {
          entities <- identifiers.distinct.parTraverse(identifier => entitiesForIdentifier(identifier))
        } yield entities.toMap

      override def addIdentifierForEntity(
          entityRef: UUID,
          entityType: EntityType,
          identifier: Identifier
      ): F[String] =
        for {
          _ <- sendXMLApiRequest(
            s"$apiUrl/${entityType.entityPath}/$entityRef/identifiers",
            Method.POST,
            Some(requestBodyForIdentifier(identifier.identifierName, identifier.value))
          )
          response = s"The Identifier was added"
        } yield response

      def streamBitstreamContent[T](
          stream: Streams[S]
      )(url: String, streamFn: stream.BinaryStream => F[T]): F[T] = {
        val apiUri = uri"$url"

        def request(token: String) = basicRequest
          .get(apiUri)
          .headers(Map("Preservica-Access-Token" -> token))
          .response(asStream(stream)(streamFn))

        for {
          token <- getAuthenticationToken
          res <- backend.send(request(token))
          body <- Async[F].fromEither {
            res.body.left.map(err => PreservicaClientException(Method.GET, apiUri, res.code, err))
          }
        } yield body
      }

      override def updateEntityIdentifiers(
          entity: Entity,
          identifiers: Seq[IdentifierResponse]
      ): F[Seq[IdentifierResponse]] =
        for {
          updateResponse <- identifiers.map { identifier =>
            val requestBody = requestBodyForIdentifier(identifier.identifierName, identifier.value).some
            for {
              path <- Async[F].fromOption(
                entity.path,
                PreservicaClientException(missingPathExceptionMessage(entity.ref))
              )
              url = uri"$apiUrl/$path/${entity.ref}/identifiers/${identifier.id}"
              _ <- sendXMLApiRequest(url.toString, Method.PUT, requestBody)
            } yield identifier
          }.sequence
        } yield updateResponse

      override def getPreservicaNamespaceVersion(endpoint: String): F[Float] = {
        for {
          resXml <- sendXMLApiRequest(s"$apiBaseUrl/api/entity/$endpoint", Method.GET)
          version <- dataProcessor.getPreservicaNamespaceVersion(resXml)
        } yield version
      }

      private def entitiesForIdentifier(
          identifier: Identifier
      ): F[(Identifier, Seq[Entity])] = {
        val queryParams = Map("type" -> identifier.identifierName, "value" -> identifier.value)
        val url = uri"$apiUrl/entities/by-identifier?$queryParams"
        for {
          entitiesWithIdentifier <- getEntities(url.toString)
          entities <- entitiesWithIdentifier.map { entityWithId =>
            for {
              entityType <- getEntityType(entityWithId)
              entity <- getEntityWithRef(
                entityWithId.ref,
                entityType
              ) // This is necessary to get the full entity information as by-identifier doesn't return it
            } yield entity
          }.sequence
        } yield identifier -> entities
      }

      private def getEntityWithRef(entityRef: UUID, entityType: EntityType) =
        for {
          url <- Async[F].pure(uri"$apiUrl/${entityType.entityPath}/$entityRef")
          entityResponse <- sendXMLApiRequest(url.toString(), Method.GET)
          entity <- dataProcessor.getEntity(entityRef, entityResponse, entityType)
        } yield entity

      private def urlsToIoRepresentations(
          entityRef: UUID,
          representationType: Option[RepresentationType]
      ) =
        for {
          url <- Async[F].pure(uri"$apiUrl/information-objects/$entityRef/representations")
          representationsResponse <- sendXMLApiRequest(url.toString(), Method.GET)
          urlsOfRepresentations <- dataProcessor.getUrlsToEntityRepresentations(
            representationsResponse,
            representationType
          )
        } yield urlsOfRepresentations

      private def getEntityType(entity: Entity): F[EntityType] =
        Async[F].fromOption(
          entity.entityType,
          PreservicaClientException(s"No entity type found for entity ${entity.ref}")
        )

      private def getEntities(url: String): F[Seq[Entity]] =
        for {
          entitiesResponseXml <- sendXMLApiRequest(url, Method.GET)
          entitiesWithUpdates <- dataProcessor.getEntities(entitiesResponseXml)
        } yield entitiesWithUpdates

      private def eventActionsXml(
          url: Option[String],
          currentCollectionOfEventActionsXml: Seq[Elem]
      ): F[Seq[Elem]] = {
        if (url.isEmpty) {
          Async[F].pure(currentCollectionOfEventActionsXml)
        } else {
          for {
            eventActionsResponseXml <- sendXMLApiRequest(url.get, Method.GET)
            nextPageUrl <- dataProcessor.nextPage(eventActionsResponseXml)
            allEventActionsXml <- eventActionsXml(
              nextPageUrl,
              currentCollectionOfEventActionsXml :+ eventActionsResponseXml
            )
          } yield allEventActionsXml
        }
      }

      private def children(
          url: Option[String],
          currentEntityRefs: Seq[EntityRef],
          potentialParentRef: Option[UUID]
      ): F[Seq[EntityRef]] = {
        if url.isEmpty then Async[F].pure(currentEntityRefs)
        else
          for {
            response <- sendXMLApiRequest(url.get, Method.GET)
            childEntityRefs <- dataProcessor.getChildren(response, potentialParentRef)
            nextPageUrl <- dataProcessor.nextPage(response)
            allEntities <- children(nextPageUrl, currentEntityRefs ++ childEntityRefs, potentialParentRef)
          } yield allEntities
      }

      private def requestBodyForIdentifier(identifierName: String, identifierValue: String): String = {
        val identifierAsXml =
          <Identifier xmlns={namespaceUrl}>
          <Type>
            {identifierName}
          </Type>
          <Value>
            {identifierValue}
          </Value>
        </Identifier>

        s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n${trim(identifierAsXml)}"""
      }

      private def ioRepresentations(
          ioEntityRef: UUID,
          representationType: RepresentationType,
          repTypeIndex: Int
      ): F[Elem] =
        for {
          url <- Async[F].pure(
            uri"$apiUrl/information-objects/$ioEntityRef/representations/$representationType/$repTypeIndex"
          )
          representationsResponse <- sendXMLApiRequest(url.toString(), Method.GET)
        } yield representationsResponse

      private def entityIdentifiers(
          url: Option[String],
          currentCollectionOfIdentifiers: Seq[IdentifierResponse]
      ): F[Seq[IdentifierResponse]] = {
        if (url.isEmpty) {
          Async[F].pure(currentCollectionOfIdentifiers)
        } else {
          for {
            identifiersResponseXml <- sendXMLApiRequest(url.get, Method.GET)
            identifiersBatch <- dataProcessor.getIdentifiers(identifiersResponseXml)
            nextPageUrl <- dataProcessor.nextPage(identifiersResponseXml)
            allIdentifiers <- entityIdentifiers(
              nextPageUrl,
              currentCollectionOfIdentifiers ++ identifiersBatch
            )
          } yield allIdentifiers
        }
      }

      private def validateEntityUpdateInputs(entityType: EntityType, parentRef: Option[UUID]): F[String] =
        for {
          _ <-
            if (entityType.entityPath != StructuralObject.entityPath && parentRef.isEmpty)
              Async[F].raiseError(
                PreservicaClientException(
                  "You must pass in the parent ref if you would like to add/update a non-structural object."
                )
              )
            else Async[F].unit
        } yield entityType.toString

      private def createUpdateRequestBody(
          ref: Option[UUID],
          title: String,
          descriptionToChange: Option[String],
          parentRef: Option[UUID],
          securityTag: SecurityTag,
          nodeName: String,
          addOpeningXipTag: Boolean = false
      ): String = {
        s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            ${if (addOpeningXipTag) s"""<XIP xmlns="http://preservica.com/XIP/v$apiVersion">""" else ""}
            <$nodeName xmlns="http://preservica.com/XIP/v$apiVersion">
              ${ref.map(r => s"<Ref>$r</Ref>").getOrElse("")}
              <Title>${escape(title)}</Title>
              ${descriptionToChange
            .map(description => s"<Description>${escape(description)}</Description>")
            .getOrElse("")}
              <SecurityTag>$securityTag</SecurityTag>
              ${parentRef.map(parent => s"<Parent>$parent</Parent>").getOrElse("")}
            </$nodeName>"""
      }

      private def generationElements(
          generationsEndpointUrl: String,
          contentObjectRef: UUID
      ): F[Seq[Elem]] =
        for {
          generationsElement <- sendXMLApiRequest(generationsEndpointUrl, Method.GET)
          allGenerationUrls <- dataProcessor.allGenerationUrls(generationsElement, contentObjectRef)

          allGenerationElements <- allGenerationUrls.map { url =>
            sendXMLApiRequest(url, Method.GET)
          }.sequence
        } yield allGenerationElements

      private def bitstreamElements(generationResponseElement: Elem) =
        for {
          allBitstreamUrls <- dataProcessor.allBitstreamUrls(generationResponseElement)
          bitstreamElements <- allBitstreamUrls.map { url =>
            sendXMLApiRequest(url, Method.GET)
          }.sequence
        } yield bitstreamElements

      private def entityIdentifiersXml(
          url: Option[String],
          currentCollectionOfIdentifiers: Seq[Node]
      ): F[Seq[Node]] =
        if (url.isEmpty)
          Async[F].pure(currentCollectionOfIdentifiers)
        else
          for {
            identifiersResponseXml <- sendXMLApiRequest(url.get, Method.GET)
            identifiersBatch <- dataProcessor.getIdentifiersXml(identifiersResponseXml)
            nextPageUrl <- dataProcessor.nextPage(identifiersResponseXml)
            allIdentifiers <- entityIdentifiersXml(
              nextPageUrl,
              currentCollectionOfIdentifiers ++ identifiersBatch
            )
          } yield allIdentifiers

      private def entityLinksXml(
          ref: UUID,
          url: Option[String],
          currentCollectionOfEntityLinks: Seq[Node]
      ): F[Seq[Node]] =
        if (url.isEmpty) Async[F].pure(currentCollectionOfEntityLinks)
        else
          for {
            entityLinksResponseXml <- sendXMLApiRequest(url.get, Method.GET)
            entityLinksXmlBatch <- dataProcessor.getEntityLinksXml(ref, entityLinksResponseXml)
            nextPageUrl <- dataProcessor.nextPage(entityLinksResponseXml)
            allEntityLinksXml <- entityLinksXml(
              ref,
              nextPageUrl,
              currentCollectionOfEntityLinks ++ entityLinksXmlBatch
            )
          } yield allEntityLinksXml

      private def contentObjectsForInformationObject(ioRef: UUID, potentialRepType: Option[RepresentationType]) =
        for {
          representationUrls <- getUrlsToIoRepresentations(ioRef, potentialRepType)
          representationElems <- representationUrls.parTraverse(url => sendXMLApiRequest(url, Method.GET))
          entities <- representationElems
            .parTraverse(elem => dataProcessor.getContentObjectsFromRepresentation(elem, ioRef))
            .map(_.flatten)
        } yield entities.map(entity => ContentObjectRef(entity.ref, ioRef))

      override def streamAllEntityRefs(repTypeFilter: Option[RepresentationType] = None): fs2.Stream[F, EntityRef] = {
        val queryParams = Map("max" -> 1000, "start" -> 0)
        val topLevelEntityRefs = children(Some(uri"$apiUrl/root/children?$queryParams".toString), Nil, None)

        def getChildrenRefs(rootEntityRefs: Seq[EntityRef]): fs2.Stream[F, EntityRef] =
          fs2.Stream.unfoldLoopEval(rootEntityRefs) {
            case Nil => Async[F].pure(NoEntityRef -> None)
            case firstEntityRef :: restOfRefs =>
              for {
                nextPageOfRefs <- firstEntityRef match {
                  case StructuralObjectRef(ref, _) =>
                    children(Some(uri"$apiUrl/structural-objects/$ref/children?$queryParams".toString), Nil, Some(ref))
                  case InformationObjectRef(ref, _) => contentObjectsForInformationObject(ref, repTypeFilter)
                  case _                            => Async[F].pure(Nil)
                }
              } yield firstEntityRef -> Option(restOfRefs ++ nextPageOfRefs)
          }
        fs2.Stream.eval(topLevelEntityRefs).flatMap(getChildrenRefs).filterNot(_ == NoEntityRef)
      }
    }

  sealed trait EntityMetadata:
    val entityNode: Node
    val identifiers: Seq[Node]
    val links: Seq[Node]
    val metadataNodes: Seq[Node]
    val eventActions: Seq[Node]

  /** Represents a Preservica security tag
    */
  enum SecurityTag:
    override def toString: String = this match
      case Open => "open"
      case Closed => "closed"
      case Unknown   => "unknown"

    case Open, Closed, Unknown

  /** Represents an entity type
    */
  enum EntityType(val entityPath: String, val entityTypeShort: String):
    case StructuralObject extends EntityType("structural-objects", "SO")
    case InformationObject extends EntityType("information-objects", "IO")
    case ContentObject extends EntityType("content-objects", "CO")

  /** Represents a Preservica identifier
    */
  case class Identifier(identifierName: String, value: String)

  /** Represents a Preservica representation tag
    */

  enum RepresentationType:
    case Access, Preservation

  /** Represents an entity to add to Preservica
    *
    * @param ref
    *   An optional ref. If one is not provided, one will be generated
    * @param title
    *   The title of the new entity
    * @param description
    *   The optional description of the new entity
    * @param entityType
    *   The type of the new entity
    * @param securityTag
    *   The security tag of the new entity
    * @param parentRef
    *   An optional parent reference
    */
  case class AddEntityRequest(
      ref: Option[UUID],
      title: String,
      description: Option[String],
      entityType: EntityType,
      securityTag: SecurityTag,
      parentRef: Option[UUID]
  )

  /** Represents an entity to update in Preservica
    *
    * @param ref
    *   The ref of the entity to be updated
    * @param title
    *   The title of the updated entity
    * @param descriptionToChange
    *   The optional description of the updated entity
    * @param entityType
    *   The type of the updated entity
    * @param securityTag
    *   The security tag of the updated entity
    * @param parentRef
    *   An optional parent reference
    */
  case class UpdateEntityRequest(
      ref: UUID,
      title: String,
      descriptionToChange: Option[String],
      entityType: EntityType,
      securityTag: SecurityTag,
      parentRef: Option[UUID]
  )

  enum GenerationType:
    case Original, Derived

  /* The non-specific (generic) metadata that is common for all Entity types; default for current/future Entities
  that don't have an EntityMetadata implementation. */
  case class StandardEntityMetadata(
      entityNode: Node,
      identifiers: Seq[Node],
      links: Seq[Node],
      metadataNodes: Seq[Node],
      eventActions: Seq[Node]
  ) extends EntityMetadata

  case class IoMetadata(
      entityNode: Node,
      representations: Seq[Node],
      identifiers: Seq[Node],
      links: Seq[Node],
      metadataNodes: Seq[Node],
      eventActions: Seq[Node]
  ) extends EntityMetadata

  case class CoMetadata(
      entityNode: Node,
      generationNodes: Seq[Node],
      bitstreamNodes: Seq[Node],
      identifiers: Seq[Node],
      links: Seq[Node],
      metadataNodes: Seq[Node],
      eventActions: Seq[Node]
  ) extends EntityMetadata

  object SecurityTag:
    def fromString(securityTagString: String): Option[SecurityTag] = Try(
      SecurityTag.valueOf(securityTagString.capitalize)
    ).toOption
}
