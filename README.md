# building

Before attempting to build this project, clone the Azure API specs:

    git clone git@github.com:Azure/azure-rest-api-specs.git

Then 

    ./gradlew :app:run

To build the sdk into the sdk/ folder.

To run the demo application in demo/, first modify demo/azure.properties to specify the 
credentials and subscription and then run:

    ./gradlew :demo:run
