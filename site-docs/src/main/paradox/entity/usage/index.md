# Entity Client

Methods to create, get and update entities in Preservica. 
The methods correspond to calls in the Preservica entity API.

@@include[create-client.md](../../.includes/client/create-client.md)

@@snip [Clients.scala](../../../scala/examples/Clients.scala) { #entity_client }
The client exposes 15 methods

```scala
  def metadataForEntity(entity: Entity): F[Seq[Elem]]

  def getBitstreamInfo(contentObjectRef: UUID): F[Seq[BitStreamInfo]]

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
      sinceDateTime: ZonedDateTime,
      startEntry: Int,
      maxEntries: Int = 1000,
      potentialEndDate: Option[ZonedDateTime] = None                          
  ): F[EntitiesUpdated]

  def entityEventActions(
      entity: Entity,
      startEntry: Int = 0,
      maxEntries: Int = 1000
  ): F[Seq[EventAction]]

  def entitiesPerIdentifier(identifiers: Seq[Identifier]): F[Map[Identifier, Seq[Entity]]]

  def getContentObjectsFromRepresentation(
      ioEntityRef: UUID,
      representationType: RepresentationType,
      repTypeIndex: Int
  ): F[Seq[Entity]]

  def addIdentifierForEntity(
      entityRef: UUID,
      entityType: EntityType,
      identifier: Identifier
  ): F[String]

  def getPreservicaNamespaceVersion(
      endpoint: String
  ): F[Float]

  def streamAllEntityRefs(
      repTypeFilter: Option[RepresentationType] = None
  ): F[List[EntityRef]]
```
@@include[method-heading.md](../../.includes/client/method-heading.md)

### metadataForEntity
* Call the API to get the `entityInfo` (XML) for the provided reference
* Call the API to get the `Identifiers` XML for the entity
  * Parse XML for the url to the next page of `Identifier` results
  * if there is a URL, return to step above and keep on until there are not more next page urls
  * Return the XML for all the `Identifiers` found
* Get the `Links` for the entity
  * Parse XML for the url to the next page of `Links` results
  * If there is a URL, return to step above and keep on until there are not more next page urls
  * Return the XML for all the `Links` found
* Use the `entityInfo` to get the fragment urls from `"AdditionalInformation" \ "Metadata" \ "Fragment"`
* For each fragment url, get the response
* Return a list of the XML elements found at `"MetadataContainer" \ "Content"` in each response
* Call the API to get the `EventActions` XML for the entity
    * Parse XML for the url to the next page of event action results
    * if there is a URL, return to step above and keep on until there are not more next page urls
    * Return the XML for all the event actions found
* Check for type of entity passed into this method
  * If `CO`
    * Call the API to get the `Generations` XML for the entity
      * Parse XML for the URLs to each `Generation`
      * Use the URLs to get all the `Generations` elements
    * Parse Generations XML for the URLs to each `Generation`'s bitstream urls
      * Use the URLs to get all the `Bitstream` elements
    * Return all metadata in a `CoMetadata` case class
  * If `IO`
    * Call the API to get the URLs of the `Representations`
      * Extract the `representationType` and `generationVersion` from each URL
      * For each group of `representationType` and `generationVersion`, pass them into the `ioRepresentations` method to get the XML for each representation
    * Return all metadata in an `IoMetadata` case class
  * If neither `IO`, nor `CO`
    * Return all metadata in an `StandardMetadata` case class

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

### getContentObjectsFromRepresentation

* Use the IO's ref, representationType and version in the endpoint's url
* Call the API to receive a RepresentationsResponse.
* Get all the Representation's Content Objects from `"Representation" \ "ContentObjects" \ "ContentObject"`
* Wrap Content Objects in an `Entity`

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
* Calls the `updated-since` endpoint using the parameters in the arguments. Has an optional end date which won't return requests newer than that date if provided.
* Returns an `EntitiesUpdated` which contains a `Seq[Entity]` with the updated entities and a boolean `hasNext` parameter which is true if there is another page.

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

### getPreservicaNamespaceVersion
* Calls any Entity Preservica endpoint that returns XML
* Returns version number found in namespace

### streamAllEntityRefs
* Calls root/children endpoint to get the root SOs
* For each SO, calls {entity-type}/{entity-ref}/children in order to get the children of those
* Continues to recursively collect SO and IO entities until it reaches the bottom

@@@ index

* [Fs2](fs2.md)

@@@
