package njsql.nson;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.LinkedHashMap;
import java.util.Map;

public class NsonObject extends LinkedHashMap<String, Object> {
    public NsonObject() {}

    public NsonObject(String json) {
        JSONObject jsonObject = new JSONObject(json);
        for (String key : jsonObject.keySet()) {
            Object val = jsonObject.get(key);
            if (val instanceof JSONObject) {
                put(key, new NsonObject(val.toString()));
            } else if (val instanceof JSONArray) {
                put(key, new NsonArray(val.toString()));
            } else {
                put(key, val);
            }
        }
    }

    public static NsonObject parse(String json) {
        return new NsonObject(json);
    }

    @Override
    public String toString() {
        return toJSONObject().toString();
    }

    public String toString(int indentFactor) {
        return toJSONObject().toString(indentFactor);
    }

    @Override
    public NsonObject put(String key, Object value) {
        super.put(key, value);
        return this;
    }

    public NsonArray getArray(String key) {
        Object value = get(key);
        if (value instanceof NsonArray) {
            return (NsonArray) value;
        }
        return null;
    }

    public NsonObject getObject(String key) {
        Object value = get(key);
        if (value instanceof NsonObject) {
            return (NsonObject) value;
        }
        return null;
    }

    public String getString(String key) {
        Object value = get(key);
        return value != null ? value.toString() : null;
    }

    public int getInt(String key) {
        Object value = get(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    public boolean getBoolean(String key) {
        Object value = get(key);
        return value instanceof Boolean ? (Boolean) value : false;
    }

    public NsonObject getAsObject() {
        return this;
    }
    public JSONObject toJSONObject() {
        JSONObject jsonObject = new JSONObject();
        for (Map.Entry<String, Object> entry : this.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof NsonObject) {
                jsonObject.put(entry.getKey(), ((NsonObject) value).toJSONObject());
            } else if (value instanceof NsonArray) {
                jsonObject.put(entry.getKey(), ((NsonArray) value).toJSONArray());
            } else {
                jsonObject.put(entry.getKey(), value);
            }
        }
        return jsonObject;
    }
    public boolean optBoolean(String key, boolean defaultValue) {
        Object value = get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue; // Trả về giá trị mặc định nếu không phải boolean
    }

}
