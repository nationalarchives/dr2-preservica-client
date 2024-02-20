# Admin Client

The admin client provides methods to update schemas, transfers, index definitions and metadata templates.

@@include[create-client.md](../../.includes/client/create-client.md)

The admin client doesn't take a `Stream` type parameter as the methods don't need to stream. 

@@snip [Clients.scala](../../../scala/examples/Clients.scala) { #admin_client }

@@include[method-heading.md](../../.includes/client/method-heading.md)

### addOrUpdateIndexDefinitions
* Generate query parameters based on the arguments
* Delete the existing schema document if present
* Add the new schema document

### addOrUpdateMetadataTemplates
* Generate query parameters based on the arguments
* Delete the existing schema document if present
* Add the new schema document

### addOrUpdateSchemas
* Generate query parameters based on the arguments
* Delete the existing schema document if present
* Add the new schema document

### addOrUpdateTransforms
* Generate query parameters based on the arguments
* Delete the existing schema document if present
* Add the new schema document


@@@ index

* [Fs2](fs2.md)

@@@
