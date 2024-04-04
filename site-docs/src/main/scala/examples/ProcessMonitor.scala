package examples

object ProcessMonitor {
  // #fs2
  object ProcessMonitorFs2 {
    import cats.effect.IO
    import uk.gov.nationalarchives.dp.client.ProcessMonitorClient.*
    import uk.gov.nationalarchives.dp.client.ProcessMonitorClient.MonitorsStatus.*
    import uk.gov.nationalarchives.dp.client.ProcessMonitorClient.MonitorCategory.*
    import uk.gov.nationalarchives.dp.client.fs2.Fs2Client

    val url = "https://test.preservica.com"
    val monitorsRequestWithStatusAnd2Categories: GetMonitorsRequest =
      GetMonitorsRequest(List(Succeeded), None, List(Ingest, Export))
    val monitorsRequestWith2Statuses: GetMonitorsRequest = GetMonitorsRequest(List(Pending, Running), None, Nil)
    val monitorsRequestWithName: GetMonitorsRequest =
      GetMonitorsRequest(
        Nil,
        Some("opex/6d21f958-6344-491b-aef7-cff0dfb63c19-a7aff8a6-04cc-4f4e-b872-6bcf7dc21f9f"),
        Nil
      )
    val monitorsWithNoParameters: GetMonitorsRequest = GetMonitorsRequest(Nil, None, Nil)

    def searchEntities(): IO[Unit] = {
      for {
        client <- Fs2Client.processMonitorClient(url, "secretName")
        _ <- client.getMonitors(monitorsRequestWithStatusAnd2Categories)
        _ <- client.getMonitors(monitorsRequestWith2Statuses)
        _ <- client.getMonitors(monitorsRequestWithName)
        _ <- client.getMonitors(monitorsWithNoParameters)
      } yield ()
    }
  }
  // #fs2
}
