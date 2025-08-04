package io.modelcontextprotocol.client;

import io.modelcontextprotocol.client.transport.WebRxStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Timeout;
import org.noear.solon.net.http.HttpUtilsBuilder;

@Timeout(15)
public class WebRxStreamableHttpAsyncClientResiliencyTests extends AbstractMcpAsyncClientResiliencyTests {

	@Override
	protected McpClientTransport createMcpTransport() {
		return WebRxStreamableHttpTransport.builder(new HttpUtilsBuilder().baseUri(host)).build();
	}
}
