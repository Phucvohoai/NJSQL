package njsql.core;

import njsql.nson.NsonObject;
import njsql.models.User;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class DescribeHandler {

    public static List<String[]> handle(String tableName, User user) throws Exception {
        String db = user.getCurrentDatabase();
        String tablePath = "njsql_data/" + user.getUsername() + "/" + db + "/" + tableName + ".nson";

        File tableFile = new File(tablePath);
        if (!tableFile.exists()) {
            throw new IllegalArgumentException("Table '" + tableName + "' does not exist.");
        }

        String jsonContent = new String(Files.readAllBytes(tableFile.toPath()));
        NsonObject tableData = NsonObject.parse(jsonContent);

        NsonObject types = tableData.getObject("_types");
        if (types == null) {
            throw new IllegalArgumentException("Invalid table structure for '" + tableName + "': Missing '_types'.");
        }

        List<String[]> result = new ArrayList<>();
        for (String key : types.keySet()) {
            String type = types.getString(key);
            result.add(new String[] { key, type });
        }

        return result;
    }
}