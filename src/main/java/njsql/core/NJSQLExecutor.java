package njsql.core;

import njsql.utils.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.Consumer;
import java.io.File;

public class NJSQLExecutor {

    public static JSONObject runQuery(String user, String db, String sql, Consumer<String> logger) {
        JSONObject result = new JSONObject();

        try {
            // Tách câu lệnh: SELECT * FROM Product
            String[] parts = sql.trim().split("\\s+");
            if (parts.length < 4 || !parts[0].equalsIgnoreCase("SELECT")) {
                result.put("error", "Chỉ hỗ trợ SELECT đơn giản.");
                return result;
            }

            String tableName = parts[3].replace(";", "");
            String tablePath = String.format("njsql_data/%s/%s/%s.json", user, db, tableName);

            File tableFile = new File(tablePath);
            if (!tableFile.exists()) {
                result.put("error", "Bảng không tồn tại: " + tableName);
                return result;
            }

            String tableData = FileUtils.readFileUtf8(tablePath);
            JSONArray tableJson = new JSONArray(tableData);

            // Kiểm tra có WHERE không
            if (sql.toUpperCase().contains("WHERE")) {
                // Lọc thủ công
                String whereField = parts[5]; // giả định: WHERE age > 20
                String operator = parts[6];
                String value = parts[7].replace(";", "").replaceAll("\"", "");

                JSONArray filtered = new JSONArray();
                for (int i = 0; i < tableJson.length(); i++) {
                    JSONObject row = tableJson.getJSONObject(i);
                    if (row.has(whereField)) {
                        String fieldVal = row.get(whereField).toString();
                        if (checkCondition(fieldVal, operator, value)) {
                            filtered.put(row);
                        }
                    }
                }
                result.put("data", filtered);
                result.put("status", "filtered");
            } else {
                result.put("data", tableJson);
                result.put("status", "full");
            }

        } catch (Exception e) {
            result.put("error", "Lỗi xử lý: " + e.getMessage());
        }

        return result;
    }

    private static boolean checkCondition(String val, String op, String target) {
        try {
            double v1 = Double.parseDouble(val);
            double v2 = Double.parseDouble(target);
            return switch (op) {
                case ">" -> v1 > v2;
                case "<" -> v1 < v2;
                case "=" -> v1 == v2;
                case ">=" -> v1 >= v2;
                case "<=" -> v1 <= v2;
                case "!=" -> v1 != v2;
                default -> false;
            };
        } catch (Exception e) {
            return val.equals(target);
        }
    }
}
