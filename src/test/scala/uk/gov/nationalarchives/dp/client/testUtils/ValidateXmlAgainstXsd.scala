package uk.gov.nationalarchives.dp.client.testUtils

import cats.effect.IO

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import javax.xml.validation.Validator
import scala.xml.InputSource

class ValidateXmlAgainstXsd(pathOfSchemaFile: String):
  def xmlStringIsValid(xmlStringToValidate: String): IO[Boolean] =
    for {
      documentBuilderFactory <- IO {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance
        documentBuilderFactory.setNamespaceAware(true)
        documentBuilderFactory
      }
      documentBuilder <- IO(documentBuilderFactory.newDocumentBuilder)
      xmlDocument <- IO(documentBuilder.parse(new InputSource(new StringReader(xmlStringToValidate))))
      schemaFactory <- IO(SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI))
      streamSource <- IO(new StreamSource(getClass.getResourceAsStream(pathOfSchemaFile)))
      schema <- IO(schemaFactory.newSchema(streamSource))
      validator: Validator <- IO.pure(schema.newValidator)

      isXmlFileValid <- IO(validator.validate(new DOMSource(xmlDocument))).map(_ => true)

    } yield isXmlFileValid

object ValidateXmlAgainstXsd {
  val xipXsdSchemaV6 = "/XIP-V6.xsd"
  def apply(pathOfSchemaFile: String) = new ValidateXmlAgainstXsd(pathOfSchemaFile: String)
}
