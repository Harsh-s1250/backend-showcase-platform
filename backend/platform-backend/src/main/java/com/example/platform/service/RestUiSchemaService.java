package com.example.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a running project's OpenAPI spec into a small set of CRUD-able resources the
 * frontend can render tables/forms for — PRD §10 "Dynamic API-Based UI" and §11 "API
 * Schema -> UI Mapping".
 *
 * Deliberately conservative per PRD §11: "For complex or unsupported APIs, the system
 * should fall back to the API Explorer instead of generating an unreliable interface."
 * Anything that doesn't cleanly fit the collection+item CRUD pattern, or whose fields
 * aren't simple primitives, is left out rather than guessed at.
 */
@Service
public class RestUiSchemaService {

    private static final Pattern SINGLE_TRAILING_PARAM = Pattern.compile("^\\{[^/{}]+}$");

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UiSchema.Result fetchAndBuild(int hostPort) {
        String specJson;
        try {
            specJson = restClient.get()
                    .uri("http://localhost:" + hostPort + "/v3/api-docs")
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            return UiSchema.Result.unsupported("Could not fetch the OpenAPI spec from the running project.");
        }
        return build(specJson);
    }

    public UiSchema.Result build(String specJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(specJson);
        } catch (Exception e) {
            return UiSchema.Result.unsupported("The OpenAPI spec could not be parsed.");
        }

        JsonNode paths = root.path("paths");
        if (!paths.isObject() || paths.isEmpty()) {
            return UiSchema.Result.unsupported("This project's OpenAPI spec has no paths to build a UI from.");
        }

        // Split into "exact" paths (no {param}) and "single trailing param" item paths
        // (e.g. /api/tasks/{id}). Anything with a param in the middle, or more than one
        // param, is left alone entirely — too complex to safely infer a resource from.
        Map<String, JsonNode> exactPaths = new LinkedHashMap<>();
        Map<String, String> itemPathByBase = new LinkedHashMap<>();
        Map<String, JsonNode> itemNodeByPath = new LinkedHashMap<>();

        paths.fields().forEachRemaining(entry -> {
            String path = entry.getKey();
            JsonNode node = entry.getValue();
            String[] segments = path.split("/");
            long paramCount = 0;
            for (String s : segments) if (s.startsWith("{")) paramCount++;

            if (paramCount == 0) {
                exactPaths.put(path, node);
            } else if (paramCount == 1 && segments.length > 0
                    && SINGLE_TRAILING_PARAM.matcher(segments[segments.length - 1]).matches()) {
                String base = path.substring(0, path.length() - segments[segments.length - 1].length() - 1);
                itemPathByBase.put(base, path);
                itemNodeByPath.put(path, node);
            }
            // else: multi-param or mid-path param — intentionally ignored, not an error.
        });

        List<UiSchema.Resource> resources = new ArrayList<>();

        for (var entry : exactPaths.entrySet()) {
            String base = entry.getKey();
            JsonNode listNode = entry.getValue();
            if (!listNode.has("get")) continue; // nothing to list — not useful as a table

            String itemPath = itemPathByBase.get(base);
            JsonNode itemNode = itemPath != null ? itemNodeByPath.get(itemPath) : null;

            List<UiSchema.Field> fields = extractFields(root, listNode, itemNode);
            if (fields == null || fields.isEmpty()) continue; // schema too complex or empty — skip this resource

            String name = lastSegment(base);
            if (name.isBlank()) continue;

            String idField = fields.stream()
                    .filter(f -> f.name().equalsIgnoreCase("id"))
                    .map(UiSchema.Field::name)
                    .findFirst()
                    .orElse(fields.get(0).name());

            resources.add(new UiSchema.Resource(
                    name,
                    capitalize(name),
                    base,
                    itemPath,
                    idField,
                    fields,
                    listNode.has("post"),
                    itemNode != null && (itemNode.has("put") || itemNode.has("patch")),
                    itemNode != null && itemNode.has("delete")
            ));
        }

        if (resources.isEmpty()) {
            return UiSchema.Result.unsupported(
                    "No CRUD-style resources with a simple, flat schema were found in the OpenAPI spec.");
        }

        return new UiSchema.Result(true, null, resources);
    }

    /**
     * Pulls fields from the GET-list response schema (preferred, since that's what the
     * table actually renders), falling back to the POST request body schema. Returns
     * null if the schema has any nested object/array-of-object fields — those are exactly
     * the "complex" case PRD §11 says to fall back on rather than render unreliably.
     */
    private List<UiSchema.Field> extractFields(JsonNode root, JsonNode listNode, JsonNode itemNode) {
        JsonNode schema = responseArrayItemSchema(listNode, "get");
        if (schema == null && itemNode != null) {
            schema = responseObjectSchema(itemNode, "get");
        }
        if (schema == null) {
            schema = requestBodySchema(listNode, "post");
        }
        if (schema == null) return null;

        schema = resolveRef(root, schema);
        if (schema == null || !schema.has("properties")) return null;

        JsonNode requiredNode = schema.path("required");
        List<String> required = new ArrayList<>();
        if (requiredNode.isArray()) requiredNode.forEach(n -> required.add(n.asText()));

        List<UiSchema.Field> fields = new ArrayList<>();
        var propsIterator = schema.path("properties").fields();
        while (propsIterator.hasNext()) {
            var propEntry = propsIterator.next();
            String fieldName = propEntry.getKey();
            JsonNode propSchema = resolveRef(root, propEntry.getValue());
            if (propSchema == null) return null; // unresolvable $ref — too complex to trust

            String type = propSchema.path("type").asText(null);

            if (type == null || type.equals("object") || type.equals("array")) {
                // Nested object, array-of-object, or unresolvable $ref — too complex.
                return null;
            }

            fields.add(new UiSchema.Field(
                    fieldName,
                    type,
                    required.contains(fieldName),
                    propSchema.path("readOnly").asBoolean(false)
            ));
        }
        return fields;
    }

    private JsonNode responseArrayItemSchema(JsonNode operationsNode, String method) {
        JsonNode schema = responseSchema(operationsNode, method);
        if (schema == null) return null;
        if ("array".equals(schema.path("type").asText(null))) {
            JsonNode items = schema.path("items");
            return items.isMissingNode() ? null : items;
        }
        return null;
    }

    private JsonNode responseObjectSchema(JsonNode operationsNode, String method) {
        return responseSchema(operationsNode, method);
    }

    private JsonNode responseSchema(JsonNode operationsNode, String method) {
        JsonNode op = operationsNode.path(method);
        JsonNode responses = op.path("responses");
        // Prefer a 200, otherwise take whatever's first — springdoc always documents
        // the success response, so this is just being lenient about the exact key.
        JsonNode response = responses.has("200") ? responses.get("200")
                : responses.fields().hasNext() ? responses.fields().next().getValue() : null;
        if (response == null) return null;
        return response.path("content").path("application/json").path("schema").isMissingNode()
                ? null
                : response.path("content").path("application/json").path("schema");
    }

    private JsonNode requestBodySchema(JsonNode operationsNode, String method) {
        JsonNode op = operationsNode.path(method);
        JsonNode schema = op.path("requestBody").path("content").path("application/json").path("schema");
        return schema.isMissingNode() ? null : schema;
    }

    private JsonNode resolveRef(JsonNode root, JsonNode node) {
        if (node == null) return null;
        JsonNode refNode = node.path("$ref");
        if (refNode.isMissingNode()) return node;

        String ref = refNode.asText(); // e.g. "#/components/schemas/Task"
        if (!ref.startsWith("#/")) return null;
        String[] parts = ref.substring(2).split("/");
        JsonNode current = root;
        for (String part : parts) {
            current = current.path(part);
            if (current.isMissingNode()) return null;
        }
        return current;
    }

    private String lastSegment(String path) {
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank()) return parts[i];
        }
        return "";
    }

    private String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
