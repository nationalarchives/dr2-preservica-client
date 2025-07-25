package examples

object Content {
  // #fs2
  object ContentFs2 {
    import cats.effect.IO
    import uk.gov.nationalarchives.dp.client.ContentClient.{SearchField, SearchQuery}
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client

    val url = "https://test.preservica.com"
    val queryString = """{"q":"","fields":[{"name":"xip.title","values":["test-title"]}]}"""
    val searchQuery: SearchQuery = SearchQuery(queryString, SearchField("test1", List("value1")) :: Nil)

    def searchEntities(): IO[Unit] = {
      for {
        client <- Fs2Client.contentClient("secretName")
        searchResultAllResults <- client.searchEntities(searchQuery)
        searchResultWithMax <- client.searchEntities(searchQuery, 1000)
      } yield ()
    }
  }
  // #fs2
}
