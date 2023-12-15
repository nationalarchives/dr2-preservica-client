package uk.gov.nationalarchives.dp.client

/** Represents a generic XML schema document The specific schema documents are represented in the object
  */
trait FileInfo {
  def toQueryParams: Map[String, String]
  def xmlData: String
  def name: String
}

/** This contains implementations of the `FileInfo` trait
  */
object FileInfo {

  /** Represents an XML schema in the Preservica config
    * @param name
    *   The name of the schema
    * @param description
    *   The description of the schema
    * @param originalName
    *   The original name of the schema. This is used to delete the exising schema.
    * @param xmlData
    *   The data of the new schema.
    */
  case class SchemaFileInfo(
      name: String,
      description: String,
      originalName: String,
      xmlData: String
  ) extends FileInfo {
    def toQueryParams: Map[String, String] =
      Map("name" -> name, "description" -> description, "originalName" -> originalName)
  }

  /** Represents a Preservica transform.
    * @param name
    *   The name of the transform.
    * @param from
    *   What you are transforming from.
    * @param to
    *   What you are transforming to.
    * @param purpose
    *   The purpose of the transformation.
    * @param originalName
    *   The original name of the transformation.
    * @param xmlData
    *   The XML of the transform.
    */
  case class TransformFileInfo(
      name: String,
      from: String,
      to: String,
      purpose: String,
      originalName: String,
      xmlData: String
  ) extends FileInfo {
    def toQueryParams: Map[String, String] = Map(
      "name" -> name,
      "from" -> from,
      "to" -> to,
      "purpose" -> purpose,
      "originalName" -> originalName
    )
  }

  /** Represents an index definition in Preservica
    * @param name
    *   The name of the definition
    * @param xmlData
    *   The XML of the definition
    */
  case class IndexDefinitionInfo(name: String, xmlData: String) extends FileInfo {
    override def toQueryParams: Map[String, String] =
      Map("name" -> name, "type" -> "CustomIndexDefinition")
  }

  /** Represents a metadata template in Preservica
    * @param name
    *   The name of the metadata template
    * @param xmlData
    *   The XML of the metadata template
    */
  case class MetadataTemplateInfo(name: String, xmlData: String) extends FileInfo {
    override def toQueryParams: Map[String, String] =
      Map("name" -> name, "type" -> "MetadataTemplate")
  }
}
