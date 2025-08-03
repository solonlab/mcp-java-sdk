/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.AbstractStatelessIntegrationTests;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer.StatelessAsyncSpecification;
import io.modelcontextprotocol.server.McpServer.StatelessSyncSpecification;
import io.modelcontextprotocol.server.transport.WebRxStatelessServerTransport;
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

class WebRxStatelessIntegrationTests extends AbstractStatelessIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private WebRxStatelessServerTransport mcpServerTransport = WebRxStatelessServerTransport.builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(MESSAGE_ENDPOINT)
			.build();

	@BeforeEach
	public void before() {
		Solon.start(WebRxStatelessIntegrationTests.class, new String[]{"-server.port=" + PORT}, app -> {
			mcpServerTransport.toHttpHandler(app);
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
	protected StatelessAsyncSpecification prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpServerTransport);
	}

	@Override
	protected StatelessSyncSpecification prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpServerTransport);
	}

	@AfterEach
	public void after() {
		if (this.mcpServerTransport != null) {
			this.mcpServerTransport.closeGracefully().block();
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

		var server = McpServer.async(this.mcpServerTransport)
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
