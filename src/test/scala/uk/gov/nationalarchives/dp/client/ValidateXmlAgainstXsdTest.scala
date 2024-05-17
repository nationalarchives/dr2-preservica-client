package uk.gov.nationalarchives.dp.client

import cats.Monad
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import uk.gov.nationalarchives.dp.client.ValidateXmlAgainstXsd.PreservicaSchema
import uk.gov.nationalarchives.dp.client.ValidateXmlAgainstXsd.PreservicaSchema.XipXsdSchemaV6

import scala.xml.SAXParseException

abstract class ValidateXmlAgainstXsdTest[F[_]: Monad] extends AnyFlatSpec:
  private def xmlValidator = ValidateXmlAgainstXsd(XipXsdSchemaV6)

  def valueFromF[T](value: F[T]): T

  extension [T](result: F[T]) def run(): T = valueFromF(result)

  "xmlStringIsValid" should s"return 'true' if the xml string that was passed in was valid" in {
    val xmlToValidate = <XIP xmlns="http://preservica.com/XIP/v6.9">
      <InformationObject>
        <Ref>7cea2ce0-f7da-4132-bbfa-7fc92f3fd4d7</Ref>
        <Title>A Test Title</Title>
        <Description></Description>
        <SecurityTag>open</SecurityTag>
        <Parent>3fbc5b0e-c5d7-42a4-8a49-5ffad4cae761</Parent>
      </InformationObject>
    </XIP>

    val xmlIsValidResult = xmlValidator.xmlStringIsValid(xmlToValidate.toString).run()
    xmlIsValidResult should be(true)
  }

  "xmlStringIsValid" should s"throw a 'SAXParseException' if the xml string that was passed in, did not have a namespace" in {
    val xmlToValidate = <XIP></XIP>

    val error = intercept[SAXParseException] {
      valueFromF(xmlValidator.xmlStringIsValid(xmlToValidate.toString))
    }
    error.getMessage should be("cvc-elt.1.a: Cannot find the declaration of element 'XIP'.")
  }

  "xmlStringIsValid" should s"throw a 'SAXParseException' if the xml string that was passed in, has an unexpected namespace" in {
    val xmlToValidate = <XIP xmlns="http://unexpectedNamespace.come/v404"></XIP>

    val error = intercept[SAXParseException] {
      xmlValidator.xmlStringIsValid(xmlToValidate.toString).run()
    }
    error.getMessage should be("cvc-elt.1.a: Cannot find the declaration of element 'XIP'.")
  }

  "xmlStringIsValid" should s"throw a 'SAXParseException' if the xml string that was passed in, contains an unexpected element" in {
    val xmlToValidate = <XIP xmlns="http://preservica.com/XIP/v6.9">
      <InformationObject>
        <UnexpectedElement>7cea2ce0-f7da-4132-bbfa-7fc92f3fd4d7</UnexpectedElement>
        <Title>A Test Title</Title>
        <Description></Description>
        <SecurityTag>open</SecurityTag>
        <Parent>3fbc5b0e-c5d7-42a4-8a49-5ffad4cae761</Parent>
      </InformationObject>
    </XIP>

    val error = intercept[SAXParseException] {
      xmlValidator.xmlStringIsValid(xmlToValidate.toString).run()
    }

    error.getMessage should be(
      "cvc-complex-type.2.4.a: Invalid content was found starting with element " +
        """'{"http://preservica.com/XIP/v6.9":UnexpectedElement}'. One of '{"http://preservica.com/XIP/v6.9":Ref, """ +
        """"http://preservica.com/XIP/v6.9":Title, "http://preservica.com/XIP/v6.9":Description, "http://preservica.com/XIP/v6.9":SecurityTag, """ +
        """"http://preservica.com/XIP/v6.9":CustomType, "http://preservica.com/XIP/v6.9":Parent}' is expected."""
    )
  }

  "xmlStringIsValid" should s"throw a 'SAXParseException' if the xml string that was passed in, is missing an expected element" in {
    val xmlToValidate = <XIP xmlns="http://preservica.com/XIP/v6.9">
      <InformationObject>
        <Title>A Test Title</Title>
        <Description></Description>
        <SecurityTag>open</SecurityTag>
        <Parent>3fbc5b0e-c5d7-42a4-8a49-5ffad4cae761</Parent>
      </InformationObject>
    </XIP>

    val error = intercept[SAXParseException] {
      xmlValidator.xmlStringIsValid(xmlToValidate.toString).run()
    }

    error.getMessage should be(
      """cvc-complex-type.2.4.b: The content of element 'InformationObject' is not complete. """ +
        """One of '{"http://preservica.com/XIP/v6.9":Ref, "http://preservica.com/XIP/v6.9":CustomType}' is expected."""
    )
  }

  "xmlStringIsValid" should s"throw a 'SAXParseException' if the xml string that was passed in, is missing a matching tag" in {
    val xmlWithMissingTagToValidate = """<XIP xmlns="http://preservica.com/XIP/v6.9">
      <InformationObject>
        <Title>A Test Title</Title>
        <Description></Description>
        <SecurityTag>open</SecurityTag>
        <Parent>3fbc5b0e-c5d7-42a4-8a49-5ffad4cae761</Parent>
    </XIP>"""

    val error = intercept[SAXParseException] {
      xmlValidator.xmlStringIsValid(xmlWithMissingTagToValidate).run()
    }

    error.getMessage should be(
      """The element type "InformationObject" must be terminated by the matching end-tag "</InformationObject>"."""
    )
  }

  "xmlStringIsValid" should s"throw a 'SAXParseException' if the xml string that was passed in was empty" in {
    val error = intercept[SAXParseException] {
      xmlValidator.xmlStringIsValid("").run()
    }
    error.getMessage should be("Premature end of file.")
  }
