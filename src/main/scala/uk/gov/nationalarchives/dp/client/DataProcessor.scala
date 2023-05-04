package uk.gov.nationalarchives.dp.client

import cats.MonadError
import cats.implicits.toTraverseOps
import uk.gov.nationalarchives.dp.client.Client.BitStreamInfo
import uk.gov.nationalarchives.dp.client.Entity.fromType

import java.util.UUID
import scala.xml.{Elem, NodeSeq}

class DataProcessor[F[_]]()(implicit me: MonadError[F, Throwable]) {

  implicit class NodeSeqUtils(ns: NodeSeq) {
    def textOfFirstElement(): F[String] = ns.headOption.map(_.text) match {
      case Some(value) => me.pure(value)
      case None        => me.raiseError(new RuntimeException("Generation not found"))
    }
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
          new RuntimeException(
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
        me.raiseError(new RuntimeException(s"No generations found for entity:\n${entity.toString}"))
      case generationUrls => me.pure(generationUrls)
    }

  def allBitstreamInfo(entity: Seq[Elem]): F[Seq[BitStreamInfo]] = {
    me.pure(
      entity.flatMap(e =>
        (e \ "Bitstreams" \ "Bitstream").map { b =>
          val name = b.attribute("filename").map(_.toString).getOrElse("")
          val url = b.text
          BitStreamInfo(name, url)
        }
      )
    )
  }

  def nextPage(elem: Elem): F[Option[String]] =
    me.pure((elem \ "Paging" \ "Next").headOption.map(_.text))

  def getUpdatedEntities(elem: Elem): F[Seq[Entity]] = {
    (elem \ "Entities" \ "Entity")
      .map(e => {
        val entityAttributes = e.attributes
        def attrToString(key: String) = entityAttributes.get(key).map(_.toString()).getOrElse("")

        val ref = UUID.fromString(attrToString("ref"))
        val title = attrToString("title")
        val entityType = attrToString("type")
        val deleted = attrToString("deleted").nonEmpty
        fromType(entityType, ref, title, deleted)
      })
      .sequence
  }

}

object DataProcessor {
  def apply[F[_]]()(implicit me: MonadError[F, Throwable]) = new DataProcessor[F]()
}
