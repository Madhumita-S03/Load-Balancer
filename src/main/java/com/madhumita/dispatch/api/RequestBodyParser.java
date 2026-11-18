package com.madhumita.dispatch.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns a raw HTTP body into validated DTOs with precise error messages.
 *
 * <p>Accepted shapes for a collection named {@code vehicles}:
 * a bare array {@code [ {...} ]}, a wrapper {@code {"vehicles":[ {...} ]}}, or one bare object {@code {...}}.
 *
 * <p>Pasted JSON (from PDFs, chat apps or documents) often contains non-breaking spaces, a BOM or typographic
 * quotes that Jackson rejects with an unhelpful message; those are normalised before parsing.
 */
@Component
public class RequestBodyParser {
    private final ObjectMapper mapper;
    private final Validator validator;

    public RequestBodyParser(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    public <T> List<T> parseList(String rawBody, Class<T> elementType, String collectionName) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new InvalidRequestException("Request body is empty. Send a JSON array of " + collectionName + ".");
        }
        JsonNode root = readTree(rawBody, collectionName);
        List<JsonNode> elements = extractElements(root, collectionName);

        List<T> parsed = new ArrayList<>(elements.size());
        Map<String, String> errors = new LinkedHashMap<>();
        for (int i = 0; i < elements.size(); i++) {
            String path = collectionName + "[" + i + "]";
            JsonNode element = elements.get(i);
            if (element == null || !element.isObject()) {
                throw new InvalidRequestException(path + " must be a JSON object.");
            }
            T item = convert(element, elementType, path);
            for (ConstraintViolation<T> violation : validator.validate(item)) {
                errors.putIfAbsent(path + "." + violation.getPropertyPath(), violation.getMessage());
            }
            parsed.add(item);
        }
        if (!errors.isEmpty()) throw new InvalidRequestException("Validation failed", errors);
        return parsed;
    }

    private JsonNode readTree(String rawBody, String collectionName) {
        String cleaned = stripInvisibleCharacters(rawBody);
        try {
            return mapper.readTree(cleaned);
        } catch (JsonProcessingException firstFailure) {
            // Second chance: typographic double quotes are almost always paste artefacts, never intended data.
            String straightened = cleaned.replace('\u201C', '"').replace('\u201D', '"').replace('\u201E', '"');
            if (!straightened.equals(cleaned)) {
                try { return mapper.readTree(straightened); } catch (JsonProcessingException ignored) { /* report first */ }
            }
            var location = firstFailure.getLocation();
            String where = location == null ? "" : " at line " + location.getLineNr() + ", column " + location.getColumnNr();
            throw new InvalidRequestException("Malformed JSON" + where + ": " + firstFailure.getOriginalMessage()
                    + ". Expected a JSON array of " + collectionName + ".");
        }
    }

    private List<JsonNode> extractElements(JsonNode root, String collectionName) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new InvalidRequestException("Request body is empty. Send a JSON array of " + collectionName + ".");
        }
        if (root.isArray()) return toList(root);
        if (root.isObject()) {
            if (root.has(collectionName)) {
                JsonNode wrapped = root.get(collectionName);
                if (wrapped.isArray()) return toList(wrapped);
                throw new InvalidRequestException("'" + collectionName + "' must be a JSON array.");
            }
            return List.of(root); // a single bare object
        }
        throw new InvalidRequestException("Request body must be a JSON array of " + collectionName
                + " (or an object with a '" + collectionName + "' array).");
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> list = new ArrayList<>(array.size());
        array.forEach(list::add);
        return list;
    }

    private <T> T convert(JsonNode element, Class<T> type, String path) {
        try {
            return mapper.treeToValue(element, type);
        } catch (InvalidFormatException ex) {
            throw new InvalidRequestException(describeFormatError(ex, path));
        } catch (JsonProcessingException ex) {
            String field = ex instanceof JsonMappingException jme ? lastField(jme) : null;
            throw new InvalidRequestException("Invalid value" + (field == null ? "" : " for '" + field + "'")
                    + " in " + path + ": " + ex.getOriginalMessage());
        }
    }

    private static String describeFormatError(InvalidFormatException ex, String path) {
        String field = lastField(ex);
        Class<?> target = ex.getTargetType();
        if (target != null && target.isEnum()) {
            String allowed = Arrays.stream(target.getEnumConstants()).map(Object::toString).collect(Collectors.joining(", "));
            if ("priority".equals(field)) return "Invalid priority in " + path + ". Priority must be HIGH, MEDIUM, or LOW.";
            return "Invalid value for '" + field + "' in " + path + ". Allowed values: " + allowed + ".";
        }
        String expected = target == null ? "a different type" : target.getSimpleName().equals("Double") ? "a number" : target.getSimpleName();
        return "Invalid value '" + ex.getValue() + "' for '" + field + "' in " + path + ". Expected " + expected + ".";
    }

    private static String lastField(JsonMappingException ex) {
        List<JsonMappingException.Reference> path = ex.getPath();
        return path.isEmpty() ? "value" : path.get(path.size() - 1).getFieldName();
    }

    /** Replaces non-breaking/zero-width whitespace and strips a BOM; these are invisible but illegal in JSON. */
    static String stripInvisibleCharacters(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\uFEFF', '\u200B', '\u200C', '\u200D', '\u2060' -> { /* drop */ }
                case '\u00A0', '\u2007', '\u2009', '\u202F', '\u3000' -> out.append(' ');
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
