package com.example.platform.service;

import java.util.List;

/**
 * What the frontend needs to render a generated CRUD UI for one REST resource,
 * derived from an OpenAPI schema. See PRD §10-12.
 */
public class UiSchema {

    public record Field(
            String name,
            String type,      // "string" | "integer" | "number" | "boolean" — object/array fields are filtered out upstream
            boolean required,
            boolean readOnly
    ) {}

    public record Resource(
            String name,               // e.g. "tasks" — taken from the path segment
            String displayName,        // e.g. "Tasks"
            String basePath,           // e.g. "/api/tasks"
            String itemPathTemplate,   // e.g. "/api/tasks/{id}", or null if there's no item path
            String idField,            // which field's value to substitute into itemPathTemplate
            List<Field> fields,
            boolean supportsCreate,
            boolean supportsUpdate,
            boolean supportsDelete
    ) {}

    public record Result(
            boolean supported,
            String reason,
            List<Resource> resources
    ) {
        public static Result unsupported(String reason) {
            return new Result(false, reason, List.of());
        }
    }
}
