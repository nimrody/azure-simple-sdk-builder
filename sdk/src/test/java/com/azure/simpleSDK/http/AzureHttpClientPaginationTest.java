package com.azure.simpleSDK.http;

import com.azure.simpleSDK.http.auth.AzureCredentials;
import com.azure.simpleSDK.http.exceptions.AzureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AzureHttpClientPaginationTest {

    @Mock
    private AzureCredentials credentials;
    
    @Mock
    private HttpClient httpClient;
    
    @Mock
    private HttpResponse<String> httpResponse;
    
    @Mock
    private HttpResponse<String> secondPageResponse;
    
    @Mock
    private HttpResponse<String> thirdPageResponse;
    
    private AzureHttpClient azureHttpClient;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        when(credentials.getAccessToken()).thenReturn("test-token");
        objectMapper = new ObjectMapper();
        azureHttpClient = new AzureHttpClient(credentials);
        
        // Use reflection to replace the HttpClient in AzureHttpClient for testing
        var field = AzureHttpClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(azureHttpClient, httpClient);
    }

    @Test
    void testSinglePageResponse() throws Exception {
        // Arrange - Single page response with no nextLink
        String firstPageJson = """
            {
                "value": [
                    {"id": "item1", "name": "Test Item 1", "value": "value1"},
                    {"id": "item2", "name": "Test Item 2", "value": "value2"}
                ],
                "nextLink": null
            }
            """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(firstPageJson);
        when(httpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(httpResponse);

        // Act
        AzureRequest request = azureHttpClient.get("/test");
        AzureResponse<TestListResult> response = azureHttpClient.execute(request, TestListResult.class);

        // Assert
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().value().size());
        assertNull(response.getBody().nextLink());
        assertEquals("Test Item 1", response.getBody().value().get(0).name());
        assertEquals("Test Item 2", response.getBody().value().get(1).name());
        
        // Verify only one HTTP call was made
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void testMultiPagePagination() throws Exception {
        // Arrange - First page with nextLink
        String firstPageJson = """
            {
                "value": [
                    {"id": "item1", "name": "Page 1 Item 1", "value": "value1"},
                    {"id": "item2", "name": "Page 1 Item 2", "value": "value2"}
                ],
                "nextLink": "https://management.azure.com/test?$skiptoken=page2"
            }
            """;

        // Second page with nextLink
        String secondPageJson = """
            {
                "value": [
                    {"id": "item3", "name": "Page 2 Item 1", "value": "value3"},
                    {"id": "item4", "name": "Page 2 Item 2", "value": "value4"}
                ],
                "nextLink": "https://management.azure.com/test?$skiptoken=page3"
            }
            """;

        // Third page without nextLink (final page)
        String thirdPageJson = """
            {
                "value": [
                    {"id": "item5", "name": "Page 3 Item 1", "value": "value5"}
                ],
                "nextLink": null
            }
            """;

        // Mock HTTP responses
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(firstPageJson);
        when(httpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));

        when(secondPageResponse.statusCode()).thenReturn(200);
        when(secondPageResponse.body()).thenReturn(secondPageJson);
        when(secondPageResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));

        when(thirdPageResponse.statusCode()).thenReturn(200);
        when(thirdPageResponse.body()).thenReturn(thirdPageJson);
        when(thirdPageResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(httpResponse)  // First call
            .thenReturn(secondPageResponse)  // Second call
            .thenReturn(thirdPageResponse);  // Third call

        // Act
        AzureRequest request = azureHttpClient.get("/test");
        AzureResponse<TestListResult> response = azureHttpClient.execute(request, TestListResult.class);

        // Assert
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        
        // Should have combined all items from all 3 pages
        assertEquals(5, response.getBody().value().size());
        assertNull(response.getBody().nextLink()); // Combined response has null nextLink
        
        // Verify items from all pages are present
        List<TestItem> items = response.getBody().value();
        assertEquals("Page 1 Item 1", items.get(0).name());
        assertEquals("Page 1 Item 2", items.get(1).name());
        assertEquals("Page 2 Item 1", items.get(2).name());
        assertEquals("Page 2 Item 2", items.get(3).name());
        assertEquals("Page 3 Item 1", items.get(4).name());
        
        // Verify correct number of HTTP calls were made
        verify(httpClient, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void testPaginationWithErrorOnSecondPage() throws Exception {
        // Arrange - First page with nextLink
        String firstPageJson = """
            {
                "value": [
                    {"id": "item1", "name": "Page 1 Item 1", "value": "value1"},
                    {"id": "item2", "name": "Page 1 Item 2", "value": "value2"}
                ],
                "nextLink": "https://management.azure.com/test?$skiptoken=page2"
            }
            """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(firstPageJson);
        when(httpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));

        // Second page returns error
        when(secondPageResponse.statusCode()).thenReturn(500);
        when(secondPageResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(httpResponse)       // First call succeeds
            .thenReturn(secondPageResponse); // Second call fails

        // Act
        AzureRequest request = azureHttpClient.get("/test");
        AzureResponse<TestListResult> response = azureHttpClient.execute(request, TestListResult.class);

        // Assert - Should return first page data even if second page fails
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().value().size()); // Only first page items
        assertEquals("Page 1 Item 1", response.getBody().value().get(0).name());
        assertEquals("Page 1 Item 2", response.getBody().value().get(1).name());
        
        // Verify both HTTP calls were made
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void testNonPaginatedType() throws Exception {
        // Arrange - Test with a non-paginated type (String)
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("\"simple string response\"");
        when(httpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(httpResponse);

        // Act
        AzureRequest request = azureHttpClient.get("/test");
        AzureResponse<String> response = azureHttpClient.execute(request, String.class);

        // Assert - No pagination should occur for non-list types
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertEquals("simple string response", response.getBody());
        
        // Verify only one HTTP call was made
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void testEmptyNextLink() throws Exception {
        // Arrange - Response with empty nextLink
        String pageJson = """
            {
                "value": [
                    {"id": "item1", "name": "Test Item 1", "value": "value1"}
                ],
                "nextLink": ""
            }
            """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(pageJson);
        when(httpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(httpResponse);

        // Act
        AzureRequest request = azureHttpClient.get("/test");
        AzureResponse<TestListResult> response = azureHttpClient.execute(request, TestListResult.class);

        // Assert - Empty nextLink should be treated as no pagination
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().value().size());
        assertEquals("", response.getBody().nextLink()); // Original empty nextLink preserved
        
        // Verify only one HTTP call was made
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void testMaxPageLimit() throws Exception {
        // Arrange - Create a long chain of pages to test the 50 page limit
        String pageJson = """
            {
                "value": [
                    {"id": "item1", "name": "Test Item", "value": "value1"}
                ],
                "nextLink": "https://management.azure.com/test?$skiptoken=next"
            }
            """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(pageJson);
        when(httpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));

        // Mock all subsequent responses to also have nextLink (infinite pagination)
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(httpResponse);

        // Act
        AzureRequest request = azureHttpClient.get("/test");
        AzureResponse<TestListResult> response = azureHttpClient.execute(request, TestListResult.class);

        // Assert - Should stop at max page limit
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        
        // Should have items from exactly 50 pages (the limit)
        assertEquals(50, response.getBody().value().size());
        
        // Verify exactly 50 HTTP calls were made (initial + 49 pagination calls)
        verify(httpClient, times(50)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    /**
     * Test class without value() and nextLink() methods to verify non-paginated type detection
     */
    public static record NonPaginatedResult(String data) {}

    /**
     * Test models for single resource GET requests (not paginated)
     */
    public static record SingleResourceResult(
        String id,
        String name,
        String type,
        String location,
        ResourceProperties properties,
        Map<String, String> tags
    ) {}

    public static record ResourceProperties(
        String provisioningState,
        AddressSpace addressSpace,
        List<Subnet> subnets
    ) {}

    public static record AddressSpace(
        List<String> addressPrefixes
    ) {}

    public static record Subnet(
        String name,
        SubnetProperties properties
    ) {}

    public static record SubnetProperties(
        String addressPrefix
    ) {}

    @Test
    void testNonPaginatedResultType() throws Exception {
        // Arrange
        String responseJson = """
            {
                "data": "test data"
            }
            """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(responseJson);
        when(httpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(httpResponse);

        // Act
        AzureRequest request = azureHttpClient.get("/test");
        AzureResponse<NonPaginatedResult> response = azureHttpClient.execute(request, NonPaginatedResult.class);

        // Assert - Should not attempt pagination for types without required methods
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertEquals("test data", response.getBody().data());
        
        // Verify only one HTTP call was made
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void testSingleValueGetRequest() throws Exception {
        // Arrange - Test a typical Azure single resource GET response
        String resourceJson = """
            {
                "id": "/subscriptions/12345/resourceGroups/rg1/providers/Microsoft.Network/virtualNetworks/vnet1",
                "name": "vnet1",
                "type": "Microsoft.Network/virtualNetworks",
                "location": "eastus",
                "properties": {
                    "provisioningState": "Succeeded",
                    "addressSpace": {
                        "addressPrefixes": ["10.0.0.0/16"]
                    },
                    "subnets": [
                        {
                            "name": "default",
                            "properties": {
                                "addressPrefix": "10.0.1.0/24"
                            }
                        }
                    ]
                },
                "tags": {
                    "environment": "test"
                }
            }
            """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(resourceJson);
        when(httpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(httpResponse);

        // Act - GET single resource (not a list)
        AzureRequest request = azureHttpClient.get("/subscriptions/12345/resourceGroups/rg1/providers/Microsoft.Network/virtualNetworks/vnet1");
        AzureResponse<SingleResourceResult> response = azureHttpClient.execute(request, SingleResourceResult.class);

        // Assert - Single resource response should not trigger pagination
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        
        // Verify the single resource data
        SingleResourceResult resource = response.getBody();
        assertEquals("vnet1", resource.name());
        assertEquals("eastus", resource.location());
        assertEquals("Succeeded", resource.properties().provisioningState());
        assertEquals("10.0.0.0/16", resource.properties().addressSpace().addressPrefixes().get(0));
        assertEquals("test", resource.tags().get("environment"));
        
        // Verify exactly one HTTP call was made (no pagination)
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void testSingleValueGetRequestWithNullBody() throws Exception {
        // Arrange - Test GET request returning 204 No Content
        when(httpResponse.statusCode()).thenReturn(204);
        when(httpResponse.body()).thenReturn(null);
        when(httpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(httpResponse);

        // Act
        AzureRequest request = azureHttpClient.get("/test/resource");
        AzureResponse<SingleResourceResult> response = azureHttpClient.execute(request, SingleResourceResult.class);

        // Assert - Should handle null body gracefully
        assertNotNull(response);
        assertEquals(204, response.getStatusCode());
        assertNull(response.getBody());
        
        // Verify exactly one HTTP call was made
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void testSingleValueGetRequestWithEmptyBody() throws Exception {
        // Arrange - Test GET request returning empty body
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("");
        when(httpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(httpResponse);

        // Act
        AzureRequest request = azureHttpClient.get("/test/resource");
        AzureResponse<SingleResourceResult> response = azureHttpClient.execute(request, SingleResourceResult.class);

        // Assert - Should handle empty body gracefully
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertNull(response.getBody());
        
        // Verify exactly one HTTP call was made
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
