package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.implicits.{toFlatMapOps, toFunctorOps, toTraverseOps}
import uk.gov.nationalarchives.dp.client.DataProcessor.{ClosureResultIndexNames, EventAction}
import uk.gov.nationalarchives.dp.client.Entities._
import uk.gov.nationalarchives.dp.client.Client._

import java.time.ZonedDateTime
import java.util.UUID
import scala.xml.{Elem, NodeSeq}

class DataProcessor[F[_]]()(implicit me: MonadError[F, Throwable]) {
  implicit class NodeSeqUtils(ns: NodeSeq) {
    def textOfFirstElement(): F[String] = ns.headOption.map(_.text) match {
      case Some(value) => me.pure(value)
      case None        => me.raiseError(PreservicaClientException("Generation not found"))
    }
  }

  def existingApiId(res: Elem, elementName: String, fileName: String): Option[String] = {
    (res \\ elementName).find(n => (n \ "Name").text == fileName).map(n => (n \ "ApiId").text)
  }

  def closureResultIndexNames(res: Elem): F[ClosureResultIndexNames] = {
    def findByType(indexType: String) = (res \\ "term")
      .find(node => node.attribute("indexType").map(_.text).contains(indexType))
      .flatMap(_.attribute("indexName").map(_.text))
    for {
      shortName <- me.fromOption(
        (res \\ "shortName").headOption.map(_.text),
        PreservicaClientException("No short name found")
      )
      reviewDate <- me.fromOption(
        findByType("DATE"),
        PreservicaClientException("No review date index found for closure result")
      )
      documentStatus <- me.fromOption(
        findByType("STRING_DEFAULT"),
        PreservicaClientException("No document status index found for closure result")
      )
    } yield ClosureResultIndexNames(s"$shortName.$reviewDate", s"$shortName.$documentStatus")
  }

  def fragmentUrls(elem: Elem): F[Seq[String]] = {
    val fragments = elem \ "AdditionalInformation" \ "Metadata" \ "Fragment"
    me.pure(fragments.map(_.text))
  }

  def fragments(elems: Seq[Elem]): F[Seq[String]] = {
    val metadataObjects = elems.map { elem =>
      val eachContent: NodeSeq = elem \ "MetadataContainer" \ "Content"
      eachContent.flatMap(_.child).toString()
    }
    metadataObjects.filter(!_.isBlank) match {
      case Nil =>
        me.raiseError(
          PreservicaClientException(
            s"No content found for elements:\n${elems.map(_.toString).mkString("\n")}"
          )
        )
      case objects => me.pure(objects)
    }
  }

  def generationUrlFromEntity(contentEntity: Elem): F[String] =
    (contentEntity \ "AdditionalInformation" \ "Generations").textOfFirstElement()

  def allGenerationUrls(entity: Elem): F[Seq[String]] =
    (entity \ "Generations" \ "Generation").map(_.text) match {
      case Nil =>
        me.raiseError(PreservicaClientException(s"No generations found for entity:\n${entity.toString}"))
      case generationUrls => me.pure(generationUrls)
    }

  def allBitstreamUrls(entity: Seq[Elem]): F[Seq[String]] = {
    me.pure(
      entity.flatMap(e => (e \ "Bitstreams" \ "Bitstream").map(_.text))
    )
  }

  def allBitstreamInfo(entity: Seq[Elem]): F[Seq[BitStreamInfo]] = {
    me.pure {
      entity.map(e => {
        val filename = (e \\ "Bitstream" \\ "Filename").text
        val fileSize = (e \\ "Bitstream" \\ "FileSize").text.toLong
        val url = (e \\ "AdditionalInformation" \\ "Content").text
        BitStreamInfo(filename, fileSize, url)
      })
    }
  }

  def nextPage(elem: Elem): F[Option[String]] =
    me.pure((elem \ "Paging" \ "Next").headOption.map(_.text))

  def getEntities(elem: Elem): F[Seq[Entity]] =
    (elem \ "Entities" \ "Entity").map { e =>
      val entityAttributes = e.attributes

      def attrToString(key: String) = entityAttributes.get(key).map(_.toString()).getOrElse("")

      val ref = UUID.fromString(attrToString("ref"))
      val title = entityAttributes.get("title").map(_.toString)
      val description = entityAttributes.get("description").map(_.toString)
      val entityType = attrToString("type")
      val deleted = attrToString("deleted").nonEmpty
      fromType(entityType, ref, title, description, deleted)
    }.sequence

  def getEventActions(elem: Elem): F[Seq[EventAction]] = {
    me.pure(
      (elem \ "EventActions" \ "EventAction")
        .map { e =>
          val eventRef = UUID.fromString((e \\ "Event" \\ "Ref").text)
          val eventType = (e \\ "Event").flatMap(event => event.attributes("type")).text
          val dateOfEvent = ZonedDateTime.parse((e \\ "Event" \\ "Date").text)

          EventAction(eventRef, eventType, dateOfEvent)
        }
    )
  }
}

object DataProcessor {
  def apply[F[_]]()(implicit me: MonadError[F, Throwable]) = new DataProcessor[F]()

  case class EventAction(eventRef: UUID, eventType: String, dateOfEvent: ZonedDateTime)

  case class ClosureResultIndexNames(reviewDateName: String, documentStatusName: String)
}
