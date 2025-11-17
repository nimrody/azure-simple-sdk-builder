package com.azure.simpleSDK.graph.client;

import com.azure.simpleSDK.graph.models.GraphAppRoleAssignment;
import com.azure.simpleSDK.graph.models.GraphServicePrincipal;
import com.azure.simpleSDK.http.auth.ServicePrincipalCredentials;
import com.azure.simpleSDK.http.exceptions.AzureAuthenticationException;
import com.azure.simpleSDK.http.exceptions.AzureException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal Microsoft Graph client used by the demo to resolve service principals and permissions.
 */
public class MicrosoftGraphClient {
    private static final String GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0";

    private final HttpClient httpClient;
    private final ServicePrincipalCredentials credentials;
    private final ObjectMapper objectMapper;

    public MicrosoftGraphClient(String clientId, String clientSecret, String tenantId) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.credentials = new ServicePrincipalCredentials(
            clientId,
            clientSecret,
            tenantId,
            "https://graph.microsoft.com/.default");
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Resolves a service principal by its application ID using Microsoft Graph.
     *
     * @param appId Azure AD application/client identifier.
     * @return The first matching service principal.
     * @throws AzureException if the lookup fails or no principal is found.
     */
    public GraphServicePrincipal resolveServicePrincipalByAppId(String appId) throws AzureException {
        if (appId == null || appId.isBlank()) {
            throw new AzureException("App ID is required to resolve a service principal.");
        }
        String filter = "appId eq '" + appId + "'";
        String encodedFilter = URLEncoder.encode(filter, StandardCharsets.UTF_8);
        String url = GRAPH_BASE_URL + "/servicePrincipals?$filter=" + encodedFilter + "&$top=1";

        JsonNode response = executeGraphRequest(url);
        JsonNode valueNode = response.path("value");
        if (!valueNode.isArray() || valueNode.isEmpty()) {
            throw new AzureException("Service principal not found for appId " + appId);
        }
        JsonNode principalNode = valueNode.get(0);
        return new GraphServicePrincipal(
            principalNode.path("id").asText(null),
            principalNode.path("appId").asText(null),
            principalNode.path("displayName").asText(null),
            principalNode.path("servicePrincipalType").asText(null)
        );
    }

    /**
     * Lists the application permissions assigned to the given service principal via Microsoft Graph.
     *
     * @param servicePrincipalId Object ID of the service principal.
     * @return List of app role assignments.
     * @throws AzureException if the Graph call fails.
     */
    public List<GraphAppRoleAssignment> listAvailablePermissions(String servicePrincipalId) throws AzureException {
        if (servicePrincipalId == null || servicePrincipalId.isBlank()) {
            return Collections.emptyList();
        }

        List<GraphAppRoleAssignment> assignments = new ArrayList<>();
        String nextLink = GRAPH_BASE_URL + "/servicePrincipals/" + servicePrincipalId + "/appRoleAssignments?$top=999";

        while (nextLink != null && !nextLink.isBlank()) {
            JsonNode response = executeGraphRequest(nextLink);
            JsonNode valueNode = response.path("value");
            if (valueNode.isArray()) {
                for (JsonNode node : valueNode) {
                    assignments.add(new GraphAppRoleAssignment(
                        node.path("id").asText(null),
                        node.path("resourceId").asText(null),
                        node.path("resourceDisplayName").asText(null),
                        node.path("appRoleId").asText(null),
                        node.path("principalId").asText(null),
                        node.path("principalDisplayName").asText(null)
                    ));
                }
            }
            JsonNode nextNode = response.get("@odata.nextLink");
            nextLink = nextNode != null && !nextNode.isNull() ? nextNode.asText(null) : null;
        }

        return assignments;
    }

    private JsonNode executeGraphRequest(String url) throws AzureException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + acquireToken())
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                String message = String.format("Microsoft Graph request failed (%d): %s",
                    response.statusCode(), response.body());
                throw new AzureException(message);
            }

            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new AzureException("Failed to parse Microsoft Graph response", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AzureException("Microsoft Graph request interrupted", e);
        }
    }

    private String acquireToken() throws AzureException {
        try {
            return credentials.getAccessToken();
        } catch (AzureAuthenticationException e) {
            throw new AzureException("Failed to acquire Microsoft Graph access token", e);
        }
    }
}
