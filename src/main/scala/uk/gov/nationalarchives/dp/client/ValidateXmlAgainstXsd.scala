package uk.gov.nationalarchives.dp.client

import cats.Monad
import cats.implicits.given

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.{SchemaFactory, Validator}
import scala.xml.InputSource

trait ValidateXmlAgainstXsd[F[_]](pathOfSchemaFile: String) {
  def xmlStringIsValid(xmlStringToValidate: String): F[Boolean]
}

object ValidateXmlAgainstXsd {
  val xipXsdSchemaV6 = "/XIP-V6.xsd"
  def apply[F[_]: Monad](pathOfSchemaFile: String): ValidateXmlAgainstXsd[F] =
    new ValidateXmlAgainstXsd[F](pathOfSchemaFile) {

      override def xmlStringIsValid(xmlStringToValidate: String): F[Boolean] =
        for {
          documentBuilderFactory <- Monad[F].pure {
            val documentBuilderFactory = DocumentBuilderFactory.newInstance
            documentBuilderFactory.setNamespaceAware(true)
            documentBuilderFactory
          }
          documentBuilder <- Monad[F].pure(documentBuilderFactory.newDocumentBuilder)
          xmlDocument <- Monad[F].pure(documentBuilder.parse(new InputSource(new StringReader(xmlStringToValidate))))
          schemaFactory <- Monad[F].pure(SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI))
          streamSource <- Monad[F].pure(new StreamSource(getClass.getResourceAsStream(pathOfSchemaFile)))
          schema <- Monad[F].pure(schemaFactory.newSchema(streamSource))
          validator: Validator <- Monad[F].pure(schema.newValidator)

          isXmlFileValid <- Monad[F].pure(validator.validate(new DOMSource(xmlDocument))).map(_ => true)

        } yield isXmlFileValid
    }
}
