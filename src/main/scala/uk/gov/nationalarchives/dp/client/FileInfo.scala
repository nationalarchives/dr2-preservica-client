package uk.gov.nationalarchives.dp.client

trait FileInfo {
  def toQueryParams: Map[String, String]
  def xmlData: String
  def name: String
}
object FileInfo {
  case class SchemaFileInfo(
      name: String,
      description: String,
      originalName: String,
      xmlData: String
  ) extends FileInfo {
    def toQueryParams: Map[String, String] =
      Map("name" -> name, "description" -> description, "originalName" -> originalName)
  }

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

  case class IndexDefinitionInfo(name: String, xmlData: String) extends FileInfo {
    override def toQueryParams: Map[String, String] =
      Map("name" -> name, "type" -> "CustomIndexDefinition")
  }

  case class MetadataTemplateInfo(name: String, xmlData: String) extends FileInfo {
    override def toQueryParams: Map[String, String] =
      Map("name" -> name, "type" -> "MetadataTemplate")
  }
}
