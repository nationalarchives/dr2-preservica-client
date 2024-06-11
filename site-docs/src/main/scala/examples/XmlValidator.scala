package examples

object XmlValidator {
  // #fs2
  object XmlValidatorFs2 {
    import cats.effect.IO
    import uk.gov.nationalarchives.dp.client.ValidateXmlAgainstXsd
    import uk.gov.nationalarchives.dp.client.ValidateXmlAgainstXsd.PreservicaSchema.XipXsdSchemaV7
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client

    val xmlValidator: ValidateXmlAgainstXsd[IO] = Fs2Client.xmlValidator(XipXsdSchemaV7) // a path to any schema can be passed in
    val xmlStringToValidate: String = <XIP xmlns="http://preservica.com/XIP/v6.9">
      <InformationObject>
        <Title>A Test Title</Title>
        <Description></Description>
        <SecurityTag>open</SecurityTag>
        <Parent>3fbc5b0e-c5d7-42a4-8a49-5ffad4cae761</Parent>
      </InformationObject>
    </XIP>.toString

    def checkIdXmlIsValid(): IO[Boolean] = for (isXmlValid <- xmlValidator.xmlStringIsValid(xmlStringToValidate)) yield isXmlValid
  }
  // #fs2
}
