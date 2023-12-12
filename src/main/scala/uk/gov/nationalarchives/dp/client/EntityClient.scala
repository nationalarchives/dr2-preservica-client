package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import sttp.capabilities.Streams
import sttp.client3._
import sttp.model.Method
import uk.gov.nationalarchives.dp.client.Client._
import uk.gov.nationalarchives.dp.client.DataProcessor.EventAction
import uk.gov.nationalarchives.dp.client.Entities.{Entity, IdentifierResponse}
import uk.gov.nationalarchives.DynamoFormatters.Identifier
import uk.gov.nationalarchives.dp.client.EntityClient.{AddEntityRequest, EntityType, UpdateEntityRequest}

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.xml.Utility.escape
import scala.xml.{Elem, PrettyPrinter, XML}

trait EntityClient[F[_], S] {
  val dateFormatter: DateTimeFormatter

  def metadataForEntity(entity: Entity): F[Seq[Elem]]

  def getBitstreamInfo(contentRef: UUID): F[Seq[BitStreamInfo]]

  def getEntity(entityRef: UUID, entityType: EntityType): F[Entity]

  def getEntityIdentifiers(entity: Entity): F[Seq[IdentifierResponse]]

  def addEntity(addEntityRequest: AddEntityRequest): F[UUID]

  def updateEntity(updateEntityRequest: UpdateEntityRequest): F[String]

  def updateEntityIdentifiers(entity: Entity, identifiers: Seq[IdentifierResponse]): F[Seq[IdentifierResponse]]

  def streamBitstreamContent[T](
      stream: Streams[S]
  )(url: String, streamFn: stream.BinaryStream => F[T]): F[T]

  def entitiesUpdatedSince(
      dateTime: ZonedDateTime,
      startEntry: Int,
      maxEntries: Int = 1000
  ): F[Seq[Entity]]

  def entityEventActions(
      entity: Entity,
      startEntry: Int = 0,
      maxEntries: Int = 1000
  ): F[Seq[EventAction]]

  def entitiesByIdentifier(
      identifier: Identifier
  ): F[Seq[Entity]]

  def addIdentifierForEntity(
      entityRef: UUID,
      entityType: EntityType,
      identifier: Identifier
  ): F[String]
}

object EntityClient {

  def createEntityClient[F[_], S](clientConfig: ClientConfig[F, S])(implicit
      me: MonadError[F, Throwable],
      sync: Sync[F]
  ): EntityClient[F, S] = new EntityClient[F, S] {
    val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private val apiBaseUrl: String = clientConfig.apiBaseUrl
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
        val xml = <Identifier xmlns="http://preservica.com/XIP/v6.5">
          <Type>{identifierName}</Type>
          <Value>{identifierValue}</Value>
        </Identifier>
        new PrettyPrinter(80, 2).format(xml)
      }
      s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n$identifierAsXml"""
    }

    override def getEntity(entityRef: UUID, entityType: EntityType): F[Entity] = {
      val url = uri"$apiBaseUrl/api/entity/${entityType.entityPath}/$entityRef"
      for {
        token <- getAuthenticationToken
        entityResponse <- sendXMLApiRequest(url.toString(), token, Method.GET)
        entity <- dataProcessor.getEntity(entityRef, entityResponse, entityType)
      } yield entity
    }

    override def getEntityIdentifiers(entity: Entity): F[Seq[IdentifierResponse]] = {
      for {
        path <- me.fromOption(
          entity.path,
          PreservicaClientException(missingPathExceptionMessage(entity.ref))
        )
        url = uri"$apiBaseUrl/api/entity/$path/${entity.ref}/identifiers"
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
            ${if (addOpeningXipTag) s"""<XIP xmlns="http://preservica.com/XIP/v6.5">""" else ""}
            <$nodeName xmlns="http://preservica.com/XIP/v6.5">
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
        url = uri"$apiBaseUrl/api/entity/$path"
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
        url = uri"$apiBaseUrl/api/entity/$path/${updateEntityRequest.ref}"
        _ <- sendXMLApiRequest(url.toString, token, Method.PUT, Some(updateRequestBody))
        response = "Entity was updated"
      } yield response
    }

    override def getBitstreamInfo(
        contentRef: UUID
    ): F[Seq[BitStreamInfo]] =
      for {
        token <- getAuthenticationToken
        contentEntity <- sendXMLApiRequest(
          s"$apiBaseUrl/api/entity/${ContentObject.entityPath}/$contentRef",
          token,
          Method.GET
        )
        generationUrl <- dataProcessor.generationUrlFromEntity(contentEntity)
        generationInfo <- sendXMLApiRequest(generationUrl, token, Method.GET)
        allGenerationUrls <- dataProcessor.allGenerationUrls(generationInfo)
        allGenerationElements <- allGenerationUrls
          .map(url => sendXMLApiRequest(url, token, Method.GET))
          .sequence
        allUrls <- dataProcessor.allBitstreamUrls(allGenerationElements)
        bitstreamXmls <- allUrls.map(url => sendXMLApiRequest(url, token, Method.GET)).sequence
        allBitstreamInfo <- dataProcessor.allBitstreamInfo(bitstreamXmls)
      } yield allBitstreamInfo

    override def metadataForEntity(entity: Entity): F[Seq[Elem]] =
      for {
        token <- getAuthenticationToken

        path <- me.fromOption(
          entity.path,
          PreservicaClientException(missingPathExceptionMessage(entity.ref))
        )
        entityInfo <- sendXMLApiRequest(
          s"$apiBaseUrl/api/entity/$path/${entity.ref}",
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
      val url = uri"$apiBaseUrl/api/entity/entities/updated-since?$queryParams"
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
        url = uri"$apiBaseUrl/api/entity/$path/${entity.ref}/event-actions?$queryParams"
        token <- getAuthenticationToken
        eventActions <- eventActions(url.toString.some, token, Nil)
      } yield eventActions.reverse // most recent event first
    }

    override def entitiesByIdentifier(
        identifier: Identifier
    ): F[Seq[Entity]] = {
      val queryParams = Map("type" -> identifier.identifierName, "value" -> identifier.value)
      val url = uri"$apiBaseUrl/api/entity/entities/by-identifier?$queryParams"
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
          s"$apiBaseUrl/api/entity/${entityType.entityPath}/$entityRef/identifiers",
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
          url = uri"$apiBaseUrl/api/entity/$path/${entity.ref}/identifiers/${identifier.id}"
          _ <- sendXMLApiRequest(url.toString, token, Method.PUT, requestBody)
        } yield identifier
      }.sequence
    }
  }

  case class AddEntityRequest(
      ref: Option[UUID],
      title: String,
      description: Option[String],
      entityType: EntityType,
      securityTag: SecurityTag,
      parentRef: Option[UUID]
  )

  case class UpdateEntityRequest(
      ref: UUID,
      title: String,
      descriptionToChange: Option[String],
      entityType: EntityType,
      securityTag: SecurityTag,
      parentRef: Option[UUID]
  )

  sealed trait SecurityTag {
    override def toString: String = getClass.getSimpleName.dropRight(1).toLowerCase

  }
  object SecurityTag {
    def fromString(securityTagString: String): Option[SecurityTag] = securityTagString match {
      case "open"   => Option(Open)
      case "closed" => Option(Closed)
      case _        => None
    }
  }

  case object Open extends SecurityTag

  case object Closed extends SecurityTag

  sealed trait EntityType {
    val entityPath: String
    val entityTypeShort: String
    override def toString: String = getClass.getSimpleName.dropRight(1)
  }

  case object StructuralObject extends EntityType {
    override val entityPath = "structural-objects"
    override val entityTypeShort: String = "SO"
  }

  case object InformationObject extends EntityType {
    override val entityPath = "information-objects"
    override val entityTypeShort: String = "IO"
  }

  case object ContentObject extends EntityType {
    override val entityPath = "content-objects"
    override val entityTypeShort: String = "CO"
  }
}
