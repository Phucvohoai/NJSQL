package njsql.nson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * NsonArray - Wrapper cho JSON Array, dùng Jackson để parse/serialize nhanh
 */
public class NsonArray extends ArrayList<Object> {
    private static final ObjectMapper mapper = new ObjectMapper();

    public NsonArray() {}

    /**
     * Parse từ chuỗi JSON array
     */
    public NsonArray(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isArray()) {
                throw new IllegalArgumentException("JSON must be an array");
            }
            addFromJsonNode(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON array: " + e.getMessage(), e);
        }
    }

    public static NsonArray parse(String json) {
        return new NsonArray(json);
    }

    protected void addFromJsonNode(JsonNode node) {   // ← thêm protected
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("Node must be a non-null array");
        }
        Iterator<JsonNode> elements = node.elements();
        while (elements.hasNext()) {
            JsonNode child = elements.next();
            add(convertJsonNode(child));
        }
    }

    
    private Object convertJsonNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            NsonObject obj = new NsonObject();
            obj.addFromJsonNode(node);
            return obj;
        } else if (node.isArray()) {
            NsonArray arr = new NsonArray();
            arr.addFromJsonNode(node);
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
            return node.asText();
        }
    }   

    /**
     * Convert sang ArrayNode của Jackson
     */
    public ArrayNode toArrayNode() {
        ArrayNode arrayNode = mapper.createArrayNode();
        for (Object val : this) {
            if (val instanceof NsonObject) {
                arrayNode.add(((NsonObject) val).toObjectNode());
            } else if (val instanceof NsonArray) {
                arrayNode.add(((NsonArray) val).toArrayNode());
            } else {
                arrayNode.addPOJO(val);
            }
        }
        return arrayNode;
    }

    @Override
    public String toString() {
        return toArrayNode().toString();
    }

    public String toString(int indentFactor) {
        return toArrayNode().toPrettyString();
    }

    public NsonObject getObject(int index) {
        if (index < 0 || index >= size()) return null;
        Object value = get(index);
        return value instanceof NsonObject ? (NsonObject) value : null;
    }

    public NsonObject getNsonObject(int index) {
        return getObject(index);
    }

    public String getString(int index) {
        Object value = get(index);
        return value != null ? value.toString() : null;
    }

    public String optString(int index, String defaultValue) {
        Object value = get(index);
        return value != null ? value.toString() : defaultValue;
    }

    public NsonArray addValue(Object value) {
        add(value);
        return this;
    }

    public boolean contains(String value) {
        for (Object obj : this) {
            if (obj != null && obj.toString().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    public void put(Object value) {
        add(value);
    }

    /**
     * Clone nhanh bằng cách serialize + parse lại
     */
    public NsonArray cloneArray() {
        try {
            return new NsonArray(this.toString());
        } catch (Exception e) {
            throw new RuntimeException("Clone failed: " + e.getMessage(), e);
        }
    }

    // Bonus: filter functional
    public NsonArray filter(Predicate<Object> predicate) {
        NsonArray filtered = new NsonArray();
        for (Object item : this) {
            if (predicate.test(item)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    // Bonus: map transform
    public <T> NsonArray map(Function<Object, T> function) {
        NsonArray mapped = new NsonArray();
        for (Object item : this) {
            mapped.add(function.apply(item));
        }
        return mapped;
    }
}