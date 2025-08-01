/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.MockMcpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.PaginatedRequest;
import io.modelcontextprotocol.spec.McpSchema.Root;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.spec.McpSchema.METHOD_INITIALIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpAsyncClientResponseHandlerTests {

	private static final McpSchema.Implementation SERVER_INFO = new McpSchema.Implementation("test-server", "1.0.0");

	private static final McpSchema.ServerCapabilities SERVER_CAPABILITIES = McpSchema.ServerCapabilities.builder()
		.tools(true)
		.resources(true, true) // Enable both resources and resource templates
		.build();

	private static MockMcpClientTransport initializationEnabledTransport() {
		return initializationEnabledTransport(SERVER_CAPABILITIES, SERVER_INFO);
	}

	private static MockMcpClientTransport initializationEnabledTransport(
			McpSchema.ServerCapabilities mockServerCapabilities, McpSchema.Implementation mockServerInfo) {
		McpSchema.InitializeResult mockInitResult = new McpSchema.InitializeResult(McpSchema.LATEST_PROTOCOL_VERSION,
				mockServerCapabilities, mockServerInfo, "Test instructions");

		return new MockMcpClientTransport((t, message) -> {
			if (message instanceof McpSchema.JSONRPCRequest r && METHOD_INITIALIZE.equals(r.method())) {
				McpSchema.JSONRPCResponse initResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
						r.id(), mockInitResult, null);
				t.simulateIncomingMessage(initResponse);
			}
		}).withProtocolVersion(McpSchema.LATEST_PROTOCOL_VERSION);
	}

	@Test
	void testSuccessfulInitialization() {
		McpSchema.Implementation serverInfo = new McpSchema.Implementation("mcp-test-server", "0.0.1");
		McpSchema.ServerCapabilities serverCapabilities = McpSchema.ServerCapabilities.builder()
			.tools(false)
			.resources(true, true) // Enable both resources and resource templates
			.build();
		MockMcpClientTransport transport = initializationEnabledTransport(serverCapabilities, serverInfo);
		McpAsyncClient asyncMcpClient = McpClient.async(transport).build();

		// Verify client is not initialized initially
		assertThat(asyncMcpClient.isInitialized()).isFalse();

		// Start initialization with reactive handling
		InitializeResult result = asyncMcpClient.initialize().block();

		// Verify initialized notification was sent
		McpSchema.JSONRPCMessage notificationMessage = transport.getLastSentMessage();
		assertThat(notificationMessage).isInstanceOf(McpSchema.JSONRPCNotification.class);
		McpSchema.JSONRPCNotification notification = (McpSchema.JSONRPCNotification) notificationMessage;
		assertThat(notification.method()).isEqualTo(McpSchema.METHOD_NOTIFICATION_INITIALIZED);

		// Verify initialization result
		assertThat(result).isNotNull();
		assertThat(result.protocolVersion()).isEqualTo(transport.protocolVersion());
		assertThat(result.capabilities()).isEqualTo(serverCapabilities);
		assertThat(result.serverInfo()).isEqualTo(serverInfo);
		assertThat(result.instructions()).isEqualTo("Test instructions");

		// Verify client state after initialization
		assertThat(asyncMcpClient.isInitialized()).isTrue();
		assertThat(asyncMcpClient.getServerCapabilities()).isEqualTo(serverCapabilities);
		assertThat(asyncMcpClient.getServerInfo()).isEqualTo(serverInfo);

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testToolsChangeNotificationHandling() throws JsonProcessingException {
		MockMcpClientTransport transport = initializationEnabledTransport();

		// Create a list to store received tools for verification
		List<McpSchema.Tool> receivedTools = new ArrayList<>();

		// Create a consumer that will be called when tools change
		Function<List<McpSchema.Tool>, Mono<Void>> toolsChangeConsumer = tools -> Mono
			.fromRunnable(() -> receivedTools.addAll(tools));

		// Create client with tools change consumer
		McpAsyncClient asyncMcpClient = McpClient.async(transport).toolsChangeConsumer(toolsChangeConsumer).build();

		assertThat(asyncMcpClient.initialize().block()).isNotNull();

		// Create a mock tools list that the server will return
		Map<String, Object> inputSchema = Map.of("type", "object", "properties", Map.of(), "required", List.of());
		McpSchema.Tool mockTool = new McpSchema.Tool("test-tool-1", "Test Tool 1 Description",
				new ObjectMapper().writeValueAsString(inputSchema));

		// Create page 1 response with nextPageToken
		String nextPageToken = "page2Token";
		McpSchema.ListToolsResult mockToolsResult1 = new McpSchema.ListToolsResult(List.of(mockTool), nextPageToken);

		// Simulate server sending tools/list_changed notification
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, null);
		transport.simulateIncomingMessage(notification);

		// Simulate server response to first tools/list request
		McpSchema.JSONRPCRequest toolsListRequest1 = transport.getLastSentMessageAsRequest();
		assertThat(toolsListRequest1.method()).isEqualTo(McpSchema.METHOD_TOOLS_LIST);

		McpSchema.JSONRPCResponse toolsListResponse1 = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
				toolsListRequest1.id(), mockToolsResult1, null);
		transport.simulateIncomingMessage(toolsListResponse1);

		// Create mock tools for page 2
		McpSchema.Tool mockTool2 = new McpSchema.Tool("test-tool-2", "Test Tool 2 Description",
				new ObjectMapper().writeValueAsString(inputSchema));

		// Create page 2 response with no nextPageToken (last page)
		McpSchema.ListToolsResult mockToolsResult2 = new McpSchema.ListToolsResult(List.of(mockTool2), null);

		// Simulate server response to second tools/list request with page token
		McpSchema.JSONRPCRequest toolsListRequest2 = transport.getLastSentMessageAsRequest();
		assertThat(toolsListRequest2.method()).isEqualTo(McpSchema.METHOD_TOOLS_LIST);

		// Verify the page token was included in the request
		PaginatedRequest params = (PaginatedRequest) toolsListRequest2.params();
		assertThat(params).isNotNull();
		assertThat(params.cursor()).isEqualTo(nextPageToken);

		McpSchema.JSONRPCResponse toolsListResponse2 = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
				toolsListRequest2.id(), mockToolsResult2, null);
		transport.simulateIncomingMessage(toolsListResponse2);

		// Verify the consumer received all expected tools from both pages
		assertThat(receivedTools).hasSize(2);
		assertThat(receivedTools.get(0).name()).isEqualTo("test-tool-1");
		assertThat(receivedTools.get(0).description()).isEqualTo("Test Tool 1 Description");
		assertThat(receivedTools.get(1).name()).isEqualTo("test-tool-2");
		assertThat(receivedTools.get(1).description()).isEqualTo("Test Tool 2 Description");

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testRootsListRequestHandling() {
		MockMcpClientTransport transport = initializationEnabledTransport();

		McpAsyncClient asyncMcpClient = McpClient.async(transport)
			.roots(new Root("file:///test/path", "test-root"))
			.build();

		assertThat(asyncMcpClient.initialize().block()).isNotNull();

		// Simulate incoming request
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_ROOTS_LIST, "test-id", null);
		transport.simulateIncomingMessage(request);

		// Verify response
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.id()).isEqualTo("test-id");
		assertThat(response.result())
			.isEqualTo(new McpSchema.ListRootsResult(List.of(new Root("file:///test/path", "test-root"))));
		assertThat(response.error()).isNull();

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testResourcesChangeNotificationHandling() {
		MockMcpClientTransport transport = initializationEnabledTransport();

		// Create a list to store received resources for verification
		List<McpSchema.Resource> receivedResources = new ArrayList<>();

		// Create a consumer that will be called when resources change
		Function<List<McpSchema.Resource>, Mono<Void>> resourcesChangeConsumer = resources -> Mono
			.fromRunnable(() -> receivedResources.addAll(resources));

		// Create client with resources change consumer
		McpAsyncClient asyncMcpClient = McpClient.async(transport)
			.resourcesChangeConsumer(resourcesChangeConsumer)
			.build();

		assertThat(asyncMcpClient.initialize().block()).isNotNull();

		// Create a mock resources list that the server will return
		McpSchema.Resource mockResource = new McpSchema.Resource("test://resource", "Test Resource", "A test resource",
				"text/plain", null);
		McpSchema.ListResourcesResult mockResourcesResult = new McpSchema.ListResourcesResult(List.of(mockResource),
				null);

		// Simulate server sending resources/list_changed notification
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED, null);
		transport.simulateIncomingMessage(notification);

		// Simulate server response to resources/list request
		McpSchema.JSONRPCRequest resourcesListRequest = transport.getLastSentMessageAsRequest();
		assertThat(resourcesListRequest.method()).isEqualTo(McpSchema.METHOD_RESOURCES_LIST);

		McpSchema.JSONRPCResponse resourcesListResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
				resourcesListRequest.id(), mockResourcesResult, null);
		transport.simulateIncomingMessage(resourcesListResponse);

		// Verify the consumer received the expected resources
		assertThat(receivedResources).hasSize(1);
		assertThat(receivedResources.get(0).uri()).isEqualTo("test://resource");
		assertThat(receivedResources.get(0).name()).isEqualTo("Test Resource");
		assertThat(receivedResources.get(0).description()).isEqualTo("A test resource");

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testPromptsChangeNotificationHandling() {
		MockMcpClientTransport transport = initializationEnabledTransport();

		// Create a list to store received prompts for verification
		List<McpSchema.Prompt> receivedPrompts = new ArrayList<>();

		// Create a consumer that will be called when prompts change
		Function<List<McpSchema.Prompt>, Mono<Void>> promptsChangeConsumer = prompts -> Mono
			.fromRunnable(() -> receivedPrompts.addAll(prompts));

		// Create client with prompts change consumer
		McpAsyncClient asyncMcpClient = McpClient.async(transport).promptsChangeConsumer(promptsChangeConsumer).build();

		assertThat(asyncMcpClient.initialize().block()).isNotNull();

		// Create a mock prompts list that the server will return
		McpSchema.Prompt mockPrompt = new McpSchema.Prompt("test-prompt", "Test Prompt", "Test Prompt Description",
				List.of(new McpSchema.PromptArgument("arg1", "Test argument", "Test argument", true)));
		McpSchema.ListPromptsResult mockPromptsResult = new McpSchema.ListPromptsResult(List.of(mockPrompt), null);

		// Simulate server sending prompts/list_changed notification
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, null);
		transport.simulateIncomingMessage(notification);

		// Simulate server response to prompts/list request
		McpSchema.JSONRPCRequest promptsListRequest = transport.getLastSentMessageAsRequest();
		assertThat(promptsListRequest.method()).isEqualTo(McpSchema.METHOD_PROMPT_LIST);

		McpSchema.JSONRPCResponse promptsListResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
				promptsListRequest.id(), mockPromptsResult, null);
		transport.simulateIncomingMessage(promptsListResponse);

		// Verify the consumer received the expected prompts
		assertThat(receivedPrompts).hasSize(1);
		assertThat(receivedPrompts.get(0).name()).isEqualTo("test-prompt");
		assertThat(receivedPrompts.get(0).description()).isEqualTo("Test Prompt Description");
		assertThat(receivedPrompts.get(0).arguments()).hasSize(1);
		assertThat(receivedPrompts.get(0).arguments().get(0).name()).isEqualTo("arg1");

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testSamplingCreateMessageRequestHandling() {
		MockMcpClientTransport transport = initializationEnabledTransport();

		// Create a test sampling handler that echoes back the input
		Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler = request -> {
			var content = request.messages().get(0).content();
			return Mono.just(new McpSchema.CreateMessageResult(McpSchema.Role.ASSISTANT, content, "test-model",
					McpSchema.CreateMessageResult.StopReason.END_TURN));
		};

		// Create client with sampling capability and handler
		McpAsyncClient asyncMcpClient = McpClient.async(transport)
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build();

		assertThat(asyncMcpClient.initialize().block()).isNotNull();

		// Create a mock create message request
		var messageRequest = new McpSchema.CreateMessageRequest(
				List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Test message"))),
				null, // modelPreferences
				"Test system prompt", McpSchema.CreateMessageRequest.ContextInclusionStrategy.NONE, 0.7, // temperature
				100, // maxTokens
				null, // stopSequences
				null // metadata
		);

		// Simulate incoming request
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, "test-id", messageRequest);
		transport.simulateIncomingMessage(request);

		// Verify response
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.id()).isEqualTo("test-id");
		assertThat(response.error()).isNull();

		McpSchema.CreateMessageResult result = transport.unmarshalFrom(response.result(),
				new TypeReference<McpSchema.CreateMessageResult>() {
				});
		assertThat(result).isNotNull();
		assertThat(result.role()).isEqualTo(McpSchema.Role.ASSISTANT);
		assertThat(result.content()).isNotNull();
		assertThat(result.model()).isEqualTo("test-model");
		assertThat(result.stopReason()).isEqualTo(McpSchema.CreateMessageResult.StopReason.END_TURN);

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testSamplingCreateMessageRequestHandlingWithoutCapability() {
		MockMcpClientTransport transport = initializationEnabledTransport();

		// Create client without sampling capability
		McpAsyncClient asyncMcpClient = McpClient.async(transport)
			.capabilities(ClientCapabilities.builder().build()) // No sampling capability
			.build();

		assertThat(asyncMcpClient.initialize().block()).isNotNull();

		// Create a mock create message request
		var messageRequest = new McpSchema.CreateMessageRequest(
				List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Test message"))),
				null, null, null, null, 0, null, null);

		// Simulate incoming request
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, "test-id", messageRequest);
		transport.simulateIncomingMessage(request);

		// Verify error response
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.id()).isEqualTo("test-id");
		assertThat(response.result()).isNull();
		assertThat(response.error()).isNotNull();
		assertThat(response.error().message()).contains("Method not found: sampling/createMessage");

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testSamplingCreateMessageRequestHandlingWithNullHandler() {
		MockMcpClientTransport transport = new MockMcpClientTransport();

		// Create client with sampling capability but null handler
		assertThatThrownBy(
				() -> McpClient.async(transport).capabilities(ClientCapabilities.builder().sampling().build()).build())
			.isInstanceOf(McpError.class)
			.hasMessage("Sampling handler must not be null when client capabilities include sampling");
	}

	@Test
	@SuppressWarnings("unchecked")
	void testElicitationCreateRequestHandling() {
		MockMcpClientTransport transport = initializationEnabledTransport();

		// Create a test elicitation handler that echoes back the input
		Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler = request -> {
			assertThat(request.message()).isNotEmpty();
			assertThat(request.requestedSchema()).isInstanceOf(Map.class);
			assertThat(request.requestedSchema().get("type")).isEqualTo("object");

			var properties = request.requestedSchema().get("properties");
			assertThat(properties).isNotNull();
			assertThat(((Map<String, Object>) properties).get("message")).isInstanceOf(Map.class);

			return Mono.just(McpSchema.ElicitResult.builder()
				.message(McpSchema.ElicitResult.Action.ACCEPT)
				.content(Map.of("message", request.message()))
				.build());
		};

		// Create client with elicitation capability and handler
		McpAsyncClient asyncMcpClient = McpClient.async(transport)
			.capabilities(ClientCapabilities.builder().elicitation().build())
			.elicitation(elicitationHandler)
			.build();

		assertThat(asyncMcpClient.initialize().block()).isNotNull();

		// Create a mock elicitation
		var elicitRequest = McpSchema.ElicitRequest.builder()
			.message("Test message")
			.requestedSchema(Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))))
			.build();

		// Simulate incoming request
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_ELICITATION_CREATE, "test-id", elicitRequest);
		transport.simulateIncomingMessage(request);

		// Verify response
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.id()).isEqualTo("test-id");
		assertThat(response.error()).isNull();

		McpSchema.ElicitResult result = transport.unmarshalFrom(response.result(), new TypeReference<>() {
		});
		assertThat(result).isNotNull();
		assertThat(result.action()).isEqualTo(McpSchema.ElicitResult.Action.ACCEPT);
		assertThat(result.content()).isEqualTo(Map.of("message", "Test message"));

		asyncMcpClient.closeGracefully();
	}

	@ParameterizedTest
	@EnumSource(value = McpSchema.ElicitResult.Action.class, names = { "DECLINE", "CANCEL" })
	void testElicitationFailRequestHandling(McpSchema.ElicitResult.Action action) {
		MockMcpClientTransport transport = initializationEnabledTransport();

		// Create a test elicitation handler to decline the request
		Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler = request -> Mono
			.just(McpSchema.ElicitResult.builder().message(action).build());

		// Create client with elicitation capability and handler
		McpAsyncClient asyncMcpClient = McpClient.async(transport)
			.capabilities(ClientCapabilities.builder().elicitation().build())
			.elicitation(elicitationHandler)
			.build();

		assertThat(asyncMcpClient.initialize().block()).isNotNull();

		// Create a mock elicitation
		var elicitRequest = McpSchema.ElicitRequest.builder()
			.message("Test message")
			.requestedSchema(Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))))
			.build();

		// Simulate incoming request
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_ELICITATION_CREATE, "test-id", elicitRequest);
		transport.simulateIncomingMessage(request);

		// Verify response
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.id()).isEqualTo("test-id");
		assertThat(response.error()).isNull();

		McpSchema.ElicitResult result = transport.unmarshalFrom(response.result(), new TypeReference<>() {
		});
		assertThat(result).isNotNull();
		assertThat(result.action()).isEqualTo(action);
		assertThat(result.content()).isNull();

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testElicitationCreateRequestHandlingWithoutCapability() {
		MockMcpClientTransport transport = initializationEnabledTransport();

		// Create client without elicitation capability
		McpAsyncClient asyncMcpClient = McpClient.async(transport)
			.capabilities(ClientCapabilities.builder().build()) // No elicitation
																// capability
			.build();

		assertThat(asyncMcpClient.initialize().block()).isNotNull();

		// Create a mock elicitation
		var elicitRequest = new McpSchema.ElicitRequest("test",
				Map.of("type", "object", "properties", Map.of("test", Map.of("type", "boolean", "defaultValue", true,
						"description", "test-description", "title", "test-title"))));

		// Simulate incoming request
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_ELICITATION_CREATE, "test-id", elicitRequest);
		transport.simulateIncomingMessage(request);

		// Verify error response
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.id()).isEqualTo("test-id");
		assertThat(response.result()).isNull();
		assertThat(response.error()).isNotNull();
		assertThat(response.error().message()).contains("Method not found: elicitation/create");

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testElicitationCreateRequestHandlingWithNullHandler() {
		MockMcpClientTransport transport = new MockMcpClientTransport();

		// Create client with elicitation capability but null handler
		assertThatThrownBy(() -> McpClient.async(transport)
			.capabilities(ClientCapabilities.builder().elicitation().build())
			.build()).isInstanceOf(McpError.class)
			.hasMessage("Elicitation handler must not be null when client capabilities include elicitation");
	}

	@Test
	void testPingMessageRequestHandling() {
		MockMcpClientTransport transport = initializationEnabledTransport();

		McpAsyncClient asyncMcpClient = McpClient.async(transport).build();

		assertThat(asyncMcpClient.initialize().block()).isNotNull();

		// Simulate incoming ping request from server
		McpSchema.JSONRPCRequest pingRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_PING, "ping-id", null);
		transport.simulateIncomingMessage(pingRequest);

		// Verify response
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.id()).isEqualTo("ping-id");
		assertThat(response.error()).isNull();
		assertThat(response.result()).isInstanceOf(Map.class);
		assertThat(((Map<?, ?>) response.result())).isEmpty();

		asyncMcpClient.closeGracefully();
	}

}