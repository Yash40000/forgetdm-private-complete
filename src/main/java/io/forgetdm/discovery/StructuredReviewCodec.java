package io.forgetdm.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** Persists analyst decisions for logical PII fields inside one structured physical column. */
final class StructuredReviewCodec {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Field(String selector, String piiType, double confidence, String sampleValue,
                 String status, String suggestedFunction, String suggestedParam1, String suggestedParam2) {
        Field withDecision(String nextStatus, String function, String param1, String param2) {
            return new Field(selector, piiType, confidence, sampleValue,
                    nextStatus == null ? status : nextStatus,
                    function == null ? suggestedFunction : function,
                    param1, param2);
        }
    }

    private StructuredReviewCodec() {}

    static List<Field> decode(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.of(JSON.readValue(json, Field[].class));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Structured review evidence is invalid", e);
        }
    }

    static String encode(List<Field> fields) {
        try {
            return JSON.writeValueAsString(fields == null ? List.of() : List.copyOf(fields));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Structured review evidence could not be saved", e);
        }
    }
}
