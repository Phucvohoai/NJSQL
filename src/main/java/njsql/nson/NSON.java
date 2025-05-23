package njsql.nson;

import org.json.JSONArray;
import org.json.JSONObject;

public class NSON {
    public static NsonObject parse(String json) {
        JSONObject jsonObject = new JSONObject(json);
        return toNsonObject(jsonObject);
    }

    private static NsonObject toNsonObject(JSONObject jsonObject) {
        NsonObject nson = new NsonObject();
        for (String key : jsonObject.keySet()) {
            Object value = jsonObject.get(key);
            if (value instanceof JSONObject) {
                nson.put(key, toNsonObject((JSONObject) value));
            } else if (value instanceof JSONArray) {
                nson.put(key, toNsonArray((JSONArray) value));
            } else {
                nson.put(key, value);
            }
        }
        return nson;
    }

    private static NsonArray toNsonArray(JSONArray jsonArray) {
        NsonArray array = new NsonArray();
        for (int i = 0; i < jsonArray.length(); i++) {
            Object value = jsonArray.get(i);
            if (value instanceof JSONObject) {
                array.add(toNsonObject((JSONObject) value));
            } else if (value instanceof JSONArray) {
                array.add(toNsonArray((JSONArray) value));
            } else {
                array.add(value);
            }
        }
        return array;
    }
}
