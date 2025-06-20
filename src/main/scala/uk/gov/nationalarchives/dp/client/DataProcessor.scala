package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.implicits.*
import uk.gov.nationalarchives.dp.client.Client.*
import uk.gov.nationalarchives.dp.client.DataProcessor.EventAction
import uk.gov.nationalarchives.dp.client.Entities.*
import uk.gov.nationalarchives.dp.client.EntityClient.*
import uk.gov.nationalarchives.dp.client.EntityClient.EntityType.*
import uk.gov.nationalarchives.dp.client.EntityClient.GenerationType.*

import java.time.ZonedDateTime
import java.util.UUID
import scala.xml.{Elem, MetaData, Node, NodeSeq}

/** A class to process XML responses from Preservica
  * @param me
  *   An implicit MonadError instance
  * @tparam F
  *   The effect type
  */
class DataProcessor[F[_]]()(using me: MonadError[F, Throwable]) {

  extension (ns: NodeSeq)
    def textOfFirstElement(): F[String] = ns.headOption.map(_.text) match {
      case Some(value) => me.pure(value)
      case None        => me.raiseError(PreservicaClientException("Generation URL not found"))
    }

  /** Converts an entity response to an [[Entities.Entity]] case class
    * @param entityRef
    *   The reference of the entity
    * @param entityResponse
    *   The XML response from Preservica
    * @param entityType
    *   The [[EntityClient.EntityType]] of the entity.
    * @return
    *   An [[Entities.Entity]] wrapped in the F effect
    */
  def getEntity(entityRef: UUID, entityResponse: Elem, entityType: EntityType): F[Entity] = {
    (entityResponse \ entityType.toString).headOption
      .map { entity =>
        def optionValue(nodeName: String): Option[String] = (entity \ nodeName).headOption.map(_.text.trim)
        val title = optionValue("Title")
        val description = optionValue("Description")
        val securityTag = optionValue("SecurityTag").flatMap(SecurityTag.fromString)
        val deleted = optionValue("Deleted").isDefined
        val parent = optionValue("Parent").map(UUID.fromString)
        fromType[F](entityType.entityTypeShort, entityRef, title, description, deleted, securityTag, parent)
      }
      .getOrElse(me.raiseError(PreservicaClientException(s"Entity type '$entityType' not found for id $entityRef")))
  }

  /** Retrieves the [[EntityClient.EntityType]] Node from an entity response
    * @param entityRef
    *   The reference of the entity
    * @param entityResponse
    *   The XML response from Preservica
    * @param entityType
    *   The [[EntityClient.EntityType]] of the entity.
    * @return
    *   An [[scala.xml.Node]] found in the entity response (by searching for [[EntityClient.EntityType]]) wrapped in the
    *   F effect
    */
  def getEntityXml(entityRef: UUID, entityResponse: Elem, entityType: EntityType): F[Node] =
    (entityResponse \ entityType.toString).headOption
      .map(me.pure)
      .getOrElse(me.raiseError(PreservicaClientException(s"Entity type '$entityType' not found for id $entityRef")))

  /** Fetches `nodeName` -> `childNodeName` text from `entityResponse`
    * @param entityResponse
    *   The response from the entity
    * @param nodeName
    *   The node name to search
    * @param childNodeName
    *   The child node name to return the text from
    * @return
    *   The text of the child node wrapped in the F effect
    */
  def childNodeFromEntity(entityResponse: Elem, nodeName: String, childNodeName: String): F[String] =
    me.fromOption(
      (entityResponse \ nodeName \ childNodeName.capitalize).headOption.map(_.text),
      PreservicaClientException(s"Either $nodeName or $childNodeName does not exist on entity")
    )

  /** Returns `elementName` -> `ApiId` from the XML response where the `Name` field matches `fileName`
    * @param res
    *   The XML response from Preservica
    * @param elementName
    *   The name of the element
    * @param fileName
    *   The name of the file
    * @return
    *   An optional string with the `ApiId` or `None` if not found.
    */
  def existingApiId(res: Elem, elementName: String, fileName: String): Option[String] = {
    (res \\ elementName).find(n => (n \ "Name").text == fileName).map(n => (n \ "ApiId").text)
  }

  /** Returns the metadata fragment urls from the element
    * @param entityResponseElement
    *   The EntityResponse element to search
    * @return
    *   A `Seq` of `String` wrapped in the F effect with the fragment URLS
    */
  def fragmentUrls(entityResponseElement: Elem): F[Seq[String]] = {
    val fragmentUrls = entityResponseElement \ "AdditionalInformation" \ "Metadata" \ "Fragment"
    me.pure(fragmentUrls.map(_.text))
  }

  /** Returns a list of metadata content
    * @param metadataResponseElements
    *   The MetadataResponse elements that contains the fragments
    * @return
    *   A `Seq` of String representing the metadata XML or an error if none are found
    */
  def fragments(metadataResponseElements: Seq[Elem]): F[Seq[Node]] = {
    val metadataContainerObjects =
      (metadataResponseElements \ "MetadataContainer").toList

    val emptyMetadataContainerObjects = metadataContainerObjects.filter(_.isEmpty)
    if (metadataContainerObjects.isEmpty || emptyMetadataContainerObjects.nonEmpty || metadataContainerObjects.size != metadataResponseElements.size) && metadataResponseElements.nonEmpty
    then
      me.raiseError(
        PreservicaClientException(
          s"Could not be retrieve all 'MetadataContainer' Nodes from:\n${metadataResponseElements.mkString("\n")}"
        )
      )
    else me.pure(metadataContainerObjects)
  }

  /** Gets the text of the first generation element
    * @param contentObjectElement
    *   The Content Object element to search
    * @return
    *   The text of the first generation element wrapped in the F effect
    */
  def generationUrlFromEntity(contentObjectElement: Elem): F[String] =
    (contentObjectElement \ "AdditionalInformation" \ "Generations").textOfFirstElement()

  /** Returns all the text content of every `Generations` -> `Generation` element
    * @param generationsElement
    *   The 'Generations' element containing the generations
    * @return
    *   A `Seq` of `String` with the text content of every `Generations` -> `Generation` element
    */
  def allGenerationUrls(generationsElement: Elem, contentObjectRef: UUID): F[Seq[String]] =
    (generationsElement \ "Generations" \ "Generation").map(_.text) match {
      case Nil =>
        me.raiseError(PreservicaClientException(s"No generations found for entity ref: $contentObjectRef"))
      case generationUrls => me.pure(generationUrls)
    }

  /** Returns whether the the text content of every `Generations` -> `Generation` element
    * @param generationsElement
    *   The 'Generations' element containing the generations
    * @return
    *   A `Seq` of `String` with the text content of every `Generations` -> `Generation` element
    */

  def generationType(generationsElement: Elem, contentObjectRef: UUID): F[GenerationType] =
    (generationsElement \ "Generation").map(_.attributes) match {
      case Nil | List(xml.Null) =>
        me.raiseError(PreservicaClientException(s"No attributes found for entity ref: $contentObjectRef"))
      case (attributesPerGeneration: MetaData) :: _ =>
        val potentialOriginalityStatus = attributesPerGeneration.get("original").map(_.text)

        val potentialGenerationType: F[GenerationType] =
          potentialOriginalityStatus match {
            case Some("true")  => me.pure(Original)
            case Some("false") => me.pure(Derived)
            case _ =>
              me.raiseError(
                PreservicaClientException(
                  s"'original' attribute could not be found on Generation for entity ref: $contentObjectRef"
                )
              )
          }
        potentialGenerationType
    }

  /** Returns all the text content of every `Bitstreams` -> `Bitstream` element
    * @param generationElement
    *   The 'Generation' element containing the bitstreams
    * @return
    *   A `Seq` of `String` with the text content of every `Bitstreams` -> `Bitstream` element
    */
  def allBitstreamUrls(generationElement: Elem): F[Seq[String]] = me.pure {
    (generationElement \ "Bitstreams" \ "Bitstream").map(_.text)
  }

  /** Returns a list of [[Client.BitStreamInfo]] objects
    * @param bitstreamElements
    *   The elements containing the bitstream information
    * @return
    *   A `Seq` of `BitStreamInfo` objects parsed from the XML
    */
  def allBitstreamInfo(
      bitstreamElements: Seq[Elem],
      generationType: GenerationType,
      contentObject: Entity
  ): F[Seq[BitStreamInfo]] =
    me.pure {
      bitstreamElements.map { be =>
        val filename = (be \\ "Bitstream" \\ "Filename").text
        val fileSize = (be \\ "Bitstream" \\ "FileSize").text.toLong
        val bitstreamInfoUrl = (be \\ "AdditionalInformation" \\ "Self").text

        val bitstreamInfoUrlReversed = bitstreamInfoUrl.split("/").reverse
        val generationVersion = bitstreamInfoUrlReversed(2).toInt

        val bitstreamUrl = (be \\ "AdditionalInformation" \\ "Content").text

        val fixities = (be \\ "Bitstream" \\ "Fixities" \\ "Fixity").map { eachFixity =>
          Fixity((eachFixity \ "FixityAlgorithmRef").text, (eachFixity \ "FixityValue").text)
        }.toList

        BitStreamInfo(
          filename,
          fileSize,
          bitstreamUrl,
          fixities,
          generationVersion,
          generationType,
          contentObject.title,
          contentObject.parent
        )
      }
    }

  /** Returns the next page
    * @param elem
    *   The element containing the pagination element
    * @return
    *   The optional next page or None if there are none
    */
  def nextPage(elem: Elem): F[Option[String]] =
    me.pure((elem \ "Paging" \ "Next").headOption.map(_.text))

  /** Parses a list of [[Entities.Entity]] parsed from the XML
    * @param elem
    *   The element to parse
    * @return
    *   A `Seq` of [[Entities.Entity]]
    */
  def getEntities(elem: Elem): F[Seq[Entity]] =
    (elem \ "Entities" \ "Entity").map { e =>
      val entityAttributes = e.attributes

      val ref = UUID.fromString(attrToString(e, "ref"))
      val title = entityAttributes.get("title").map(_.toString)
      val description = entityAttributes.get("description").map(_.toString)
      val entityType = attrToString(e, "type")
      val deleted = attrToString(e, "deleted").nonEmpty
      fromType[F](entityType, ref, title, description, deleted)
    }.sequence

  def getChildren(elem: Elem, potentialParentRef: Option[UUID]): F[Seq[EntityRef]] =
    me.pure((elem \ "Children" \ "Child").map { c =>
      val ref = UUID.fromString(attrToString(c, "ref"))
      val entityType = attrToString(c, "type")
      entityType match {
        case "SO" => EntityRef.StructuralObjectRef(ref, potentialParentRef)
        case "IO" => EntityRef.InformationObjectRef(ref, potentialParentRef.get)
        case "CO" => EntityRef.ContentObjectRef(ref, potentialParentRef.get)
      }
    })

  /** Returns a list of [[Entities.IdentifierResponse]] objects
    * @param elem
    *   The element containing the identifiers
    * @return
    *   A `Seq` of `IdentifierResponse` objects parsed from the XML
    */
  def getIdentifiers(elem: Elem): F[Seq[IdentifierResponse]] = {
    me.pure {
      (elem \ "Identifiers" \ "Identifier")
        .map { i =>
          val id = (i \ "ApiId").text
          val identifierName = (i \ "Type").text
          val identifierValue = (i \ "Value").text
          IdentifierResponse(id, identifierName, identifierValue)
        }
    }
  }

  /** Returns a list of [[NodeSeq]] objects
    * @param elem
    *   The element containing the identifiers
    * @return
    *   A `Seq` of `Identifier` elements parsed from the XML
    */
  def getIdentifiersXml(elem: Elem): F[Seq[Node]] = me.pure(elem \ "Identifiers" \ "Identifier")

  /** Returns a list of [[DataProcessor.EventAction]] objects
    * @param elem
    *   The element containing the event actions
    * @return
    *   A `Seq` of `EventAction` objects parsed from the XML
    */
  def getEventActions(elem: Elem): F[Seq[EventAction]] = {
    me.pure(
      (elem \ "EventActions" \ "EventAction")
        .map { e =>
          val eventRef = UUID.fromString((e \\ "Event" \\ "Ref").text)
          val eventType = (e \\ "Event").flatMap(event => event.attributes("type")).text
          val dateOfEvent = ZonedDateTime.parse((e \ "Date").text)

          EventAction(eventRef, eventType, dateOfEvent)
        }
    )
  }

  /** Gets the child node from a workflow instance response
    * @param workflowInstanceResponse
    *   The workflow instance response
    * @param nodeName
    *   The name of the node to search for
    * @return
    *   The text of the child node provided as an argument.
    */
  def childNodeFromWorkflowInstance(workflowInstanceResponse: Elem, nodeName: String): F[String] =
    me.fromOption(
      (workflowInstanceResponse \ nodeName).headOption.map(_.text),
      PreservicaClientException(s"'$nodeName' does not exist on the workflowInstance response.")
    )

  /** Returns a `Seq` of String objects
    * @param elem
    *   The element containing the representations
    * @param representationType
    *   The (Optional) representation type that you want returned
    * @return
    *   A `Seq` of `String` objects parsed from the XML
    */
  def getUrlsToEntityRepresentations(elem: Elem, representationType: Option[RepresentationType]): F[Seq[String]] =
    me.pure {
      (elem \ "Representations" \ "Representation").collect {
        case rep if representationType.isEmpty || attrToString(rep, "type") == representationType.get.toString =>
          rep.text
      }
    }

  private def attrToString(node: Node, key: String) = node.attributes.get(key).map(_.toString()).getOrElse("")

  /** Returns a `Seq` of String objects
    * @param elem
    *   The element containing the representations
    * @param representationType
    *   The (Optional) representation type that you want returned
    * @return
    *   A `Seq` of `String` objects parsed from the XML
    */
  def getContentObjectsFromRepresentation(
      elem: Elem,
      representationType: RepresentationType,
      ioEntityRef: UUID
  ): F[Seq[Entity]] =
    me.pure {
      (elem \ "Representation" \ "ContentObjects" \ "ContentObject").map { rep =>
        Entity(
          Some(ContentObject),
          UUID.fromString(rep.text),
          None,
          None,
          deleted = false,
          ContentObject.entityPath.some,
          None,
          Some(ioEntityRef)
        )
      }
    }

  /** Returns a `Float`
    * @param elem
    *   The element containing the namespace with the version in it
    * @return
    *   A `Float`, representing the Preservica version, parsed from the XML
    */
  def getPreservicaNamespaceVersion(elem: Elem): F[Float] =
    me.pure {
      val namespaceUrl = elem.namespace
      val version = namespaceUrl.split("/").last
      version.stripPrefix("v").toFloat
    }

  /** Returns a list of [[scala.xml.Node]] XML objects
    *
    * @param elem
    *   The element containing the Entity's links
    * @return
    *   A `Seq` of `Node` containing the links
    */
  def getEntityLinksXml(ref: UUID, elem: Elem): F[Seq[Node]] = me.pure {
    val links = elem \ "Links" \ "Link"
    links.flatMap { link =>
      for {
        linkType <- link.attribute("linkType").flatMap(_.headOption)
        linkDirection <- link.attribute("linkDirection").flatMap(_.headOption)
        refLinks <- link.attribute("ref").flatMap(_.headOption.map(_.toString))
      } yield {
        val entities =
          if linkDirection.text == "To" then
            List(
              <xip:ToEntity>{refLinks}</xip:ToEntity>
          <xip:FromEntity>{ref.toString}</xip:FromEntity>
            )
          else
            List(
              <xip:ToEntity>{ref.toString}</xip:ToEntity>
            <xip:FromEntity>{refLinks}</xip:FromEntity>
            )

        <xip:Link xmlns="http://preservica.com/EntityAPI/v7.7" xmlns:xip="http://preservica.com/XIP/v7.7">
          <xip:Type>{linkType.text}</xip:Type>
          {entities}
        </xip:Link>
      }
    }
  }

  /** Returns a Seq containing multiple [[scala.xml.Node]] XML object objects representing an `EventAction`
    *
    * @param eventActionsResponseElement
    *   The element containing the Event actions
    * @return
    *   A `Seq` of Node, representing Event actions
    */
  def getEventActionElements(eventActionsResponseElement: Elem): F[Seq[Node]] =
    me.pure((eventActionsResponseElement \ "EventActions" \ "EventAction"))

  /** Returns a Seq containing one Representation [[scala.xml.Node]] XML object
    *
    * @param representationResponseElement
    *   The element containing the information for the Representation
    * @return
    *   A `Seq` of `Node` containing the links
    */
  def getRepresentationElement(representationResponseElement: Elem): F[Seq[Node]] =
    me.pure((representationResponseElement \ "Representation"))

  /** Returns a Seq containing one Generation [[scala.xml.Node]] XML object
    *
    * @param generationResponseElement
    *   The element containing the information for the Generation
    * @return
    *   A `Seq` of `Node` containing the links
    */
  def getGenerationElement(generationResponseElement: Elem): F[Seq[Node]] =
    me.pure((generationResponseElement \ "Generation"))
}

/** An apply method for the `DataProcessor` class and the `EventAction` case class
  */
object DataProcessor {
  def apply[F[_]](implicit me: MonadError[F, Throwable]) = new DataProcessor[F]()

  /** @param eventRef
    *   The reference of the event
    * @param eventType
    *   The type of the event
    * @param dateOfEvent
    *   The date of the event
    */
  case class EventAction(eventRef: UUID, eventType: String, dateOfEvent: ZonedDateTime)
}
