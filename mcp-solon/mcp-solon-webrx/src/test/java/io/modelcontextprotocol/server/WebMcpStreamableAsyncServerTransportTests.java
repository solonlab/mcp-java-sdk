/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.WebRxStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.junit.jupiter.api.Timeout;
import org.noear.solon.Solon;
import org.noear.solon.boot.http.HttpServerConfigure;

/**
 * Tests for {@link McpAsyncServer} using {@link McpStreamableServerTransportProvider}.
 *
 * @author Christian Tzolov
 * @author noear
 */
@Timeout(15) // Giving extra time beyond the client timeout
class WebMcpStreamableAsyncServerTransportTests extends AbstractMcpAsyncServerTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String MCP_ENDPOINT = "/mcp";

	private McpStreamableServerTransportProvider createMcpTransportProvider() {
		WebRxStreamableServerTransportProvider transportProvider = WebRxStreamableServerTransportProvider.builder()
				.messageEndpoint(MCP_ENDPOINT)
				.build();

		Solon.start(WebMcpStreamableAsyncServerTransportTests.class, new String[]{"-server.port=" + PORT}, app -> {
			transportProvider.toHttpHandler(app);
			app.onEvent(HttpServerConfigure.class, event -> {
				event.enableDebug(true);
			});
		});

		return transportProvider;
	}

	@Override
	protected McpServer.AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(createMcpTransportProvider());
	}

	@Override
	protected void onStart() {
	}

	@Override
	protected void onClose() {
		if (Solon.app() != null) {
			Solon.stopBlock();
		}
	}
}
