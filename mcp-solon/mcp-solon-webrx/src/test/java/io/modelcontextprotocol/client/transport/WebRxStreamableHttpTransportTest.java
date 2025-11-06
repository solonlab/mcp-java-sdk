/*
 * Copyright 2024-2025 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.noear.solon.net.http.HttpUtilsBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import reactor.test.StepVerifier;

class WebRxStreamableHttpTransportTest {

	static String host = "http://localhost:3001";

	static HttpUtilsBuilder builder;

	@SuppressWarnings("resource")
	static GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v3")
		.withCommand("node dist/index.js streamableHttp")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	@BeforeAll
	static void startContainer() {
		container.start();
		int port = container.getMappedPort(3001);
		host = "http://" + container.getHost() + ":" + port;
		builder = new HttpUtilsBuilder().baseUri(host);
	}

	@AfterAll
	static void stopContainer() {
		container.stop();
	}

	@Test
	void testCloseUninitialized() {
		var transport = WebRxStreamableHttpTransport.builder(builder).build();

		StepVerifier.create(transport.closeGracefully()).verifyComplete();

		var initializeRequest = new McpSchema.InitializeRequest(McpSchema.LATEST_PROTOCOL_VERSION,
				McpSchema.ClientCapabilities.builder().roots(true).build(),
				new McpSchema.Implementation("MCP Client", "0.3.1"));
		var testMessage = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_INITIALIZE,
				"test-id", initializeRequest);

		StepVerifier.create(transport.sendMessage(testMessage))
			.expectErrorMessage("MCP session has been closed")
			.verify();
	}

	@Test
	void testCloseInitialized() {
		var transport = WebRxStreamableHttpTransport.builder(builder).build();

		var initializeRequest = new McpSchema.InitializeRequest(McpSchema.LATEST_PROTOCOL_VERSION,
				McpSchema.ClientCapabilities.builder().roots(true).build(),
				new McpSchema.Implementation("MCP Client", "0.3.1"));
		var testMessage = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_INITIALIZE,
				"test-id", initializeRequest);

		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();
		StepVerifier.create(transport.closeGracefully()).verifyComplete();

		StepVerifier.create(transport.sendMessage(testMessage))
			.expectErrorMatches(err -> err.getMessage().matches("MCP session with ID [a-zA-Z0-9-]* has been closed"))
			.verify();
	}

}
