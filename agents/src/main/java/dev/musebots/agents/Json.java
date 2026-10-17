package dev.musebots.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JSON encoding for ACL message content. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode " + value, e);
        }
    }

    public static <T> T read(String content, Class<T> type) {
        try {
            return MAPPER.readValue(content, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot decode " + type.getSimpleName() + " from " + content, e);
        }
    }
}
