/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.StatelessAsyncSpecification;
import io.modelcontextprotocol.server.McpServer.StatelessSyncSpecification;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.transport.WebRxStatelessServerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;
import org.noear.solon.Solon;
import org.noear.solon.boot.http.HttpServerConfigure;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.stream.Stream;

@Timeout(15)
class WebRxStatelessIntegrationTests extends AbstractStatelessIntegrationTests {

    private static final int PORT = TestUtil.findAvailablePort();

    private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

    private WebRxStatelessServerTransport mcpStreamableServerTransport;

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
    protected StatelessAsyncSpecification prepareAsyncServerBuilder() {
        return McpServer.async(this.mcpStreamableServerTransport);
    }

    @Override
    protected StatelessSyncSpecification prepareSyncServerBuilder() {
        return McpServer.sync(this.mcpStreamableServerTransport);
    }

    @BeforeEach
    public void before() {
        mcpStreamableServerTransport = WebRxStatelessServerTransport.builder()
                .messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
                .build();

        Solon.start(WebRxStatelessIntegrationTests.class, new String[]{"-server.port=" + PORT}, app -> {
            mcpStreamableServerTransport.toHttpHandler(app);
            app.onEvent(HttpServerConfigure.class, event -> {
                event.enableDebug(true);
            });
        });

        prepareClients(PORT, null);
    }


    @AfterEach
    public void after() {
        if (this.mcpStreamableServerTransport != null) {
            this.mcpStreamableServerTransport.closeGracefully().block();
        }

        Schedulers.shutdownNow();

        if (Solon.app() != null) {
            Solon.stopBlock();
        }
    }
}
