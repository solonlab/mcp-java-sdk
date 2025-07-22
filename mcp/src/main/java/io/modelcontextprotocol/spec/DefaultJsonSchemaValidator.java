/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.spec;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import io.modelcontextprotocol.util.Assert;

/**
 * Default implementation of the {@link JsonSchemaValidator} interface. This class
 * provides methods to validate structured content against a JSON schema. It uses the
 * NetworkNT JSON Schema Validator library for validation.
 *
 * @author Christian Tzolov
 */
public class DefaultJsonSchemaValidator implements JsonSchemaValidator {

	private static final Logger logger = LoggerFactory.getLogger(DefaultJsonSchemaValidator.class);

	private final ObjectMapper objectMapper;

	private final JsonSchemaFactory schemaFactory;

	// TODO: Implement a strategy to purge the cache (TTL, size limit, etc.)
	private final ConcurrentHashMap<String, JsonSchema> schemaCache;

	public DefaultJsonSchemaValidator() {
		this(new ObjectMapper());
	}

	public DefaultJsonSchemaValidator(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
		this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
		this.schemaCache = new ConcurrentHashMap<>();
	}

	@Override
	public ValidationResponse validate(Map<String, Object> schema, Map<String, Object> structuredContent) {

		Assert.notNull(schema, "Schema must not be null");
		Assert.notNull(structuredContent, "Structured content must not be null");

		try {

			JsonNode jsonStructuredOutput = this.objectMapper.valueToTree(structuredContent);

			Set<ValidationMessage> validationResult = this.getOrCreateJsonSchema(schema).validate(jsonStructuredOutput);

			// Check if validation passed
			if (!validationResult.isEmpty()) {
				return ValidationResponse
					.asInvalid("Validation failed: structuredContent does not match tool outputSchema. "
							+ "Validation errors: " + validationResult);
			}

			return ValidationResponse.asValid(jsonStructuredOutput.toString());

		}
		catch (JsonProcessingException e) {
			logger.error("Failed to validate CallToolResult: Error parsing schema: {}", e);
			return ValidationResponse.asInvalid("Error parsing tool JSON Schema: " + e.getMessage());
		}
		catch (Exception e) {
			logger.error("Failed to validate CallToolResult: Unexpected error: {}", e);
			return ValidationResponse.asInvalid("Unexpected validation error: " + e.getMessage());
		}
	}

	/**
	 * Gets a cached JsonSchema or creates and caches a new one.
	 * @param schema the schema map to convert
	 * @return the compiled JsonSchema
	 * @throws JsonProcessingException if schema processing fails
	 */
	private JsonSchema getOrCreateJsonSchema(Map<String, Object> schema) throws JsonProcessingException {
		// Generate cache key based on schema content
		String cacheKey = this.generateCacheKey(schema);

		// Try to get from cache first
		JsonSchema cachedSchema = this.schemaCache.get(cacheKey);
		if (cachedSchema != null) {
			return cachedSchema;
		}

		// Create new schema if not in cache
		JsonSchema newSchema = this.createJsonSchema(schema);

		// Cache the schema
		JsonSchema existingSchema = this.schemaCache.putIfAbsent(cacheKey, newSchema);
		return existingSchema != null ? existingSchema : newSchema;
	}

	/**
	 * Creates a new JsonSchema from the given schema map.
	 * @param schema the schema map
	 * @return the compiled JsonSchema
	 * @throws JsonProcessingException if schema processing fails
	 */
	private JsonSchema createJsonSchema(Map<String, Object> schema) throws JsonProcessingException {
		// Convert schema map directly to JsonNode (more efficient than string
		// serialization)
		JsonNode schemaNode = this.objectMapper.valueToTree(schema);

		// Handle case where ObjectMapper might return null (e.g., in mocked scenarios)
		if (schemaNode == null) {
			throw new JsonProcessingException("Failed to convert schema to JsonNode") {
			};
		}

		// Handle additionalProperties setting
		if (schemaNode.isObject()) {
			ObjectNode objectSchemaNode = (ObjectNode) schemaNode;
			if (!objectSchemaNode.has("additionalProperties")) {
				// Clone the node before modification to avoid mutating the original
				objectSchemaNode = objectSchemaNode.deepCopy();
				objectSchemaNode.put("additionalProperties", false);
				schemaNode = objectSchemaNode;
			}
		}

		return this.schemaFactory.getSchema(schemaNode);
	}

	/**
	 * Generates a cache key for the given schema map.
	 * @param schema the schema map
	 * @return a cache key string
	 */
	protected String generateCacheKey(Map<String, Object> schema) {
		if (schema.containsKey("$id")) {
			// Use the (optional) "$id" field as the cache key if present
			return "" + schema.get("$id");
		}
		// Fall back to schema's hash code as a simple cache key
		// For more sophisticated caching, could use content-based hashing
		return String.valueOf(schema.hashCode());
	}

	/**
	 * Clears the schema cache. Useful for testing or memory management.
	 */
	public void clearCache() {
		this.schemaCache.clear();
	}

	/**
	 * Returns the current size of the schema cache.
	 * @return the number of cached schemas
	 */
	public int getCacheSize() {
		return this.schemaCache.size();
	}

}
