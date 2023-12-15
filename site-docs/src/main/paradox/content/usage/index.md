# Content Client

The content client provides a method to search for entities in Preservica.

@@include[create-client.md](../../.includes/client/create-client.md)

The content client doesn't take a `Stream` type parameter as the search method doesn't need to stream. 

@@snip [Clients.scala](../../../scala/examples/Clients.scala) { #content_client }

@@include[method-heading.md](../../.includes/client/method-heading.md)

### searchEntities
* Convert the SearchQuery argument into a url with query parameters
* Queries the API to get the first 100 entities
* Checks to see if the objectIds in the response are empty. If they are, we have retrieved all entities. If not:
* Query the API to get the next 100 entities. Repeat until all entities are found. 
* Convert the responses to `Entity` case classes and return a `List` of them all.

@@@ index

* [Zio](zio.md)
* [Fs2](fs2.md)

@@@
