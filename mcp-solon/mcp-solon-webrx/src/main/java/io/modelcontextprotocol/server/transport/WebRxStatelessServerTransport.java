package io.modelcontextprotocol.server.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.Assert;
import org.noear.solon.SolonApp;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Entity;
import org.noear.solon.core.handle.StatusCodes;
import org.noear.solon.core.util.MimeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Implementation of a WebMVC based {@link McpStatelessServerTransport}.
 *
 * @author Christian Tzolov
 * @author noear
 * @see McpStatelessServerTransport
 */
public class WebRxStatelessServerTransport implements McpStatelessServerTransport, IMcpHttpServerTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebRxStatelessServerTransport.class);

	private final McpJsonMapper jsonMapper;

	private final String mcpEndpoint;

	private McpStatelessServerHandler mcpHandler;

	private McpTransportContextExtractor<Context> contextExtractor;

	private volatile boolean isClosing = false;

	private WebRxStatelessServerTransport(McpJsonMapper jsonMapper, String mcpEndpoint,
                                          McpTransportContextExtractor<Context> contextExtractor) {
		Assert.notNull(jsonMapper, "objectMapper must not be null");
		Assert.notNull(mcpEndpoint, "mcpEndpoint must not be null");
		Assert.notNull(contextExtractor, "contextExtractor must not be null");

		this.jsonMapper = jsonMapper;
		this.mcpEndpoint = mcpEndpoint;
		this.contextExtractor = contextExtractor;
	}

	@Override
	public void toHttpHandler(SolonApp app) {
		if (app != null) {
			app.get(this.mcpEndpoint, this::handleGet);
			app.post(this.mcpEndpoint, this::handlePost);
		}
	}

	@Override
	public String getMcpEndpoint() {
		return mcpEndpoint;
	}

	@Override
	public List<String> protocolVersions() {
		return Arrays.asList(ProtocolVersions.MCP_2025_03_26);
	}

	@Override
	public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
		this.mcpHandler = mcpHandler;
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> this.isClosing = true);
	}

	private void handleGet(Context request) {
		request.contentType("");

		request.status(StatusCodes.CODE_METHOD_NOT_ALLOWED);
	}

	private void handlePost(Context request) throws Throwable {
		request.contentType("");

		Entity entity = handlePostDo(request);
		if (entity.body() != null) {
			if (entity.body() instanceof McpError) {
				McpError mcpError = (McpError) entity.body();
				entity.body(mcpError.getMessage());
			} else if (entity.body() instanceof McpSchema.JSONRPCResponse) {
				entity.body(jsonMapper.writeValueAsString(entity.body()));
			}
		}

		request.returnValue(entity);
	}

	private Entity handlePostDo(Context request) {
		if (isClosing) {
			return new Entity().status(StatusCodes.CODE_SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		String acceptHeaders = request.acceptNew();
		if (!(acceptHeaders.contains(MimeType.APPLICATION_JSON_VALUE)
				&& acceptHeaders.contains(MimeType.TEXT_EVENT_STREAM_VALUE))) {
			return new Entity().status(StatusCodes.CODE_BAD_REQUEST);
		}

		try {
			String body = request.body();
			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);

			if (message instanceof McpSchema.JSONRPCRequest) {
				McpSchema.JSONRPCRequest jsonrpcRequest = (McpSchema.JSONRPCRequest) message;

				try {
					McpSchema.JSONRPCResponse jsonrpcResponse = this.mcpHandler
						.handleRequest(transportContext, jsonrpcRequest)
						.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
						.block();
					return new Entity().contentType(MimeType.APPLICATION_JSON_VALUE).body(jsonrpcResponse);
				}
				catch (Exception e) {
					logger.error("Failed to handle request: {}", e.getMessage());
					return new Entity().status(StatusCodes.CODE_INTERNAL_SERVER_ERROR)
						.body(new McpError("Failed to handle request: " + e.getMessage()));
				}
			}
			else if (message instanceof McpSchema.JSONRPCNotification) {
				McpSchema.JSONRPCNotification jsonrpcNotification = (McpSchema.JSONRPCNotification) message;

				try {
					this.mcpHandler.handleNotification(transportContext, jsonrpcNotification)
						.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
						.block();
					return new Entity().status(StatusCodes.CODE_ACCEPTED);
				}
				catch (Exception e) {
					logger.error("Failed to handle notification: {}", e.getMessage());
					return new Entity().status(StatusCodes.CODE_INTERNAL_SERVER_ERROR)
						.body(new McpError("Failed to handle notification: " + e.getMessage()));
				}
			}
			else {
				return new Entity().status(StatusCodes.CODE_BAD_REQUEST)
					.body(new McpError("The server accepts either requests or notifications"));
			}
		}
		catch (IllegalArgumentException | IOException e) {
			logger.error("Failed to deserialize message: {}", e.getMessage());
			return new Entity().status(StatusCodes.CODE_BAD_REQUEST).body(new McpError("Invalid message format"));
		}
		catch (Exception e) {
			logger.error("Unexpected error handling message: {}", e.getMessage());
			return new Entity().status(StatusCodes.CODE_INTERNAL_SERVER_ERROR)
				.body(new McpError("Unexpected error: " + e.getMessage()));
		}
	}

	/**
	 * Create a builder for the server.
	 * @return a fresh {@link Builder} instance.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of {@link WebRxStatelessServerTransport}.
	 * <p>
	 * This builder provides a fluent API for configuring and creating instances of
	 * WebMvcStatelessServerTransport with custom settings.
	 */
	public static class Builder {

		private McpJsonMapper jsonMapper;

		private String mcpEndpoint = "/mcp";

		private McpTransportContextExtractor<Context> contextExtractor = (
                serverRequest) -> McpTransportContext.EMPTY;

		private Builder() {
			// used by a static method
		}

        /**
         * Sets the ObjectMapper to use for JSON serialization/deserialization of MCP
         * messages.
         * @param jsonMapper The ObjectMapper instance. Must not be null.
         * @return this builder instance
         * @throws IllegalArgumentException if jsonMapper is null
         */
        public Builder jsonMapper(McpJsonMapper jsonMapper) {
            Assert.notNull(jsonMapper, "ObjectMapper must not be null");
            this.jsonMapper = jsonMapper;
            return this;
        }


        /**
		 * Sets the endpoint URI where clients should send their JSON-RPC messages.
		 * @param messageEndpoint The message endpoint URI. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if messageEndpoint is null
		 */
		public Builder messageEndpoint(String messageEndpoint) {
			Assert.notNull(messageEndpoint, "Message endpoint must not be null");
			this.mcpEndpoint = messageEndpoint;
			return this;
		}

		/**
		 * Sets the context extractor that allows providing the MCP feature
		 * implementations to inspect HTTP transport level metadata that was present at
		 * HTTP request processing time. This allows to extract custom headers and other
		 * useful data for use during execution later on in the process.
		 * @param contextExtractor The contextExtractor to fill in a
		 * {@link McpTransportContext}.
		 * @return this builder instance
		 * @throws IllegalArgumentException if contextExtractor is null
		 */
		public Builder contextExtractor(McpTransportContextExtractor<Context> contextExtractor) {
			Assert.notNull(contextExtractor, "Context extractor must not be null");
			this.contextExtractor = contextExtractor;
			return this;
		}

		/**
		 * Builds a new instance of {@link WebRxStatelessServerTransport} with the
		 * configured settings.
		 * @return A new WebMvcStatelessServerTransport instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public WebRxStatelessServerTransport build() {
			Assert.notNull(mcpEndpoint, "Message endpoint must be set");

			return new WebRxStatelessServerTransport(
                    jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper,
                     mcpEndpoint,
                    contextExtractor);
		}
	}
}