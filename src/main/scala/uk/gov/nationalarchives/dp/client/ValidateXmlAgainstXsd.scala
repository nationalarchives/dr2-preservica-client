package uk.gov.nationalarchives.dp.client

import cats.Monad
import cats.implicits.given
import uk.gov.nationalarchives.dp.client.ValidateXmlAgainstXsd.PreservicaSchema

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import javax.xml.validation.Validator
import scala.xml.InputSource

trait ValidateXmlAgainstXsd[F[_]](schema: PreservicaSchema) {
  def xmlStringIsValid(xmlStringToValidate: String): F[Boolean]
}

object ValidateXmlAgainstXsd {

  enum PreservicaSchema(val pathOfSchemaFile: String):
    case XipXsdSchemaV7 extends PreservicaSchema("/XIP-V7.xsd")
    case OpexMetadataSchema extends PreservicaSchema("/OPEX-Metadata.xsd")

  def apply[F[_]: Monad](schema: PreservicaSchema): ValidateXmlAgainstXsd[F] =
    new ValidateXmlAgainstXsd[F](schema) {

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
          streamSource <- Monad[F].pure(new StreamSource(getClass.getResourceAsStream(schema.pathOfSchemaFile)))
          schema <- Monad[F].pure(schemaFactory.newSchema(streamSource))
          validator: Validator <- Monad[F].pure(schema.newValidator)

          isXmlFileValid <- Monad[F].pure(validator.validate(new DOMSource(xmlDocument))).map(_ => true)

        } yield isXmlFileValid
    }
}
