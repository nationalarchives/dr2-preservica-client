package uk.gov.nationalarchives.dp.client

import cats.Monad
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor2}
import uk.gov.nationalarchives.dp.client.ValidateXmlAgainstXsd.PreservicaSchema.*

import scala.xml.SAXParseException

abstract class ValidateXmlAgainstXsdTest[F[_]: Monad] extends AnyFlatSpec with TableDrivenPropertyChecks:
  private def xipXmlValidator = ValidateXmlAgainstXsd(XipXsdSchemaV7)
  private def opexXmlValidator = ValidateXmlAgainstXsd(OpexMetadataSchema)

  def valueFromF[T](value: F[T]): T

  extension [T](result: F[T]) def run(): T = valueFromF(result)

  "xmlStringIsValid" should s"return 'true' if the XIP xml string that was passed in was valid" in {
    val xmlToValidate = <XIP xmlns="http://preservica.com/XIP/v7.7">
      <InformationObject>
        <Ref>7cea2ce0-f7da-4132-bbfa-7fc92f3fd4d7</Ref>
        <Title>A Test Title</Title>
        <Description></Description>
        <SecurityTag>unknown</SecurityTag>
        <Parent>3fbc5b0e-c5d7-42a4-8a49-5ffad4cae761</Parent>
      </InformationObject>
    </XIP>

    val xmlIsValidResult = xipXmlValidator.xmlStringIsValid(xmlToValidate.toString).run()
    xmlIsValidResult should be(true)
  }

  "xmlStringIsValid" should s"throw a 'SAXParseException' if the XIP xml string that was passed in, did not have a namespace" in {
    val xmlToValidate = <XIP></XIP>

    val error = intercept[SAXParseException] {
      valueFromF(xipXmlValidator.xmlStringIsValid(xmlToValidate.toString))
    }
    error.getMessage should be("cvc-elt.1.a: Cannot find the declaration of element 'XIP'.")
  }

  "xmlStringIsValid" should s"throw a 'SAXParseException' if the XIP xml string that was passed in, has an unexpected namespace" in {
    val xmlToValidate = <XIP xmlns="http://unexpectedNamespace.come/v404"></XIP>

    val error = intercept[SAXParseException] {
      xipXmlValidator.xmlStringIsValid(xmlToValidate.toString).run()
    }
    error.getMessage should be("cvc-elt.1.a: Cannot find the declaration of element 'XIP'.")
  }

  "xmlStringIsValid" should s"throw a 'SAXParseException' if the XIP xml string that was passed in, contains an unexpected element" in {
    val xmlToValidate = <XIP xmlns="http://preservica.com/XIP/v7.7">
      <InformationObject>
        <UnexpectedElement>7cea2ce0-f7da-4132-bbfa-7fc92f3fd4d7</UnexpectedElement>
        <Title>A Test Title</Title>
        <Description></Description>
        <SecurityTag>unknown</SecurityTag>
        <Parent>3fbc5b0e-c5d7-42a4-8a49-5ffad4cae761</Parent>
      </InformationObject>
    </XIP>

    val error = intercept[SAXParseException] {
      xipXmlValidator.xmlStringIsValid(xmlToValidate.toString).run()
    }

    error.getMessage should be(
      "cvc-complex-type.2.4.a: Invalid content was found starting with element " +
        """'{"http://preservica.com/XIP/v7.7":UnexpectedElement}'. One of '{"http://preservica.com/XIP/v7.7":Ref, """ +
        """"http://preservica.com/XIP/v7.7":Title, "http://preservica.com/XIP/v7.7":Description, "http://preservica.com/XIP/v7.7":SecurityTag, """ +
        """"http://preservica.com/XIP/v7.7":CustomType, "http://preservica.com/XIP/v7.7":Parent}' is expected."""
    )
  }

  "xmlStringIsValid" should s"throw a 'SAXParseException' if the XIP xml string that was passed in, is missing an expected element" in {
    val xmlToValidate = <XIP xmlns="http://preservica.com/XIP/v7.7">
      <InformationObject>
        <Title>A Test Title</Title>
        <Description></Description>
        <SecurityTag>unknown</SecurityTag>
        <Parent>3fbc5b0e-c5d7-42a4-8a49-5ffad4cae761</Parent>
      </InformationObject>
    </XIP>

    val error = intercept[SAXParseException] {
      xipXmlValidator.xmlStringIsValid(xmlToValidate.toString).run()
    }

    error.getMessage should be(
      """cvc-complex-type.2.4.b: The content of element 'InformationObject' is not complete. """ +
        """One of '{"http://preservica.com/XIP/v7.7":Ref, "http://preservica.com/XIP/v7.7":CustomType}' is expected."""
    )
  }

  "xmlStringIsValid" should s"return 'true' if the OPEX xml string that was passed in was valid" in {
    val xmlToValidate = <opex:OPEXMetadata xmlns:opex="http://www.openpreservationexchange.org/opex/v1.2">
      <opex:Properties>
        <opex:Title>Test Name</opex:Title>
      </opex:Properties>
    </opex:OPEXMetadata>

    val xmlIsValidResult = opexXmlValidator.xmlStringIsValid(xmlToValidate.toString).run()
    xmlIsValidResult should be(true)
  }

  "xmlStringIsValid" should s"throw a 'SAXParseException' if the OPEX xml string that was passed in, did not have a namespace" in {
    val xmlToValidate = <opex:OPEXMetadata></opex:OPEXMetadata>

    val error = intercept[SAXParseException] {
      valueFromF(opexXmlValidator.xmlStringIsValid(xmlToValidate.toString))
    }
    error.getMessage should be("The prefix \"opex\" for element \"opex:OPEXMetadata\" is not bound.")
  }

  "xmlStringIsValid" should s"throw a 'SAXParseException' if the OPEX xml string that was passed in, has an unexpected namespace" in {
    val xmlToValidate = <opex:OPEXMetadata xmlns="http://unexpectedNamespace.come/v404"></opex:OPEXMetadata>

    val error = intercept[SAXParseException] {
      opexXmlValidator.xmlStringIsValid(xmlToValidate.toString).run()
    }
    error.getMessage should be("The prefix \"opex\" for element \"opex:OPEXMetadata\" is not bound.")
  }

  "xmlStringIsValid" should s"throw a 'SAXParseException' if the OPEX xml string that was passed in, contains an unexpected element" in {
    val xmlToValidate = <opex:OPEXMetadata xmlns:opex="http://www.openpreservationexchange.org/opex/v1.2">
      <opex:Properties>
        <UnexpectedElement>7cea2ce0-f7da-4132-bbfa-7fc92f3fd4d7</UnexpectedElement>
        <opex:Title>Test Name</opex:Title>
      </opex:Properties>
    </opex:OPEXMetadata>

    val error = intercept[SAXParseException] {
      opexXmlValidator.xmlStringIsValid(xmlToValidate.toString).run()
    }

    error.getMessage should be(
      """cvc-complex-type.2.4.a: Invalid content was found starting with element 'UnexpectedElement'.
        | One of '{"http://www.openpreservationexchange.org/opex/v1.2":Title,
        | "http://www.openpreservationexchange.org/opex/v1.2":Description,
        | "http://www.openpreservationexchange.org/opex/v1.2":SecurityDescriptor,
        | "http://www.openpreservationexchange.org/opex/v1.2":Identifiers}' is expected.""".stripMargin
        .replaceAll("\n", "")
    )
  }

  val validatorTable: TableFor2[String, ValidateXmlAgainstXsd[F]] = Table(
    ("name", "validator"),
    ("XIP", xipXmlValidator),
    ("OPEX", opexXmlValidator)
  )

  forAll(validatorTable) { (name, validator) =>
    "xmlStringIsValid" should s"throw a 'SAXParseException' if the $name xml string that was passed in, is missing a matching tag" in {
      val xmlWithMissingTagToValidate =
        """<XIP xmlns="http://preservica.com/XIP/v7.7">
          <InformationObject>
            <Title>A Test Title</Title>
            <Description></Description>
            <SecurityTag>unknown</SecurityTag>
            <Parent>3fbc5b0e-c5d7-42a4-8a49-5ffad4cae761</Parent>
        </XIP>"""

      val error = intercept[SAXParseException] {
        validator.xmlStringIsValid(xmlWithMissingTagToValidate).run()
      }

      error.getMessage should be(
        """The element type "InformationObject" must be terminated by the matching end-tag "</InformationObject>"."""
      )
    }

    "xmlStringIsValid" should s"throw a 'SAXParseException' if the $name xml string that was passed in was empty" in {
      val error = intercept[SAXParseException] {
        validator.xmlStringIsValid("").run()
      }
      error.getMessage should be("Premature end of file.")
    }
  }
