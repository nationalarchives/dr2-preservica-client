package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import sttp.capabilities.Streams
import sttp.client3._
import sttp.model.Method
import uk.gov.nationalarchives.DynamoFormatters.Identifier
import uk.gov.nationalarchives.dp.client.Client._
import uk.gov.nationalarchives.dp.client.DataProcessor.EventAction
import uk.gov.nationalarchives.dp.client.Entities.{Entity, IdentifierResponse}
import uk.gov.nationalarchives.dp.client.EntityClient.{
  AddEntityRequest,
  EntityType,
  RepresentationType,
  UpdateEntityRequest
}

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.xml.Utility.escape
import scala.xml.{Elem, PrettyPrinter, XML}

/** A client to create, get and update entities in Preservica
  * @tparam F
  *   Type of the effect
  */
trait EntityClient[F[_], S] {

  /** Used to format dates.
    */
  val dateFormatter: DateTimeFormatter

  /** Returns metadata as an XML `Elem` for the provided entity
    * @param entity
    *   The entity to return metadata for
    * @return
    *   A `Seq` of `Elem` containing the metadata wrapped in the F effect
    */
  def metadataForEntity(entity: Entity): F[Seq[Elem]]

  /** Returns a list of [[Client.BitStreamInfo]] representing the bitstreams for the content object reference
    * @param contentRef
    *   The reference of the content object
    * @return
    *   A `Seq` of [[Client.BitStreamInfo]] containing the bitstream details
    */
  def getBitstreamInfo(contentRef: UUID): F[Seq[BitStreamInfo]]

  /** Returns an [[Entities.Entity]] for the given ref and type
    * @param entityRef
    *   The reference of the entity
    * @param entityType
    *   The [[EntityClient.EntityType]] of the entity.
    * @return
    *   An [[Entities.Entity]] wrapped in the F effect
    */
  def getEntity(entityRef: UUID, entityType: EntityType): F[Entity]

  /** Returns a list of [[Entities.IdentifierResponse]] for a given entity
    * @param entity
    *   The entity to find the identifiers for
    * @return
    *   A `Seq` of [[Entities.IdentifierResponse]] wrapped in the F effect
    */
  def getEntityIdentifiers(entity: Entity): F[Seq[IdentifierResponse]]

  /** Returns a String for the given ref and representationType
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
    * @param ioEntityRef
    *   The reference of the Information Object
    * @param representationType
    *   The [[EntityClient.RepresentationType]] of the entity.
    * @param version
    *   The version of the Representation.
    * @return
    *   A `Seq` of [[Entities.Entity]] wrapped in the F effect
    */
  def getContentObjectsFromRepresentation(
      ioEntityRef: UUID,
      representationType: RepresentationType,
      version: Int
  ): F[Seq[Entity]]

  /** Adds an entity to Preservica
    * @param addEntityRequest
    *   An instance of [[EntityClient.AddEntityRequest]] with the details of the entity to add
    * @return
    *   The reference of the new entity wrapped in the F effect.
    */
  def addEntity(addEntityRequest: AddEntityRequest): F[UUID]

  /** Updates an entity in Preservice
    * @param updateEntityRequest
    *   An instance of [[EntityClient.UpdateEntityRequest]] with the details of the entity to update
    * @return
    *   The string `"Entity was updated"`
    */
  def updateEntity(updateEntityRequest: UpdateEntityRequest): F[String]

  /** Updates identifiers for an entity
    * @param entity
    *   The entity to update
    * @param identifiers
    *   A list of identifiers to update on the entity
    * @return
    *   The original identifiers argument.
    */
  def updateEntityIdentifiers(entity: Entity, identifiers: Seq[IdentifierResponse]): F[Seq[IdentifierResponse]]

  /** Streams the bitstream from the provided url into `streamFn`
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
    * @param dateTime
    *   The date and time to pass to the API
    * @param startEntry
    *   The entry to start from. Used for pagination
    * @param maxEntries
    *   The maximum number of entries to return. Defaults to 1000
    * @return
    *   A `Seq` of [[Entities.Entity]] wrapped in the F effect
    */
  def entitiesUpdatedSince(
      dateTime: ZonedDateTime,
      startEntry: Int,
      maxEntries: Int = 1000
  ): F[Seq[Entity]]

  /** Returns a list of event actions for an entity
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

  /** Find entities for an identifier
    * @param identifier
    *   The identifier to use to find the entities
    * @return
    *   A `Seq` of [[Entities.Entity]]
    */
  def entitiesByIdentifier(
      identifier: Identifier
  ): F[Seq[Entity]]

  /** Adds an identifier for an entity
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
    * @param endpoint
    *   The Entity endpoint to be called (this should exclude the baseUrl and path)
    * @return
    *   The version of Preservica in the namespace as a Float
    */

  def getPreservicaNamespaceVersion(endpoint: String): F[Float]
}

/** An object containing a method which returns an implementation of the EntityClient trait
  */
object EntityClient {

  /** Creates a new `EntityClient` instance.
    * @param clientConfig
    *   Configuration parameters needed to create the client
    * @param me
    *   An implicit instance of cats.MonadError
    * @param sync
    *   An implicit instance of cats.Sync
    * @tparam F
    *   The type of the effect
    * @tparam S
    *   The type of the Stream to be used for the streaming methods.
    * @return
    */
  def createEntityClient[F[_], S](clientConfig: ClientConfig[F, S])(implicit
      me: MonadError[F, Throwable],
      sync: Sync[F]
  ): EntityClient[F, S] = new EntityClient[F, S] {
    val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private val apiBaseUrl: String = clientConfig.apiBaseUrl
    private val apiVersion = 7.0f
    private val apiUrl = s"$apiBaseUrl/api/entity/v$apiVersion"
    private val namespaceUrl = s"http://preservica.com/XIP/v$apiVersion"

    private val missingPathExceptionMessage: UUID => String = ref =>
      s"No path found for entity id $ref. Could this entity have been deleted?"

    private val client: Client[F, S] = Client(clientConfig)

    import client._

    private def getEntities(
        url: String,
        token: String
    ): F[Seq[Entity]] =
      for {
        entitiesResponseXml <- sendXMLApiRequest(url, token, Method.GET)
        entitiesWithUpdates <- dataProcessor.getEntities(entitiesResponseXml)
      } yield entitiesWithUpdates

    private def eventActions(
        url: Option[String],
        token: String,
        currentCollectionOfEventActions: Seq[EventAction]
    ): F[Seq[EventAction]] = {
      if (url.isEmpty) {
        me.pure(currentCollectionOfEventActions)
      } else {
        for {
          eventActionsResponseXml <- sendXMLApiRequest(url.get, token, Method.GET)
          eventActionsBatch <- dataProcessor.getEventActions(eventActionsResponseXml)
          nextPageUrl <- dataProcessor.nextPage(eventActionsResponseXml)
          allEventActions <- eventActions(
            nextPageUrl,
            token,
            currentCollectionOfEventActions ++ eventActionsBatch
          )
        } yield allEventActions
      }
    }

    private def requestBodyForIdentifier(identifierName: String, identifierValue: String): String = {
      val identifierAsXml: String = {
        val xml = <Identifier xmlns={namespaceUrl}>
          <Type>{identifierName}</Type>
          <Value>{identifierValue}</Value>
        </Identifier>
        new PrettyPrinter(80, 2).format(xml)
      }
      s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n$identifierAsXml"""
    }

    override def getEntity(entityRef: UUID, entityType: EntityType): F[Entity] = {
      val url = uri"$apiUrl/${entityType.entityPath}/$entityRef"
      for {
        token <- getAuthenticationToken
        entityResponse <- sendXMLApiRequest(url.toString(), token, Method.GET)
        entity <- dataProcessor.getEntity(entityRef, entityResponse, entityType)
      } yield entity
    }

    override def getUrlsToIoRepresentations(
        entityRef: UUID,
        representationType: Option[RepresentationType]
    ): F[Seq[String]] =
      for {
        token <- getAuthenticationToken
        url = uri"$apiUrl/information-objects/$entityRef/representations"
        representationsResponse <- sendXMLApiRequest(url.toString(), token, Method.GET)
        urlsOfRepresentations <- dataProcessor.getUrlsToEntityRepresentations(
          representationsResponse,
          representationType
        )
      } yield urlsOfRepresentations

    override def getContentObjectsFromRepresentation(
        ioEntityRef: UUID,
        representationType: RepresentationType,
        version: Int
    ): F[Seq[Entity]] =
      for {
        token <- getAuthenticationToken
        url =
          uri"$apiUrl/information-objects/$ioEntityRef/representations/$representationType/$version"
        representationsResponse <- sendXMLApiRequest(url.toString(), token, Method.GET)
        contentObjects <- dataProcessor.getContentObjectsFromRepresentation(
          representationsResponse,
          representationType,
          ioEntityRef
        )
      } yield contentObjects

    override def getEntityIdentifiers(entity: Entity): F[Seq[IdentifierResponse]] = {
      for {
        path <- me.fromOption(
          entity.path,
          PreservicaClientException(missingPathExceptionMessage(entity.ref))
        )
        url = uri"$apiUrl/$path/${entity.ref}/identifiers"
        token <- getAuthenticationToken
        identifiers <- entityIdentifiers(url.toString.some, token, Nil)
      } yield identifiers
    }

    private def entityIdentifiers(
        url: Option[String],
        token: String,
        currentCollectionOfIdentifiers: Seq[IdentifierResponse]
    ): F[Seq[IdentifierResponse]] = {
      if (url.isEmpty) {
        me.pure(currentCollectionOfIdentifiers)
      } else {
        for {
          identifiersResponseXml <- sendXMLApiRequest(url.get, token, Method.GET)
          identifiersBatch <- dataProcessor.getIdentifiers(identifiersResponseXml)
          nextPageUrl <- dataProcessor.nextPage(identifiersResponseXml)
          allIdentifiers <- entityIdentifiers(
            nextPageUrl,
            token,
            currentCollectionOfIdentifiers ++ identifiersBatch
          )
        } yield allIdentifiers
      }
    }

    private def validateEntityUpdateInputs(
        entityType: EntityType,
        parentRef: Option[UUID]
    ): F[(String, String)] =
      for {
        _ <-
          if (entityType.entityPath != StructuralObject.entityPath && parentRef.isEmpty)
            me.raiseError(
              PreservicaClientException(
                "You must pass in the parent ref if you would like to add/update a non-structural object."
              )
            )
          else me.unit
        token <- getAuthenticationToken
      } yield (entityType.toString, token)

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

    override def addEntity(addEntityRequest: AddEntityRequest): F[UUID] = {
      val path = addEntityRequest.entityType.entityPath
      for {
        _ <-
          if (path == ContentObject.entityPath)
            me.raiseError(
              PreservicaClientException("You currently cannot create a content object via the API.")
            )
          else me.unit

        nodeNameAndToken <- validateEntityUpdateInputs(
          addEntityRequest.entityType,
          addEntityRequest.parentRef
        )
        (nodeName, token) = nodeNameAndToken
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
        addEntityResponse <- sendXMLApiRequest(url.toString, token, Method.POST, Some(fullRequestBody))
        ref <- dataProcessor.childNodeFromEntity(addEntityResponse, nodeName, "Ref")
      } yield UUID.fromString(ref.trim)
    }

    override def updateEntity(updateEntityRequest: UpdateEntityRequest): F[String] = {
      for {
        nodeNameAndToken <- validateEntityUpdateInputs(
          updateEntityRequest.entityType,
          updateEntityRequest.parentRef
        )

        (nodeName, token) = nodeNameAndToken
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
        _ <- sendXMLApiRequest(url.toString, token, Method.PUT, Some(updateRequestBody))
        response = "Entity was updated"
      } yield response
    }

    override def getBitstreamInfo(
        contentObjectRef: UUID
    ): F[Seq[BitStreamInfo]] =
      for {
        token <- getAuthenticationToken
        contentObjectElement <- sendXMLApiRequest(
          s"$apiUrl/${ContentObject.entityPath}/$contentObjectRef",
          token,
          Method.GET
        )
        generationsEndpointUrl <- dataProcessor.generationUrlFromEntity(contentObjectElement)
        generationsElement <- sendXMLApiRequest(generationsEndpointUrl, token, Method.GET)
        allGenerationUrls <- dataProcessor.allGenerationUrls(generationsElement, contentObjectRef)
        allGenerationElements <- allGenerationUrls
          .map(url => sendXMLApiRequest(url, token, Method.GET))
          .sequence
        allBitstreamInfo <- allGenerationElements.map { generationElement =>
          for {
            generationType <- dataProcessor.generationType(generationElement, contentObjectRef)
            allBitstreamUrls <- dataProcessor.allBitstreamUrls(generationElement)
            bitstreamElements <- allBitstreamUrls.map(url => sendXMLApiRequest(url, token, Method.GET)).sequence
            contentObject <- dataProcessor.getEntity(contentObjectRef, contentObjectElement, ContentObject)
            allBitstreamInfo <- dataProcessor.allBitstreamInfo(bitstreamElements, generationType, contentObject.title)
          } yield allBitstreamInfo
        }.flatSequence

      } yield allBitstreamInfo

    override def metadataForEntity(entity: Entity): F[Seq[Elem]] =
      for {
        token <- getAuthenticationToken

        path <- me.fromOption(
          entity.path,
          PreservicaClientException(missingPathExceptionMessage(entity.ref))
        )
        entityInfo <- sendXMLApiRequest(
          s"$apiUrl/$path/${entity.ref}",
          token,
          Method.GET
        )
        fragmentUrls <- dataProcessor.fragmentUrls(entityInfo)
        fragmentResponse <- fragmentUrls.map(url => sendXMLApiRequest(url, token, Method.GET)).sequence
        fragments <- dataProcessor.fragments(fragmentResponse)
      } yield fragments.map(XML.loadString)

    override def entitiesUpdatedSince(
        dateTime: ZonedDateTime,
        startEntry: Int,
        maxEntries: Int = 1000
    ): F[Seq[Entity]] = {
      val dateString = dateTime.format(dateFormatter)
      val queryParams = Map("date" -> dateString, "max" -> maxEntries, "start" -> startEntry)
      val url = uri"$apiUrl/entities/updated-since?$queryParams"
      for {
        token <- getAuthenticationToken
        updatedEntities <- getEntities(url.toString, token)
      } yield updatedEntities
    }

    override def entityEventActions(
        entity: Entity,
        startEntry: Int = 0,
        maxEntries: Int = 1000
    ): F[Seq[EventAction]] = {
      val queryParams = Map("max" -> maxEntries, "start" -> startEntry)
      for {
        path <- me.fromOption(
          entity.path,
          PreservicaClientException(missingPathExceptionMessage(entity.ref))
        )
        url = uri"$apiUrl/$path/${entity.ref}/event-actions?$queryParams"
        token <- getAuthenticationToken
        eventActions <- eventActions(url.toString.some, token, Nil)
      } yield eventActions.reverse // most recent event first
    }

    override def entitiesByIdentifier(
        identifier: Identifier
    ): F[Seq[Entity]] = {
      val queryParams = Map("type" -> identifier.identifierName, "value" -> identifier.value)
      val url = uri"$apiUrl/entities/by-identifier?$queryParams"
      for {
        token <- getAuthenticationToken
        entitiesWithIdentifier <- getEntities(url.toString, token)
        entities <- entitiesWithIdentifier.map { entity =>
          for {
            entityType <- me.fromOption(
              entity.entityType,
              PreservicaClientException(s"No entity type found for entity ${entity.ref}")
            )
            entity <- getEntity(entity.ref, entityType)
          } yield entity
        }.sequence
      } yield entities
    }

    override def addIdentifierForEntity(
        entityRef: UUID,
        entityType: EntityType,
        identifier: Identifier
    ): F[String] =
      for {
        token <- getAuthenticationToken
        _ <- sendXMLApiRequest(
          s"$apiUrl/${entityType.entityPath}/$entityRef/identifiers",
          token,
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
        body <- me.fromEither {
          res.body.left.map(err => PreservicaClientException(Method.GET, apiUri, res.code, err))
        }
      } yield body
    }

    override def updateEntityIdentifiers(
        entity: Entity,
        identifiers: Seq[IdentifierResponse]
    ): F[Seq[IdentifierResponse]] = {
      identifiers.map { identifier =>
        val requestBody = requestBodyForIdentifier(identifier.identifierName, identifier.value).some
        for {
          path <- me.fromOption(
            entity.path,
            PreservicaClientException(missingPathExceptionMessage(entity.ref))
          )
          token <- getAuthenticationToken
          url = uri"$apiUrl/$path/${entity.ref}/identifiers/${identifier.id}"
          _ <- sendXMLApiRequest(url.toString, token, Method.PUT, requestBody)
        } yield identifier
      }.sequence
    }

    override def getPreservicaNamespaceVersion(endpoint: String): F[Float] = {
      for {
        token <- getAuthenticationToken
        resXml <- sendXMLApiRequest(s"$apiBaseUrl/api/entity/$endpoint", token, Method.GET)
        version <- dataProcessor.getPreservicaNamespaceVersion(resXml)
      } yield version
    }
  }

  /** Represents a Preservica security tag
    */
  sealed trait SecurityTag {
    override def toString: String = getClass.getSimpleName.dropRight(1).toLowerCase
  }

  /** Represents an entity type
    */
  sealed trait EntityType {

    /** The path to be used in the url information-objects, structural-objects or content-objects
      */
    val entityPath: String

    /** Either IO, SO or CO
      */
    val entityTypeShort: String

    /** A string representing the implementing classes name
      * @return
      *   The class name
      */
    override def toString: String = getClass.getSimpleName.dropRight(1)
  }

  /** Represents a Preservica representation tag
    */
  sealed trait RepresentationType {
    override def toString: String = getClass.getSimpleName.dropRight(1)
  }

  /** Represents an entity to add to Preservica
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

  /** An object providing a method to return a SecurityTag instance from a string
    */
  object SecurityTag {

    /** Returns a security tag from a string
      * @param securityTagString
      *   The security tag as a string. Either 'open' or 'closed'
      * @return
      *   The SecurityTag instance
      */
    def fromString(securityTagString: String): Option[SecurityTag] = securityTagString match {
      case "open"   => Option(Open)
      case "closed" => Option(Closed)
      case _        => None
    }
  }

  /** Represents an Open security tag
    */
  case object Open extends SecurityTag

  /** Represents a closed security tag
    */
  case object Closed extends SecurityTag

  /** A structural object
    */
  case object StructuralObject extends EntityType {
    override val entityPath = "structural-objects"
    override val entityTypeShort: String = "SO"
  }

  /** An information object
    */
  case object InformationObject extends EntityType {
    override val entityPath = "information-objects"
    override val entityTypeShort: String = "IO"
  }

  /** A content object
    */
  case object ContentObject extends EntityType {
    override val entityPath = "content-objects"
    override val entityTypeShort: String = "CO"
  }

  case object Access extends RepresentationType

  case object Preservation extends RepresentationType

  sealed trait GenerationType {
    override def toString: String = getClass.getSimpleName.dropRight(1).toLowerCase
  }

  case object Original extends GenerationType

  case object Derived extends GenerationType
}
