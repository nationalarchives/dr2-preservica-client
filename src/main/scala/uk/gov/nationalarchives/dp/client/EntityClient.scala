package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import sttp.capabilities.Streams
import sttp.client3._
import sttp.model.Method
import uk.gov.nationalarchives.dp.client.Client._
import uk.gov.nationalarchives.dp.client.DataProcessor.EventAction
import uk.gov.nationalarchives.dp.client.Entities.{Entity, Identifier}
import uk.gov.nationalarchives.dp.client.EntityClient.{AddEntityRequest, EntityType, UpdateEntityRequest}

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.xml.{Elem, PrettyPrinter, XML}

trait EntityClient[F[_], S] {
  val dateFormatter: DateTimeFormatter

  def nodesFromEntity(
      entityRef: UUID,
      entityType: EntityType,
      childNodeNames: List[String],
      secretName: String
  ): F[Map[String, String]]

  def metadataForEntity(entity: Entity, secretName: String): F[Seq[Elem]]

  def getBitstreamInfo(contentRef: UUID, secretName: String): F[Seq[BitStreamInfo]]

  def addEntity(addEntityRequest: AddEntityRequest, secretName: String): F[UUID]

  def updateEntity(updateEntityRequest: UpdateEntityRequest, secretName: String): F[String]

  def streamBitstreamContent[T](
      stream: Streams[S]
  )(url: String, secretName: String, streamFn: stream.BinaryStream => F[T]): F[T]

  def entitiesUpdatedSince(
      dateTime: ZonedDateTime,
      secretName: String,
      startEntry: Int,
      maxEntries: Int = 1000
  ): F[Seq[Entity]]

  def entityEventActions(
      entity: Entity,
      secretName: String,
      startEntry: Int = 0,
      maxEntries: Int = 1000
  ): F[Seq[EventAction]]

  def entitiesByIdentifier(
      identifier: Identifier,
      secretName: String
  ): F[Seq[Entity]]

  def addIdentifierForEntity(
      entityRef: UUID,
      entityType: EntityType,
      identifier: Identifier,
      secretName: String
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

    private def entity(entityRef: UUID, entityPath: String, secretName: String): F[Elem] =
      for {
        path <- me.fromOption(
          entityPath.some,
          PreservicaClientException(missingPathExceptionMessage(entityRef))
        )
        url = uri"$apiBaseUrl/api/entity/$path/$entityRef"
        token <- getAuthenticationToken(secretName)
        entity <- sendXMLApiRequest(url.toString(), token, Method.GET)
      } yield entity

    private def validateEntityUpdateInputs(
        entityType: EntityType,
        parentRef: Option[UUID],
        secretName: String
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
        token <- getAuthenticationToken(secretName)
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
              ${if (ref.nonEmpty) s"<Ref>${ref.get}</Ref>"}
              <Title>$title</Title>
              ${if (descriptionToChange.nonEmpty) s"<Description>${descriptionToChange.get}</Description>"}
              <SecurityTag>$securityTag</SecurityTag>
              ${if (parentRef.nonEmpty) s"<Parent>${parentRef.get}</Parent>"}
            </$nodeName>"""
    }

    override def addEntity(addEntityRequest: AddEntityRequest, secretName: String): F[UUID] = {
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
          addEntityRequest.parentRef,
          secretName
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

    override def updateEntity(updateEntityRequest: UpdateEntityRequest, secretName: String): F[String] = {
      for {
        nodeNameAndToken <- validateEntityUpdateInputs(
          updateEntityRequest.entityType,
          updateEntityRequest.parentRef,
          secretName
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

    override def nodesFromEntity(
        entityRef: UUID,
        entityType: EntityType,
        childNodeNames: List[String],
        secretName: String
    ): F[Map[String, String]] =
      for {
        entityResponse <- entity(entityRef, entityType.entityPath, secretName)

        childNodeValues <- childNodeNames.distinct.map { childNodeName =>
          val nodeName = entityType.toString
          dataProcessor.childNodeFromEntity(entityResponse, nodeName, childNodeName)
        }.sequence
      } yield childNodeNames.zip(childNodeValues).toMap

    override def getBitstreamInfo(
        contentRef: UUID,
        secretName: String
    ): F[Seq[BitStreamInfo]] =
      for {
        token <- getAuthenticationToken(secretName)
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

    override def metadataForEntity(entity: Entity, secretName: String): F[Seq[Elem]] =
      for {
        token <- getAuthenticationToken(secretName)

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
        secretName: String,
        startEntry: Int,
        maxEntries: Int = 1000
    ): F[Seq[Entity]] = {
      val dateString = dateTime.format(dateFormatter)
      val queryParams = Map("date" -> dateString, "max" -> maxEntries, "start" -> startEntry)
      val url = uri"$apiBaseUrl/api/entity/entities/updated-since?$queryParams"
      for {
        token <- getAuthenticationToken(secretName)
        updatedEntities <- getEntities(url.toString, token)
      } yield updatedEntities
    }

    override def entityEventActions(
        entity: Entity,
        secretName: String,
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
        token <- getAuthenticationToken(secretName)
        eventActions <- eventActions(url.toString.some, token, Nil)
      } yield eventActions.reverse // most recent event first
    }

    override def entitiesByIdentifier(
        identifier: Identifier,
        secretName: String
    ): F[Seq[Entity]] = {
      val queryParams = Map("type" -> identifier.identifierName, "value" -> identifier.value)
      val url = uri"$apiBaseUrl/api/entity/entities/by-identifier?$queryParams"
      for {
        token <- getAuthenticationToken(secretName)
        entitiesWithIdentifier <- getEntities(url.toString, token)
      } yield entitiesWithIdentifier
    }

    override def addIdentifierForEntity(
        entityRef: UUID,
        entityType: EntityType,
        identifier: Identifier,
        secretName: String
    ): F[String] =
      for {
        token <- getAuthenticationToken(secretName)

        identifierAsXml: String = {
          val xml = <Identifier xmlns="http://preservica.com/XIP/v6.5">
            <Type>{identifier.identifierName}</Type>
            <Value>{identifier.value}</Value>
          </Identifier>
          new PrettyPrinter(80, 2).format(xml)
        }
        requestBody = s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n$identifierAsXml"""

        _ <- sendXMLApiRequest(
          s"$apiBaseUrl/api/entity/${entityType.entityPath}/$entityRef/identifiers",
          token,
          Method.POST,
          Some(requestBody)
        )
        response = s"The Identifier was added"
      } yield response

    def streamBitstreamContent[T](
        stream: Streams[S]
    )(url: String, secretName: String, streamFn: stream.BinaryStream => F[T]): F[T] = {
      val apiUri = uri"$url"
      def request(token: String) = basicRequest
        .get(apiUri)
        .headers(Map("Preservica-Access-Token" -> token))
        .response(asStream(stream)(streamFn))

      for {
        token <- getAuthenticationToken(secretName)
        res <- backend.send(request(token))
        body <- me.fromEither {
          res.body.left.map(err => PreservicaClientException(Method.GET, apiUri, res.code, err))
        }
      } yield body
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

  case object Open extends SecurityTag

  case object Closed extends SecurityTag

  sealed trait EntityType {
    val entityPath: String
    override def toString: String = getClass.getSimpleName.dropRight(1)
  }

  case object StructuralObject extends EntityType {
    override val entityPath = "structural-objects"
  }

  case object InformationObject extends EntityType {
    override val entityPath = "information-objects"
  }

  case object ContentObject extends EntityType {
    override val entityPath = "content-objects"
  }
}
