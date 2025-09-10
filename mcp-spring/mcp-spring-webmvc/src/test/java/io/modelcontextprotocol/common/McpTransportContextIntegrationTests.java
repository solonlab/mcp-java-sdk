/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.common;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.TomcatTestUtil;
import io.modelcontextprotocol.server.TomcatTestUtil.TomcatServer;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link McpTransportContext} propagation between MCP clients and
 * servers using Spring WebMVC transport implementations.
 *
 * <p>
 * This test class validates the end-to-end flow of transport context propagation across
 * different MCP transport mechanisms in a Spring WebMVC environment. It demonstrates how
 * contextual information can be passed from client to server through HTTP headers and
 * properly extracted and utilized on the server side.
 *
 * <h2>Transport Types Tested</h2>
 * <ul>
 * <li><b>Stateless</b>: Tests context propagation with
 * {@link WebMvcStatelessServerTransport} where each request is independent</li>
 * <li><b>Streamable HTTP</b>: Tests context propagation with
 * {@link WebMvcStreamableServerTransportProvider} supporting stateful server
 * sessions</li>
 * <li><b>Server-Sent Events (SSE)</b>: Tests context propagation with
 * {@link WebMvcSseServerTransportProvider} for long-lived connections</li>
 * </ul>
 *
 * @author Daniel Garnier-Moiroux
 * @author Christian Tzolov
 */
@Timeout(15)
public class McpTransportContextIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private TomcatServer tomcatServer;

	private static final ThreadLocal<String> CLIENT_SIDE_HEADER_VALUE_HOLDER = new ThreadLocal<>();

	private static final String HEADER_NAME = "x-test";

	private final Supplier<McpTransportContext> clientContextProvider = () -> {
		var headerValue = CLIENT_SIDE_HEADER_VALUE_HOLDER.get();
		return headerValue != null ? McpTransportContext.create(Map.of("client-side-header-value", headerValue))
				: McpTransportContext.EMPTY;
	};

	private final McpSyncHttpClientRequestCustomizer clientRequestCustomizer = (builder, method, endpoint, body,
			context) -> {
		var headerValue = context.get("client-side-header-value");
		if (headerValue != null) {
			builder.header(HEADER_NAME, headerValue.toString());
		}
	};

	private static final BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> statelessHandler = (
			transportContext,
			request) -> new McpSchema.CallToolResult(transportContext.get("server-side-header-value").toString(), null);

	private static final BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> statefulHandler = (
			exchange, request) -> statelessHandler.apply(exchange.transportContext(), request);

	private static McpTransportContextExtractor<ServerRequest> serverContextExtractor = (ServerRequest r) -> {
		String headerValue = r.servletRequest().getHeader(HEADER_NAME);
		return headerValue != null ? McpTransportContext.create(Map.of("server-side-header-value", headerValue))
				: McpTransportContext.EMPTY;
	};

	private final McpSyncClient streamableClient = McpClient
		.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
			.httpRequestCustomizer(clientRequestCustomizer)
			.build())
		.transportContextProvider(clientContextProvider)
		.build();

	private final McpSyncClient sseClient = McpClient
		.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT)
			.httpRequestCustomizer(clientRequestCustomizer)
			.build())
		.transportContextProvider(clientContextProvider)
		.build();

	private static final McpSchema.Tool tool = McpSchema.Tool.builder()
		.name("test-tool")
		.description("return the value of the x-test header from call tool request")
		.build();

	@AfterEach
	public void after() {
		CLIENT_SIDE_HEADER_VALUE_HOLDER.remove();
		if (streamableClient != null) {
			streamableClient.closeGracefully();
		}
		if (sseClient != null) {
			sseClient.closeGracefully();
		}
		stopTomcat();
	}

	@Test
	void statelessServer() {
		startTomcat(TestStatelessConfig.class);

		McpSchema.InitializeResult initResult = streamableClient.initialize();
		assertThat(initResult).isNotNull();

		CLIENT_SIDE_HEADER_VALUE_HOLDER.set("some important value");
		McpSchema.CallToolResult response = streamableClient
			.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response.content()).hasSize(1)
			.first()
			.extracting(McpSchema.TextContent.class::cast)
			.extracting(McpSchema.TextContent::text)
			.isEqualTo("some important value");
	}

	@Test
	void streamableServer() {

		startTomcat(TestStreamableHttpConfig.class);

		McpSchema.InitializeResult initResult = streamableClient.initialize();
		assertThat(initResult).isNotNull();

		CLIENT_SIDE_HEADER_VALUE_HOLDER.set("some important value");
		McpSchema.CallToolResult response = streamableClient
			.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response.content()).hasSize(1)
			.first()
			.extracting(McpSchema.TextContent.class::cast)
			.extracting(McpSchema.TextContent::text)
			.isEqualTo("some important value");
	}

	@Test
	void sseServer() {
		startTomcat(TestSseConfig.class);

		McpSchema.InitializeResult initResult = sseClient.initialize();
		assertThat(initResult).isNotNull();

		CLIENT_SIDE_HEADER_VALUE_HOLDER.set("some important value");
		McpSchema.CallToolResult response = sseClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response.content()).hasSize(1)
			.first()
			.extracting(McpSchema.TextContent.class::cast)
			.extracting(McpSchema.TextContent::text)
			.isEqualTo("some important value");
	}

	private void startTomcat(Class<?> componentClass) {
		tomcatServer = TomcatTestUtil.createTomcatServer("", PORT, componentClass);
		try {
			tomcatServer.tomcat().start();
			assertThat(tomcatServer.tomcat().getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
	}

	private void stopTomcat() {
		if (tomcatServer != null && tomcatServer.tomcat() != null) {
			try {
				tomcatServer.tomcat().stop();
				tomcatServer.tomcat().destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

	@Configuration
	@EnableWebMvc
	static class TestStatelessConfig {

		@Bean
		public WebMvcStatelessServerTransport webMvcStatelessServerTransport() {

			return WebMvcStatelessServerTransport.builder()
				.objectMapper(new ObjectMapper())
				.contextExtractor(serverContextExtractor)
				.build();
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(WebMvcStatelessServerTransport transportProvider) {
			return transportProvider.getRouterFunction();
		}

		@Bean
		public McpStatelessSyncServer mcpStatelessServer(WebMvcStatelessServerTransport transportProvider) {
			return McpServer.sync(transportProvider)
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.tools(new McpStatelessServerFeatures.SyncToolSpecification(tool, statelessHandler))
				.build();
		}

	}

	@Configuration
	@EnableWebMvc
	static class TestStreamableHttpConfig {

		@Bean
		public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransport() {

			return WebMvcStreamableServerTransportProvider.builder()
				.objectMapper(new ObjectMapper())
				.contextExtractor(serverContextExtractor)
				.build();
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(
				WebMvcStreamableServerTransportProvider transportProvider) {
			return transportProvider.getRouterFunction();
		}

		@Bean
		public McpSyncServer mcpStreamableServer(WebMvcStreamableServerTransportProvider transportProvider) {
			return McpServer.sync(transportProvider)
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.tools(new McpServerFeatures.SyncToolSpecification(tool, null, statefulHandler))
				.build();
		}

	}

	@Configuration
	@EnableWebMvc
	static class TestSseConfig {

		@Bean
		public WebMvcSseServerTransportProvider webMvcSseServerTransport() {

			return WebMvcSseServerTransportProvider.builder()
				.objectMapper(new ObjectMapper())
				.contextExtractor(serverContextExtractor)
				.messageEndpoint("/mcp/message")
				.build();
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(WebMvcSseServerTransportProvider transportProvider) {
			return transportProvider.getRouterFunction();
		}

		@Bean
		public McpSyncServer mcpSseServer(WebMvcSseServerTransportProvider transportProvider) {
			return McpServer.sync(transportProvider)
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.tools(new McpServerFeatures.SyncToolSpecification(tool, null, statefulHandler))
				.build();

		}

	}

}
