/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.WebRxSseClientTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.WebRxSseServerTransportProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;
import org.noear.solon.Solon;
import org.noear.solon.boot.http.HttpServerConfigure;
import org.noear.solon.core.handle.Context;
import org.noear.solon.net.http.HttpUtilsBuilder;

@Timeout(15)
public class WebRxSseIntegrationTests extends AbstractMcpClientServerIntegrationTests{
    private static final int PORT = TestUtil.findAvailablePort();

    private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";

    private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

    private WebRxSseServerTransportProvider mcpServerTransportProvider;

    static McpTransportContextExtractor<Context> TEST_CONTEXT_EXTRACTOR = (r) -> McpTransportContext
            .create(Map.of("important", "value"));

    static Stream<Arguments> clientsForTesting() {
        return Stream.of(Arguments.of("httpclient"));
    }


    @Override
    protected void prepareClients(int port, String mcpEndpoint) {
        clientBuilders.put("httpclient",
                McpClient.sync(WebRxSseClientTransport.builder(new HttpUtilsBuilder().baseUri("http://localhost:" + PORT))
                        .sseEndpoint(CUSTOM_SSE_ENDPOINT)
                        .build()).requestTimeout(Duration.ofHours(10)));
    }

    @Override
    protected McpServer.AsyncSpecification<?> prepareAsyncServerBuilder() {
        return McpServer.async(mcpServerTransportProvider);
    }

    @Override
    protected McpServer.SingleSessionSyncSpecification prepareSyncServerBuilder() {
        return McpServer.sync(mcpServerTransportProvider);
    }

	@BeforeEach
	public void before() {
		this.mcpServerTransportProvider = new WebRxSseServerTransportProvider.Builder()
                .messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
                .sseEndpoint(CUSTOM_SSE_ENDPOINT)
                .contextExtractor(TEST_CONTEXT_EXTRACTOR)
				.build();

		Solon.start(WebRxSseIntegrationTests.class, new String[]{"server.port=" + PORT}, app -> {
			mcpServerTransportProvider.toHttpHandler(app);
			app.onEvent(HttpServerConfigure.class, event -> {
				event.enableDebug(true);
			});
		});

        prepareClients(PORT, null);
	}

	@AfterEach
	public void after() {
		if (Solon.app() != null) {
			Solon.stopBlock();
		}
	}
}
