package com.example.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Small JSON envelope both directions of the terminal WebSocket speak.
 * type: "input" (client -> server), "output" | "exit" | "busy" | "error" | "info" (server -> client)
 *
 * @JsonProperty on the record components is required here: this codebase uses a raw
 * `new ObjectMapper()` (see RestUiSchemaService/GitHubOAuthService — no Spring-managed
 * ObjectMapper bean exists in this project), which has no ParameterNamesModule registered.
 * Without either that module or explicit @JsonProperty, Jackson can't map JSON field names
 * back onto a record's canonical constructor parameters and deserialization silently fails.
 */
public record TerminalMessage(@JsonProperty("type") String type, @JsonProperty("data") String data) {}
