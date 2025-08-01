/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * A mock implementation of the {@link McpClientTransport} interfaces.
 */
public class MockMcpClientTransport implements McpClientTransport {

	private final Sinks.Many<McpSchema.JSONRPCMessage> inbound = Sinks.many().unicast().onBackpressureBuffer();

	private final List<McpSchema.JSONRPCMessage> sent = new ArrayList<>();

	private final BiConsumer<MockMcpClientTransport, McpSchema.JSONRPCMessage> interceptor;

	private String protocolVersion = McpSchema.LATEST_PROTOCOL_VERSION;

	public MockMcpClientTransport() {
		this((t, msg) -> {
		});
	}

	public MockMcpClientTransport(BiConsumer<MockMcpClientTransport, McpSchema.JSONRPCMessage> interceptor) {
		this.interceptor = interceptor;
	}

	public MockMcpClientTransport withProtocolVersion(String protocolVersion) {
		return this;
	}

	@Override
	public String protocolVersion() {
		return protocolVersion;
	}

	public void simulateIncomingMessage(McpSchema.JSONRPCMessage message) {
		if (inbound.tryEmitNext(message).isFailure()) {
			throw new RuntimeException("Failed to process incoming message " + message);
		}
	}

	@Override
	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
		sent.add(message);
		interceptor.accept(this, message);
		return Mono.empty();
	}

	public McpSchema.JSONRPCRequest getLastSentMessageAsRequest() {
		return (JSONRPCRequest) getLastSentMessage();
	}

	public McpSchema.JSONRPCNotification getLastSentMessageAsNotification() {
		return (JSONRPCNotification) getLastSentMessage();
	}

	public McpSchema.JSONRPCMessage getLastSentMessage() {
		return !sent.isEmpty() ? sent.get(sent.size() - 1) : null;
	}

	private volatile boolean connected = false;

	@Override
	public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		if (connected) {
			return Mono.error(new IllegalStateException("Already connected"));
		}
		connected = true;
		return inbound.asFlux()
			.flatMap(message -> Mono.just(message).transform(handler))
			.doFinally(signal -> connected = false)
			.then();
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			connected = false;
			inbound.tryEmitComplete();
			// Wait for all subscribers to complete
			return Mono.empty();
		});
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return new ObjectMapper().convertValue(data, typeRef);
	}

}
