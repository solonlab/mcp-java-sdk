/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.transport.WebRxStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.noear.solon.Solon;
import org.noear.solon.boot.http.HttpServerConfigure;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WebRxStreamableIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private WebRxStreamableServerTransportProvider mcpServerTransportProvider = WebRxStreamableServerTransportProvider.builder()
			.objectMapper(new ObjectMapper())
			.mcpEndpoint(MESSAGE_ENDPOINT)
			.build();

	@BeforeEach
	public void before() {
		Solon.start(WebRxStreamableIntegrationTests.class, new String[]{"-server.port=" + PORT}, app -> {
			mcpServerTransportProvider.toHttpHandler(app);
			app.onEvent(HttpServerConfigure.class, event -> {
				event.enableDebug(true);
			});
		});

		McpClient.SyncSpec httpclient = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
				.endpoint(MESSAGE_ENDPOINT)
				.build()).initializationTimeout(Duration.ofHours(10)).requestTimeout(Duration.ofHours(10));

		clientBuilders.put("httpclient", httpclient);
		clientBuilders.put("webflux", httpclient);
	}

	@Override
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpServerTransportProvider);
	}

	@Override
	protected SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpServerTransportProvider);
	}

	@AfterEach
	public void after() {
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
		}
		Schedulers.shutdownNow();
		if (Solon.app() != null) {
			Solon.stopBlock();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = {"httpclient", "webflux"})
	void simple(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var server = McpServer.async(mcpServerTransportProvider)
				.serverInfo("test-server", "1.0.0")
				.requestTimeout(Duration.ofSeconds(1000))
				.build();

		try (
				// Create client without sampling capabilities
				var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
						.requestTimeout(Duration.ofSeconds(1000))
						.build()) {

			assertThat(client.initialize()).isNotNull();

		}
		server.closeGracefully();
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
		McpClient.SyncSpec httpclient = McpClient
				.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).endpoint(mcpEndpoint).build())
				.initializationTimeout(Duration.ofHours(10))
				.requestTimeout(Duration.ofHours(10));

		clientBuilders.put("httpclient", httpclient);
		clientBuilders.put("webflux", httpclient);
	}
}