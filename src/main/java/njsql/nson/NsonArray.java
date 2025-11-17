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

    // ✅ FIX: Thêm annotation để tránh warning
    public NsonObject getObject(int index) {
        if (index < 0 || index >= size()) {
            return null;
        }
        Object value = get(index);
        if (value instanceof NsonObject) {
            return (NsonObject) value;
        }
        return null;
    }

    // ✅ THÊM METHOD MỚI: getNsonObject để rõ ràng hơn
    public NsonObject getNsonObject(int index) {
        return getObject(index);
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

    // ✅ THÊM METHOD CLONE ARRAY
    public NsonArray cloneArray() {
        NsonArray cloned = new NsonArray();
        for (Object item : this) {
            if (item instanceof NsonObject) {
                cloned.add(((NsonObject) item).clone());
            } else if (item instanceof NsonArray) {
                cloned.add(((NsonArray) item).cloneArray());
            } else {
                cloned.add(item);
            }
        }
        return cloned;
    }
}