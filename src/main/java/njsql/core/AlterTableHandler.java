package njsql.core;

import njsql.utils.FileUtils;
import njsql.models.User;
import njsql.nson.NsonObject;
import java.io.File;
import java.io.IOException;

public class AlterTableHandler {

    public static void handle(String sql, User user) {
        String[] tokens = sql.split("\\s+");
        if (tokens.length < 5) {
            System.out.println("\u001B[31m>> ERROR: Invalid ALTER TABLE syntax.\u001B[0m");
            return;
        }

        String tableName = tokens[2].trim();
        String alterAction = tokens[3].trim();

        if (alterAction.equalsIgnoreCase("add")) {
            String columnDefinition = sql.substring(sql.toLowerCase().indexOf("add") + 4).trim();
            if (columnDefinition.endsWith(";")) {
                columnDefinition = columnDefinition.substring(0, columnDefinition.length() - 1);
            }

            String[] colParts = columnDefinition.split("\\s+", 2);
            if (colParts.length != 2) {
                System.out.println("\u001B[31m>> ERROR: Invalid column definition.\u001B[0m");
                return;
            }

            String columnName = colParts[0].trim();
            String dataType = colParts[1].trim();

            // Lấy tên cơ sở dữ liệu hiện tại
            String dbName = user.getCurrentDatabase();
            if (dbName == null || dbName.isEmpty()) {
                System.out.println("\u001B[31m>> ERROR: No database selected. Please use `USE <dbname>` first.\u001B[0m");
                return;
            }

            // Lấy thư mục gốc của người dùng và xác định đường dẫn đến tệp bảng
            String rootDir = UserManager.getRootDirectory(user.getUsername());
            String tablePath = rootDir + "/" + dbName + "/" + tableName + ".nson";
            File tableFile = new File(tablePath);
            if (!tableFile.exists()) {
                System.out.println("\u001B[31m>> ERROR: Table '" + tableName + "' not found.\u001B[0m");
                return;
            }

            try {
                // Đọc dữ liệu từ file
                String tableContent = FileUtils.readFileUtf8(tablePath);
                NsonObject tableData = NsonObject.parse(tableContent);

                // Kiểm tra xem '_types' có tồn tại không, nếu không tạo mới
                NsonObject types = tableData.getObject("_types");
                if (types == null) {
                    types = new NsonObject();
                    tableData.put("_types", types);
                }

                // Thêm cột vào _types nếu chưa có
                if (!types.containsKey(columnName)) {
                    types.put(columnName, dataType);

                    // Cập nhật dữ liệu vào file
                    FileUtils.writeFileUtf8(tablePath, tableData.toString(2));

                    System.out.println(">>\u001B[32m Success: Column |\u001B[0m " + columnName + "\u001B[32m | added to table '" + tableName + "'.\u001B[0m");
                } else {
                    System.out.println("\u001B[31m>> ERROR: Column '" + columnName + "' already exists in _types of table '" + tableName + "'.\u001B[0m");
                }
            } catch (IOException e) {
                System.out.println("\u001B[31m>> ERROR: Failed to update table file - " + e.getMessage() + "\u001B[0m");
            }
        } else {
            System.out.println("\u001B[31m>> ERROR: ALTER TABLE only supports ADD column for now.\u001B[0m");
        }
    }
}
