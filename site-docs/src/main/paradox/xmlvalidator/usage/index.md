# XML Validator

The XML validator provides a method that validates XML against an XSD schema.

The validator's `apply` method takes a path (of type String) to an XSD schema as an argument.
* As well as the `apply` method, the validator's Companion `object` also provides paths to Preservica schemas (in the form af vals) that you can use as an argument to `apply`

### xmlStringIsValid
* Takes an XML string
* Validates the XML string against the schema (that was passed into the apply method)
* Returns `true` if the XML is valid or an `SAXParseException` if it's not

@@@ index

* [Fs2](fs2.md)

@@@
