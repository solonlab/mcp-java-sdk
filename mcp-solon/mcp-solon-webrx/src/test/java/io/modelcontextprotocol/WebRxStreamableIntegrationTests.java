/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.transport.WebRxStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import org.noear.solon.Solon;
import org.noear.solon.boot.http.HttpServerConfigure;
import org.noear.solon.core.handle.Context;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(15)
class WebRxStreamableIntegrationTests extends AbstractMcpClientServerIntegrationTests {

    private static final int PORT = TestUtil.findAvailablePort();

    private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

    private WebRxStreamableServerTransportProvider mcpServerTransportProvider;

    static McpTransportContextExtractor<Context> TEST_CONTEXT_EXTRACTOR = (r) -> McpTransportContext
            .create(Map.of("important", "value"));

    static Stream<Arguments> clientsForTesting() {
        return Stream.of(Arguments.of("httpclient"));
    }

    @Override
    protected void prepareClients(int port, String mcpEndpoint) {
        McpClient.SyncSpec httpclient = McpClient
                .sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint(mcpEndpoint).build())
                .initializationTimeout(Duration.ofHours(10))
                .requestTimeout(Duration.ofHours(10));

        clientBuilders.put("httpclient", httpclient);
        //clientBuilders.put("webflux", httpclient);
    }

    @Override
    protected AsyncSpecification<?> prepareAsyncServerBuilder() {
        return McpServer.async(this.mcpServerTransportProvider);
    }

    @Override
    protected SyncSpecification<?> prepareSyncServerBuilder() {
        return McpServer.sync(this.mcpServerTransportProvider);
    }

    @BeforeEach
    public void before() {
        mcpServerTransportProvider = WebRxStreamableServerTransportProvider.builder()
                .mcpEndpoint(CUSTOM_MESSAGE_ENDPOINT)
                .contextExtractor(TEST_CONTEXT_EXTRACTOR)
                .build();

        Solon.start(WebRxStreamableIntegrationTests.class, new String[]{"-server.port=" + PORT}, app -> {
            mcpServerTransportProvider.toHttpHandler(app);
            app.onEvent(HttpServerConfigure.class, event -> {
                event.enableDebug(true);
            });
        });

        prepareClients(PORT, null);
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
}