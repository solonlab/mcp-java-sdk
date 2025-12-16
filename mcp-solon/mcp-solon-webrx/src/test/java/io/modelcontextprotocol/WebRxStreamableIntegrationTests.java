/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebRxStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.transport.WebRxStreamableServerTransportProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;
import org.noear.solon.Solon;
import org.noear.solon.boot.http.HttpServerConfigure;
import org.noear.solon.core.handle.Context;
import org.noear.solon.net.http.HttpUtilsBuilder;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

@Timeout(15)
class WebRxStreamableIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private WebRxStreamableServerTransportProvider mcpStreamableServerTransportProvider;

	static McpTransportContextExtractor<Context> TEST_CONTEXT_EXTRACTOR = (r) -> McpTransportContext
			.create(Map.of("important", "value"));

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("httpclient"), Arguments.of("webflux"));
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {

		clientBuilders
				.put("httpclient",
						McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
								.endpoint(CUSTOM_MESSAGE_ENDPOINT)
								.build()).requestTimeout(Duration.ofHours(10)));
		clientBuilders.put("webflux",
				McpClient
						.sync(WebRxStreamableHttpTransport
								.builder(new HttpUtilsBuilder().baseUri("http://localhost:" + PORT))
								.endpoint(CUSTOM_MESSAGE_ENDPOINT)
								.build())
						.requestTimeout(Duration.ofHours(10)));
	}

	@Override
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(mcpStreamableServerTransportProvider);
	}

	@Override
	protected SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(mcpStreamableServerTransportProvider);
	}

	@BeforeEach
	public void before() {

		this.mcpStreamableServerTransportProvider = WebRxStreamableServerTransportProvider.builder()
				.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
				.contextExtractor(TEST_CONTEXT_EXTRACTOR)
				.build();

		Solon.start(WebRxStreamableIntegrationTests.class, new String[]{"-server.port=" + PORT}, app -> {
			mcpStreamableServerTransportProvider.toHttpHandler(app);
			app.onEvent(HttpServerConfigure.class, event -> {
				event.enableDebug(true);
			});
		});

		prepareClients(PORT, null);
	}

	@AfterEach
	public void after() {
		if (mcpStreamableServerTransportProvider != null) {
			mcpStreamableServerTransportProvider.closeGracefully().block();
		}
		Schedulers.shutdownNow();
		if (Solon.app() != null) {
			Solon.stopBlock();
		}
	}
}
