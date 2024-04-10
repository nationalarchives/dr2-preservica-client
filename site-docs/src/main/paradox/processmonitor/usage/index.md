# Process Monitor Client

The process monitor client provides a method to get monitors and a method to get messages in Preservica

@@include[create-client.md](../../.includes/client/create-client.md)

The process monitor client doesn't take a `Stream` type parameter as the `getMonitors` method doesn't need to stream. 

@@snip [Clients.scala](../../../scala/examples/Clients.scala) { #process_monitor_client }

@@include[method-heading.md](../../.includes/client/method-heading.md)

### getMonitors
* Generates the monitors input query from the arguments.
* Sends this query in order to call the `monitors` endpoint of the API and get information on a particular monitor.

### getMessages
* Generates the generate input query from the arguments.
* Sends this query in order to call the `messages` endpoint of the API and get information on a particular message/messages.

@@@ index

* [Fs2](fs2.md)

@@@
