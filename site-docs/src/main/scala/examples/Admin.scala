package examples

object Admin {
  // #fs2
  object AdminFs2 {
    import cats.effect.IO
    import uk.gov.nationalarchives.dp.client.FileInfo.{IndexDefinitionInfo, MetadataTemplateInfo, SchemaFileInfo, TransformFileInfo}
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client

    val url = "https://test.preservica.com"
    val name = "name"
    val xmlData = "<SomeXml></SomeXml>"

    def updatePreservicaXml(): IO[Unit] = {
      for {
        client <- Fs2Client.adminClient(url, "secretName")
        updateIndexDefinitions <- client.addOrUpdateIndexDefinitions(IndexDefinitionInfo(name, xmlData) :: Nil)
        updateMetadataTemplates <- client.addOrUpdateMetadataTemplates(MetadataTemplateInfo(name, xmlData) :: Nil)
        updateSchemas <- client.addOrUpdateSchemas(SchemaFileInfo(name, "description", "originalName", xmlData) :: Nil)
        updateTransforms <- client.addOrUpdateTransforms(TransformFileInfo(name, "from", "to", "purpose", "originalName", xmlData) :: Nil)
      } yield ()
    }
  }
  // #fs2
}
