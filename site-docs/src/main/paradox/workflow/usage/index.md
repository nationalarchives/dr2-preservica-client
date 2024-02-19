# Workflow Client

The workflow client provides a method to start a workflow in Preservica

@@include[create-client.md](../../.includes/client/create-client.md)

The workflow client doesn't take a `Stream` type parameter as the search method doesn't need to stream. 

@@snip [Clients.scala](../../../scala/examples/Clients.scala) { #workflow_client }

@@include[method-heading.md](../../.includes/client/method-heading.md)

### startWorkflow
* Generates the start workflow input XML from the arguments.
* Sends this input XML to the start workflow endpoint of the API.

@@@ index

* [Fs2](fs2.md)

@@@
