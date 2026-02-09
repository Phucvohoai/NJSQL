package njsql.nson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Factory để parse JSON thành NsonObject/NsonArray (full Jackson)
 */
public class NSON {
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Parse chuỗi JSON thành NsonObject (phải là object root)
     */
    public static NsonObject parse(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException("JSON root must be an object for NsonObject");
            }
            return convertToNsonObject(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON to NsonObject: " + e.getMessage(), e);
        }
    }

    private static NsonObject convertToNsonObject(JsonNode node) {
        NsonObject nson = new NsonObject();
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode valueNode = entry.getValue();
            nson.put(key, convertJsonNode(valueNode));
        });
        return nson;
    }

    private static Object convertJsonNode(JsonNode node) {
        if (node.isObject()) {
            return convertToNsonObject(node);
        } else if (node.isArray()) {
            NsonArray array = new NsonArray();
            node.elements().forEachRemaining(child -> array.add(convertJsonNode(child)));
            return array;
        } else if (node.isTextual()) {
            return node.textValue();
        } else if (node.isIntegralNumber()) {
            return node.asLong();
        } else if (node.isFloatingPointNumber()) {
            return node.asDouble();
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else {
            return null;
        }
    }
}