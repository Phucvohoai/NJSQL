package njsql.nson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public class NsonArray extends ArrayList<Object> {

    // ✅ Constructor đọc từ chuỗi JSON
    public NsonArray() {}

    public NsonArray(String json) {
        JSONArray jsonArray = new JSONArray(json);
        for (int i = 0; i < jsonArray.length(); i++) {
            Object val = jsonArray.get(i);
            if (val instanceof JSONObject) {
                add(new NsonObject(val.toString()));
            } else if (val instanceof JSONArray) {
                add(new NsonArray(val.toString()));
            } else {
                add(val);
            }
        }
    }

    public static NsonArray parse(String json) {
        return new NsonArray(json);
    }

    // ✅ Convert thành JSONArray gốc
    public JSONArray toJSONArray() {
        JSONArray jsonArray = new JSONArray();
        for (Object val : this) {
            if (val instanceof NsonObject) {
                jsonArray.put(((NsonObject) val).toJSONObject());
            } else if (val instanceof NsonArray) {
                jsonArray.put(((NsonArray) val).toJSONArray());
            } else {
                jsonArray.put(val);
            }
        }
        return jsonArray;
    }

    // ✅ In chuỗi JSON đẹp
    @Override
    public String toString() {
        return toJSONArray().toString();
    }

    public String toString(int indentFactor) {
        return toJSONArray().toString(indentFactor);
    }

    // ✅ Giữ nguyên tiện ích Cừu đã có
    public NsonObject getObject(int index) {
        Object value = get(index);
        if (value instanceof NsonObject) {
            return (NsonObject) value;
        }
        return null;
    }

    public NsonArray addValue(Object value) {
        this.add(value);
        return this;
    }

    public String getString(int index) {
        Object value = get(index);
        return value != null ? value.toString() : null;
    }

    public boolean contains(String value) {
        for (Object obj : this) {
            if (obj != null && obj.toString().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    public void put(Object value) { // tương thích cách dùng cũ
        this.add(value);
    }
}
