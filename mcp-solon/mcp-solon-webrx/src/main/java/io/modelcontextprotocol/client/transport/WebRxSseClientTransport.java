/*
 * Copyright 2025 - 2025 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.util.Assert;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.net.http.textstream.ServerSentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import reactor.util.retry.Retry.RetrySignal;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Server-Sent Events (SSE) implementation of the
 * {@link io.modelcontextprotocol.spec.McpTransport} that follows the MCP HTTP with SSE
 * transport specification.
 *
 * <p>
 * This transport establishes a bidirectional communication channel where:
 * <ul>
 * <li>Inbound messages are received through an SSE connection from the server</li>
 * <li>Outbound messages are sent via HTTP POST requests to a server-provided
 * endpoint</li>
 * </ul>
 *
 * <p>
 * The message flow follows these steps:
 * <ol>
 * <li>The client establishes an SSE connection to the server's /sse endpoint</li>
 * <li>The server sends an 'endpoint' event containing the URI for sending messages</li>
 * </ol>
 *
 * This implementation uses {@link org.noear.solon.net.http.HttpUtils} for HTTP communications and supports JSON
 * serialization/deserialization of messages.
 *
 * @author Christian Tzolov
 * @author noear
 * @see <a href=
 * "https://spec.modelcontextprotocol.io/specification/basic/transports/#http-with-sse">MCP
 * HTTP with SSE Transport Specification</a>
 */
public class WebRxSseClientTransport implements McpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebRxSseClientTransport.class);

	/**
	 * Event type for JSON-RPC messages received through the SSE connection. The server
	 * sends messages with this event type to transmit JSON-RPC protocol data.
	 */
	private static final String MESSAGE_EVENT_TYPE = "message";

	/**
	 * Event type for receiving the message endpoint URI from the server. The server MUST
	 * send this event when a client connects, providing the URI where the client should
	 * send its messages via HTTP POST.
	 */
	private static final String ENDPOINT_EVENT_TYPE = "endpoint";

	/**
	 * Default SSE endpoint path as specified by the MCP transport specification. This
	 * endpoint is used to establish the SSE connection with the server.
	 */
	private static final String DEFAULT_SSE_ENDPOINT = "/sse";

	/**
	 * ObjectMapper for serializing outbound messages and deserializing inbound messages.
	 * Handles conversion between JSON-RPC messages and their string representation.
	 */
	protected ObjectMapper objectMapper;

	/**
	 * Subscription for the SSE connection handling inbound messages. Used for cleanup
	 * during transport shutdown.
	 */
	private Disposable inboundSubscription;

	/**
	 * Flag indicating if the transport is in the process of shutting down. Used to
	 * prevent new operations during shutdown and handle cleanup gracefully.
	 */
	private volatile boolean isClosing = false;

	/**
	 * Sink for managing the message endpoint URI provided by the server. Stores the most
	 * recent endpoint URI and makes it available for outbound message processing.
	 */
	protected final Sinks.One<String> messageEndpointSink = Sinks.one();

	/**
	 * The SSE service base URL provided by the server. Used for sending outbound messages via
	 * HTTP POST requests.
	 */
	private String baseUrl;

	/**
	 * The SSE endpoint URI provided by the server. Used for sending outbound messages via
	 * HTTP POST requests.
	 */
	private String sseEndpoint;

	/**
	 * Constructs a new SseClientTransport with the specified WebClient builder. Uses a
	 * default ObjectMapper instance for JSON processing.
	 * instance
	 * @param baseUrl the SSE service base URL
	 * @throws IllegalArgumentException if webClientBuilder is null
	 */
	public WebRxSseClientTransport(String baseUrl) {
		this(baseUrl,new ObjectMapper());
	}

	/**
	 * Constructs a new SseClientTransport with the specified WebClient builder and
	 * ObjectMapper. Initializes both inbound and outbound message processing pipelines.
	 * instance
	 * @param baseUrl the SSE service base URL
	 * @param objectMapper the ObjectMapper to use for JSON processing
	 * @throws IllegalArgumentException if either parameter is null
	 */
	public WebRxSseClientTransport(String baseUrl,ObjectMapper objectMapper) {
		this(baseUrl, objectMapper, DEFAULT_SSE_ENDPOINT);
	}

	/**
	 * Constructs a new SseClientTransport with the specified WebClient builder and
	 * ObjectMapper. Initializes both inbound and outbound message processing pipelines.
	 * instance
	 * @param baseUrl the SSE service base URL
	 * @param objectMapper the ObjectMapper to use for JSON processing
	 * @param sseEndpoint the SSE endpoint URI to use for establishing the connection
	 * @throws IllegalArgumentException if either parameter is null
	 */
	public WebRxSseClientTransport(String baseUrl,ObjectMapper objectMapper,
								   String sseEndpoint) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.hasText(sseEndpoint, "SSE endpoint must not be null or empty");

		this.baseUrl = baseUrl;
		this.objectMapper = objectMapper;
		this.sseEndpoint = sseEndpoint;
	}

	/**
	 * Establishes a connection to the MCP server using Server-Sent Events (SSE). This
	 * method initiates the SSE connection and sets up the message processing pipeline.
	 *
	 * <p>
	 * The connection process follows these steps:
	 * <ol>
	 * <li>Establishes an SSE connection to the server's /sse endpoint</li>
	 * <li>Waits for the server to send an 'endpoint' event with the message posting
	 * URI</li>
	 * <li>Sets up message handling for incoming JSON-RPC messages</li>
	 * </ol>
	 *
	 * <p>
	 * The connection is considered established only after receiving the endpoint event
	 * from the server.
	 * @param handler a function that processes incoming JSON-RPC messages and returns
	 * responses
	 * @return a Mono that completes when the connection is fully established
	 * @throws McpError if there's an error processing SSE events or if an unrecognized
	 * event type is received
	 */
	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		Flux<ServerSentEvent> events = eventStream();
		this.inboundSubscription = events.concatMap(event -> Mono.just(event).<JSONRPCMessage>handle((e, s) -> {
			if (ENDPOINT_EVENT_TYPE.equals(event.event())) {
				String messageEndpointUri = event.data();
				if (messageEndpointSink.tryEmitValue(messageEndpointUri).isSuccess()) {
					s.complete();
				}
				else {
					// TODO: clarify with the spec if multiple events can be
					// received
					s.error(new McpError("Failed to handle SSE endpoint event"));
				}
			}
			else if (MESSAGE_EVENT_TYPE.equals(event.event())) {
				try {
					JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(this.objectMapper, event.data());
					s.next(message);
				}
				catch (IOException ioException) {
					s.error(ioException);
				}
			}
			else {
				s.error(new McpError("Received unrecognized SSE event type: " + event.event()));
			}
		}).transform(handler)).subscribe();

		// The connection is established once the server sends the endpoint event
		return messageEndpointSink.asMono().then();
	}

	/**
	 * Sends a JSON-RPC message to the server using the endpoint provided during
	 * connection.
	 *
	 * <p>
	 * Messages are sent via HTTP POST requests to the server-provided endpoint URI. The
	 * message is serialized to JSON before transmission. If the transport is in the
	 * process of closing, the message send operation is skipped gracefully.
	 * @param message the JSON-RPC message to send
	 * @return a Mono that completes when the message has been sent successfully
	 * @throws RuntimeException if message serialization fails
	 */
	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		// The messageEndpoint is the endpoint URI to send the messages
		// It is provided by the server as part of the endpoint event
		return messageEndpointSink.asMono().flatMap(messageEndpointUri -> {
			if (isClosing) {
				return Mono.empty();
			}

			try {
				String jsonText = this.objectMapper.writeValueAsString(message);

				HttpUtils.http(baseUrl + messageEndpointUri)
						.contentType(MimeType.APPLICATION_JSON_VALUE)
						.bodyOfJson(jsonText)
						.post();

				logger.debug("Message sent successfully");
			} catch (Exception error) {
				if (!isClosing) {
					logger.error("Error sending message: {}", error.getMessage());
				}
			}

			return Mono.empty();
		}).then(); // TODO: Consider non-200-ok response
	}

	/**
     * Initializes and starts the inbound SSE event processing. Establishes the SSE
     * connection and sets up event handling for both message and endpoint events.
     * Includes automatic retry logic for handling transient connection failures.
     */
	// visible for tests
	protected Flux<ServerSentEvent> eventStream() {// @formatter:off
		return Flux.from(HttpUtils.http(baseUrl + this.sseEndpoint)
				.accept(MimeType.TEXT_EVENT_STREAM_VALUE)
				.execAsEventStream("GET"))
				.retryWhen(Retry.from(retrySignal -> retrySignal.handle(inboundRetryHandler)));
	} // @formatter:on

	/**
	 * Retry handler for the inbound SSE stream. Implements the retry logic for handling
	 * connection failures and other errors.
	 */
	private BiConsumer<RetrySignal, SynchronousSink<Object>> inboundRetryHandler = (retrySpec, sink) -> {
		if (isClosing) {
			logger.debug("SSE connection closed during shutdown");
			sink.error(retrySpec.failure());
			return;
		}
		if (retrySpec.failure() instanceof IOException) {
			logger.debug("Retrying SSE connection after IO error");
			sink.next(retrySpec);
			return;
		}
		logger.error("Fatal SSE error, not retrying: {}", retrySpec.failure().getMessage());
		sink.error(retrySpec.failure());
	};

	/**
	 * Implements graceful shutdown of the transport. Cleans up all resources including
	 * subscriptions and schedulers. Ensures orderly shutdown of both inbound and outbound
	 * message processing.
	 * @return a Mono that completes when shutdown is finished
	 */
	@Override
	public Mono<Void> closeGracefully() { // @formatter:off
		return Mono.fromRunnable(() -> {
			isClosing = true;
			
			// Dispose of subscriptions
			
			if (inboundSubscription != null) {
				inboundSubscription.dispose();
			}

		})
		.then()
		.subscribeOn(Schedulers.boundedElastic());
	} // @formatter:on

	/**
	 * Unmarshalls data from a generic Object into the specified type using the configured
	 * ObjectMapper.
	 *
	 * <p>
	 * This method is particularly useful when working with JSON-RPC parameters or result
	 * objects that need to be converted to specific Java types. It leverages Jackson's
	 * type conversion capabilities to handle complex object structures.
	 * @param <T> the target type to convert the data into
	 * @param data the source object to convert
	 * @param typeRef the TypeReference describing the target type
	 * @return the unmarshalled object of type T
	 * @throws IllegalArgumentException if the conversion cannot be performed
	 */
	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

	/**
	 * Creates a new builder for {@link WebRxSseClientTransport}.
	 * instance
	 * @return a new builder instance
	 */
	public static Builder builder(String baseUrl) {
		return new Builder(baseUrl);
	}

	/**
	 * Builder for {@link WebRxSseClientTransport}.
	 */
	public static class Builder {
		private String baseUrl;

		private String sseEndpoint = DEFAULT_SSE_ENDPOINT;

		private ObjectMapper objectMapper = new ObjectMapper();

		/**
		 * Creates a new builder with the specified WebClient.Builder.
		 */
		public Builder(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		/**
		 * Sets the SSE endpoint path.
		 * @param sseEndpoint the SSE endpoint path
		 * @return this builder
		 */
		public Builder sseEndpoint(String sseEndpoint) {
			Assert.hasText(sseEndpoint, "sseEndpoint must not be empty");
			this.sseEndpoint = sseEndpoint;
			return this;
		}

		/**
		 * Sets the object mapper for JSON serialization/deserialization.
		 * @param objectMapper the object mapper
		 * @return this builder
		 */
		public Builder objectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "objectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		/**
		 * Builds a new {@link WebRxSseClientTransport} instance.
		 * @return a new transport instance
		 */
		public WebRxSseClientTransport build() {
			return new WebRxSseClientTransport(baseUrl,objectMapper, sseEndpoint);
		}
	}
}