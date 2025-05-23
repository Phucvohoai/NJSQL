package njsql.core;

import njsql.models.User;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

import java.io.File;
import java.nio.file.Files;

public class TableUtils {

    public static JSONObject getSchema(User user, String db, String table) throws Exception {
        File schemaFile = new File("njsql_data/" + user.getUsername() + "/" + db + "/" + table + "_schema.json");
        if (!schemaFile.exists()) throw new Exception("Schema file not found.");
        String schema = new String(Files.readAllBytes(schemaFile.toPath()), StandardCharsets.UTF_8);
        return new JSONObject(schema);
    }

    public static int getColumnIndex(JSONObject schema, String column) throws Exception {
        JSONArray cols = schema.getJSONArray("columns");
        for (int i = 0; i < cols.length(); i++) {
            if (cols.getJSONObject(i).getString("name").equalsIgnoreCase(column)) {
                return i;
            }
        }
        throw new Exception("Column '" + column + "' not found.");
    }
}
