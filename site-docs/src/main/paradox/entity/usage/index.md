# Entity Client

Methods to create, get and update entities in Preservica. 
The methods correspond to calls in the Preservica entity API.

@@include[create-client.md](../../.includes/client/create-client.md)

@@snip [Clients.scala](../../../scala/examples/Clients.scala) { #entity_client }
The client exposes 12 methods

```scala
  def metadataForEntity(entity: Entity): F[Seq[Elem]]

  def getBitstreamInfo(contentRef: UUID): F[Seq[BitStreamInfo]]

  def getEntity(entityRef: UUID, entityType: EntityType): F[Entity]

  def getEntityIdentifiers(entity: Entity): F[Seq[IdentifierResponse]]

  def getUrlsToIoRepresentations(ioEntityRef: UUID, representationType: Option[RepresentationType]): F[Seq[String]]

  def addEntity(addEntityRequest: AddEntityRequest): F[UUID]

  def updateEntity(updateEntityRequest: UpdateEntityRequest): F[String]

  def updateEntityIdentifiers(entity: Entity, identifiers: Seq[IdentifierResponse]): F[Seq[IdentifierResponse]]

  def streamBitstreamContent[T](
      stream: Streams[S]
  )(url: String, streamFn: stream.BinaryStream => F[T]): F[T]

  def entitiesUpdatedSince(
      dateTime: ZonedDateTime,
      startEntry: Int,
      maxEntries: Int = 1000
  ): F[Seq[Entity]]

  def entityEventActions(
      entity: Entity,
      startEntry: Int = 0,
      maxEntries: Int = 1000
  ): F[Seq[EventAction]]

  def entitiesByIdentifier(
      identifier: Identifier
  ): F[Seq[Entity]]

  def getUrlsToIoRepresentations(
      ioEntityRef: UUID,
      representationType: Option[RepresentationType]
  ): F[Seq[String]]

  def addIdentifierForEntity(
      entityRef: UUID,
      entityType: EntityType,
      identifier: Identifier
  ): F[String]
```
@@include[method-heading.md](../../.includes/client/method-heading.md)

### metadataForEntity
* Get the entity for the provided reference
* Get the fragment urls from `"AdditionalInformation" \ "Metadata" \ "Fragment"`
* For each fragment url, get the response
* Return a list of the XML elements found at `"MetadataContainer" \ "Content"` in each response

### getBitstreamInfo
* Get the entity for the provided reference
* Get the first generation url from `"AdditionalInformation" \ "Generations"`
* Call the API with this url
* Get all the generations from `"Generations" \ "Generation"`
* Send an API request for each of these urls
* Get the bitstream url from `"Bitstreams" \ "Bitstream"` for each of the responses
* Call the API with each of these bitstream urls.
* Get the file name, file size and download url for the bitstream from each response.

### getEntity
* Get the entity from the provided reference
* Parse the response into the `Entity` class

### getEntityIdentifiers
* Get the entity identifiers from the reference
* Call the API for each url returned in the previous step.
* Check for a next page. If there is one, return to step 2 until there is no next page, otherwise, return a `Seq[IdentifierResponse]`

### getUrlsToIoRepresentations

* Use the IO's ref in the endpoint's url
* Call the API to receive a RepresentationsResponse.
* Get all the representations from `"Representations" \ "Representation"`
* Filter the representations on the optional `RepresentationType`
* Retrieve url(s) from remaining representations

### addEntity
* Convert the `AddEntityRequest` case class into XML
* Send the XML to the API to create an entity.
* Parse the response and return the newly created reference UUID.

### updateEntity
* Convert the `UpdateEntityRequest` case class into XML
* Send the XML to the API to update an entity.
* Parse the response and return "Entity was updated"

### updateEntityIdentifiers
* Convert the `Seq[IdentifierResponse]` into XML
* Send the XML to the API to update the entity identifiers.
* Parse the response and return the original `identifiers` argument.

### streamBitstreamContent
* Calls the url from the first argument. 
* Passes a stream of the response to the function provided by the second argument.

### entitiesUpdatedSince
* Calls the `updated-since` endpoint using the parameters in the arguments.
* Converts the response to `Seq[Entity]` and returns

### entityEventActions
* Calls the `event-actions` endpoint using the parameters in the arguments.
* If there is a next page in the result, call the API until the last page is reached.
* Converts the response to `Seq[EventAction]` and returns

### entitiesByIdentifier
* Calls the `by-identifier` endpoint using the parameters in the arguments.
* The entities returned by this endpoint don't contain all the information we need. So for each entity returned, call the `/{ref}` endpoint

### addIdentifierForEntity
* Converts the arguments to the XML input
* Calls the `identifiers` endpoint to create an identifier for an entity

@@@ index

* [Zio](zio.md)
* [Fs2](fs2.md)

@@@
