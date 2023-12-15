## Creating a client

The client takes mandatory arguments for the url of the Preservica service and the secret name of the secrets manager secret storing the API credentials.

There is a caching layer which caches the secret value and the API token on disk. The duration of the cache defaults to 15 minutes but can be configured here.

It is also possible to configure the secrets manager endpoint. This can be used for testing or if using private endpoints in a VPC.

These examples use both the FS2 client and the ZIO client
