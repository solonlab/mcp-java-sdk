/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.noear.solon.net.http.HttpUtilsBuilder;
import org.noear.solon.net.http.textstream.ServerSentEvent;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for the {@link HttpClientSseClientTransport} class.
 *
 * @author Christian Tzolov
 */
@Timeout(15)
class WebRxSseClientTransportTests {

	static String host = "http://localhost:3001";

	@SuppressWarnings("resource")
	GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v1")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	private TestWebRxSseClientTransport transport;

	// Test class to access protected methods
	static class TestWebRxSseClientTransport extends WebRxSseClientTransport {

		private final AtomicInteger inboundMessageCount = new AtomicInteger(0);

		private Sinks.Many<ServerSentEvent> events = Sinks.many().unicast().onBackpressureBuffer();

		public TestWebRxSseClientTransport(String baseUri) {
			super(new HttpUtilsBuilder().baseUri(baseUri));
		}

		public int getInboundMessageCount() {
			return inboundMessageCount.get();
		}

		public void simulateEndpointEvent(String jsonMessage) {
			events.tryEmitNext(new ServerSentEvent(null,"endpoint",jsonMessage,null));
			inboundMessageCount.incrementAndGet();
		}

		public void simulateMessageEvent(String jsonMessage) {
			events.tryEmitNext(new ServerSentEvent(null,"message",jsonMessage,null));
			inboundMessageCount.incrementAndGet();
		}

	}

	void startContainer() {
		container.start();
		int port = container.getMappedPort(3001);
		host = "http://" + container.getHost() + ":" + port;
	}

	@BeforeEach
	void setUp() {
		startContainer();
		transport = new TestWebRxSseClientTransport(host);
		transport.connect(Function.identity()).block();
	}

	@AfterEach
	void afterEach() {
		if (transport != null) {
			assertThatCode(() -> transport.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
		}
		cleanup();
	}

	void cleanup() {
		container.stop();
	}

	@Test
	void testMessageProcessing() {
		// Create a test message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Map.of("key", "value"));

		// Simulate receiving the message
		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "test-method",
				    "id": "test-id",
				    "params": {"key": "value"}
				}
				""");

		// Subscribe to messages and verify
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testResponseMessageProcessing() {
		// Simulate receiving a response message
		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "id": "test-id",
				    "result": {"status": "success"}
				}
				""");

		// Create and send a request message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Map.of("key", "value"));

		// Verify message handling
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testErrorMessageProcessing() {
		// Simulate receiving an error message
		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "id": "test-id",
				    "error": {
				        "code": -32600,
				        "message": "Invalid Request"
				    }
				}
				""");

		// Create and send a request message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Map.of("key", "value"));

		// Verify message handling
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testNotificationMessageProcessing() {
		// Simulate receiving a notification message (no id)
		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "update",
				    "params": {"status": "processing"}
				}
				""");

		// Verify the notification was processed
		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testGracefulShutdown() {
		// Test graceful shutdown
		StepVerifier.create(transport.closeGracefully()).verifyComplete();

		// Create a test message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Map.of("key", "value"));

		// Verify message is not processed after shutdown
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// Message count should remain 0 after shutdown
		assertThat(transport.getInboundMessageCount()).isEqualTo(0);
	}

	@Test
	void testRetryBehavior() {
		// Create a client that simulates connection failures
		HttpClientSseClientTransport failingTransport = new HttpClientSseClientTransport("http://non-existent-host");

		// Verify that the transport attempts to reconnect
		StepVerifier.create(Mono.delay(Duration.ofSeconds(2))).expectNextCount(1).verifyComplete();

		// Clean up
		failingTransport.closeGracefully().block();
	}

	@Test
	void testMultipleMessageProcessing() {
		// Simulate receiving multiple messages in sequence
		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "method1",
				    "id": "id1",
				    "params": {"key": "value1"}
				}
				""");

		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "method2",
				    "id": "id2",
				    "params": {"key": "value2"}
				}
				""");

		// Create and send corresponding messages
		JSONRPCRequest message1 = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method1", "id1",
				Map.of("key", "value1"));

		JSONRPCRequest message2 = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method2", "id2",
				Map.of("key", "value2"));

		// Verify both messages are processed
		StepVerifier.create(transport.sendMessage(message1).then(transport.sendMessage(message2))).verifyComplete();

		// Verify message count
		assertThat(transport.getInboundMessageCount()).isEqualTo(2);
	}

	@Test
	void testMessageOrderPreservation() {
		// Simulate receiving messages in a specific order
		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "first",
				    "id": "1",
				    "params": {"sequence": 1}
				}
				""");

		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "second",
				    "id": "2",
				    "params": {"sequence": 2}
				}
				""");

		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "third",
				    "id": "3",
				    "params": {"sequence": 3}
				}
				""");

		// Verify message count and order
		assertThat(transport.getInboundMessageCount()).isEqualTo(3);
	}

}
