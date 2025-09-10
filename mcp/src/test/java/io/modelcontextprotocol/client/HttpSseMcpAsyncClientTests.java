/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;

/**
 * Tests for the {@link McpSyncClient} with {@link HttpClientSseClientTransport}.
 *
 * @author Christian Tzolov
 */
@Timeout(15)
class HttpSseMcpAsyncClientTests extends AbstractMcpAsyncClientTests {

	private static String host = "http://localhost:3004";

	// Uses the https://github.com/tzolov/mcp-everything-server-docker-image
	@SuppressWarnings("resource")
	static GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v3")
		.withCommand("node dist/index.js sse")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	@Override
	protected McpClientTransport createMcpTransport() {
		return HttpClientSseClientTransport.builder(host).build();
	}

	@BeforeAll
	static void startContainer() {
		container.start();
		int port = container.getMappedPort(3001);
		host = "http://" + container.getHost() + ":" + port;
	}

	@AfterAll
	static void stopContainer() {
		container.stop();
	}

}
