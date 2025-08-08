This is a Java command line tool to create a library to replace the broken
Azure SDK. 

The tool will read specifications from the folder
azure-rest-api-spects/specification and generate the necessary model files and
code to call the relevant REST API endpoint. 

The tool accepts a list of API calls to generate code for. E.g., specifying
`VirtualNetworkGateways_List` will find the endpoint at
azure-rest-api-spects/specification/network/resource-manager/Microsoft.Network/stable/2024-07-01/virtualNetworkGateway.json
and create models and code for this endpoint.

# Features

* Instead of relying on the Reactor async library, the generated code  is using
  the built Java 17 HTTP synchronous client. 

* The generated code parses Azure JSON responses using Jackson ObjectMapper. All
  models created from the HTTP response data will use immutable Java records.

* When the same model appears in multiple files and paths contain date, use the file 
  where the date is latest.

* Built with Gradle and requires Java 17+

# Misc

* To build and run the tool use gradle with two submodules - an 'app' submodule
  that contains the code and an 'sdk' submodule which will hold the generated
  code.  When building the sdk submodule, the generated jar will serve as the
  new Azure Simple SDK.

