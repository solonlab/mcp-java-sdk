package io.modelcontextprotocol.client;

import io.modelcontextprotocol.client.transport.WebRxStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.noear.solon.net.http.HttpUtilsBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;

@Timeout(15)
public class WebRxStreamableHttpSyncClientTests extends AbstractMcpSyncClientTests {

    static String host = "http://localhost:3001";

    // Uses the https://github.com/tzolov/mcp-everything-server-docker-image
    @SuppressWarnings("resource")
    static GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v3")
            .withCommand("node dist/index.js streamableHttp")
            .withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
            .withExposedPorts(3001)
            .waitingFor(Wait.forHttp("/").forStatusCode(404));

	@Override
	protected McpClientTransport createMcpTransport() {
		return WebRxStreamableHttpTransport.builder(new HttpUtilsBuilder().baseUri(host)).build();
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
