# building

Before attempting to build this project, clone the Azure API specs:

    git clone git@github.com:Azure/azure-rest-api-specs.git

Then 

    ./gradlew :app:run

To build the sdk into the sdk/ folder.

To run the demo application in demo/, first modify demo/azure.properties to specify the 
credentials and subscription and then run:

    ./gradlew :demo:run

## HTTP Recording for Regression Tests

The Java HTTP client can record live Azure traffic and replay it later without issuing any network calls.  
Use the `HttpInteractionRecorder` and pass it to `AzureHttpClient`:

```java
Path recordings = Paths.get("recordings/network");
HttpInteractionRecorder recorder =
    new HttpInteractionRecorder(HttpInteractionRecorder.Mode.RECORD, recordings);
AzureHttpClient client = new AzureHttpClient(credentials, recorder);
```

Switch the recorder to `Mode.PLAYBACK` to reuse the captured responses. The client will look up the
responses based on method + URL + body and avoid issuing real HTTP requests, enabling fast and
repeatable regression tests.

### Demo CLI Shortcuts

The demo application can control the recorder from the command line:

- `--mode live` (default) – talk to Azure normally.
- `--mode record` – make real requests and store them under `--recordings-dir <path>`.
- `--mode play` – serve responses from the recordings directory without performing HTTP calls.

Examples:

```bash
./gradlew :demo:run --args="--mode record --recordings-dir recordings/demo/network"
./gradlew :demo:run --args="--mode play --recordings-dir recordings/demo/network" -Dstrict=true
```

### Suggested Regression Workflow

1. Pick a stable Azure subscription and run the demo (or your own harness) in record mode to capture all necessary endpoints. Keep the resulting `recordings/` directory under version control.
2. Redact or sanitize any secrets from the recorded JSON before committing.
3. In CI or local regression suites run the same command in `--mode play` (optionally with `-Dstrict=true`) to guarantee deterministic, offline verification of the SDK surface.
4. When the REST specs change, regenerate the SDK and re-run the recorder to refresh the fixtures; any diff in the recording files highlights behavior changes immediately.

### Recorded Regression Test

The repository contains a canned capture under `sdk/src/test/resources/recordings/record-78abb1c`.  
`RecordedPlaybackIntegrationTest` replays those files to validate the SDK end-to-end:

```bash
./gradlew :sdk:test --tests com.azure.simpleSDK.integration.RecordedPlaybackIntegrationTest
```

To refresh the fixtures, rerun the demo in record mode targeting the same directory:

```bash
./gradlew :demo:run --args="--mode record --recordings-dir sdk/src/test/resources/recordings/record-78abb1c"
```

Review and commit the updated JSON files so CI always exercises the latest recordings.
