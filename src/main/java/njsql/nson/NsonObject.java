package njsql.nson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NsonObject - Wrapper cho JSON Object, dùng Jackson để parse/serialize
 */
public class NsonObject extends LinkedHashMap<String, Object> implements Cloneable {
    private static final ObjectMapper mapper = new ObjectMapper();

    public NsonObject() {
        super();
    }

    /**
     * Parse từ chuỗi JSON object
     */
    public NsonObject(String json) {
        super();
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException("JSON must be an object");
            }
            addFromJsonNode(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON object: " + e.getMessage(), e);
        }
    }

    public static NsonObject parse(String json) {
        return new NsonObject(json);
    }

    protected void addFromJsonNode(JsonNode node) {   // ← thêm protected
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode child = entry.getValue();
            put(key, convertJsonNode(child));
        });
    }

    private Object convertJsonNode(JsonNode node) {
    if (node == null || node.isNull()) {
        return null;
    }
    if (node.isObject()) {
        NsonObject obj = new NsonObject();  // constructor rỗng
        obj.addFromJsonNode(node);          // parse recursive trực tiếp
        return obj;
    } else if (node.isArray()) {
        NsonArray arr = new NsonArray();    // constructor rỗng
        arr.addFromJsonNode(node);          // parse recursive trực tiếp
        return arr;
    } else if (node.isTextual()) {
        return node.textValue();
    } else if (node.isIntegralNumber()) {
        return node.asLong();
    } else if (node.isFloatingPointNumber()) {
        return node.asDouble();
    } else if (node.isBoolean()) {
        return node.asBoolean();
    } else {
        return node.asText();  // fallback an toàn
    }
}

    /**
     * Convert sang ObjectNode của Jackson
     */
    public ObjectNode toObjectNode() {
        ObjectNode node = mapper.createObjectNode();
        forEach((key, value) -> {
            if (value instanceof NsonObject) {
                node.set(key, ((NsonObject) value).toObjectNode());
            } else if (value instanceof NsonArray) {
                node.set(key, ((NsonArray) value).toArrayNode());
            } else {
                node.putPOJO(key, value);
            }
        });
        return node;
    }

    @Override
    public String toString() {
        return toObjectNode().toString();
    }

    public String toString(int indentFactor) {
        return toObjectNode().toPrettyString();
    }

    @Override
    public NsonObject put(String key, Object value) {
        super.put(key, value);
        return this;
    }

    public NsonArray getArray(String key) {
        Object value = get(key);
        return value instanceof NsonArray ? (NsonArray) value : null;
    }

    public NsonObject getObject(String key) {
        Object value = get(key);
        return value instanceof NsonObject ? (NsonObject) value : null;
    }

    public String getString(String key) {
        Object value = get(key);
        return value != null ? value.toString() : null;
    }

    public String getString(String key, String defaultValue) {
        Object value = get(key);
        return value != null ? value.toString() : defaultValue;
    }

    public int getInt(String key) {
        Object value = get(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    public int optInt(String key, int defaultValue) {
        Object value = get(key);
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }

    public boolean getBoolean(String key) {
        Object value = get(key);
        return value instanceof Boolean ? (Boolean) value : false;
    }

    public boolean optBoolean(String key, boolean defaultValue) {
        Object value = get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    public NsonObject getAsObject() {
        return this;
    }

    public Map<String, Object> toMap() {
        return new LinkedHashMap<>(this);
    }

    /**
     * Clone an toàn và nhanh bằng Jackson
     */
    @Override
    public NsonObject clone() {
        try {
            return new NsonObject(this.toString());
        } catch (Exception e) {
            throw new RuntimeException("Clone failed: " + e.getMessage(), e);
        }
    }

    // Bonus: Merge từ object khác
    public NsonObject merge(NsonObject other) {
        if (other != null) {
            putAll(other);
        }
        return this;
    }

    // Bonus: Xóa các key có value null
    public void removeNulls() {
        entrySet().removeIf(entry -> entry.getValue() == null);
    }
}