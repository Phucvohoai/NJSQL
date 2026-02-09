package njsql.core;

import njsql.models.User;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature; // [NEW] Import này quan trọng

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.ArrayList;
import java.time.Instant;
import java.io.IOException;
import java.util.Collections; // [NEW]

public class DeleteHandler {

    // Hàm handle cho CLI (gọi lại API để tái sử dụng logic)
    public static String handle(String sql, User user) throws Exception {
        NsonObject result = handleForAPI(sql, user);
        if (result.containsKey("error")) {
            throw new Exception(result.getString("error"));
        }
        // Trả về tên bảng để SQLMode in ra (giữ logic cũ của ní)
        // Hoặc trả về message chi tiết: return result.getString("message");
        return result.getString("tableName"); 
    }

    public static NsonObject handleForAPI(String sql, User user) {
        NsonObject response = new NsonObject();
        try {
            sql = sql.replace(";", "").trim();
            Pattern pattern = Pattern.compile(
                    "(?i)DELETE FROM (\\w+)(?: WHERE (.+))?$",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
            Matcher matcher = pattern.matcher(sql);

            if (!matcher.find()) {
                return response.put("error", "Invalid DELETE syntax. Expected format: DELETE FROM <table> [WHERE <condition>];");
            }

            String table = matcher.group(1);
            String whereClause = matcher.group(2);

            String db = user.getCurrentDatabase();
            if (db == null) {
                return response.put("error", "No database selected. Please use `USE <dbname>` first.");
            }

            String rootDir = UserManager.getRootDirectory(user.getUsername());
            File file = new File(rootDir + "/" + db + "/" + table + ".nson");
            if (!file.exists()) {
                return response.put("error", "Table '" + table + "' not found in database '" + db + "'.");
            }

            String fileContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            NsonObject tableData = NsonObject.parse(fileContent);

            NsonObject meta = tableData.getObject("_meta");
            NsonObject types = tableData.getObject("_types");
            NsonArray data = tableData.getArray("data");
            if (meta == null || types == null || data == null) {
                return response.put("error", "Invalid table structure for '" + table + "'. Missing '_meta', '_types', or 'data'.");
            }
            NsonArray indexCols = meta.getArray("index");
            if (indexCols == null) indexCols = new NsonArray();

            IndexManager indexManager = new IndexManager();
            indexManager.loadIndexes(file.getPath(), data, indexCols);

            NsonArray newData = new NsonArray();
            List<Integer> deletedRowsIndices = new ArrayList<>();
            
            // Lọc dữ liệu: Giữ lại dòng KHÔNG thỏa mãn điều kiện
            for (int i = 0; i < data.size(); i++) {
                NsonObject row = data.getObject(i);
                if (whereClause == null || evaluateWhere(row, whereClause, types)) {
                    deletedRowsIndices.add(i);
                } else {
                    newData.add(row);
                }
            }

            // Xóa index của các dòng bị xóa
            for (int i : deletedRowsIndices) {
                NsonObject row = data.getObject(i);
                for (Object indexColObj : indexCols) {
                    String indexCol = indexColObj.toString();
                    Object value = row.get(indexCol);
                    if (value != null) {
                        indexManager.removeIndex(indexCol, value, i);
                    }
                }
            }

            tableData.put("data", newData);
            meta.put("last_modified", Instant.now().toString());

            // --- [FIX] GHI FILE BẰNG JACKSON & KHÓA AN TOÀN ---
            try (FileOutputStream fos = new FileOutputStream(file)) {
                FileChannel channel = fos.getChannel();
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    return response.put("error", "Cannot obtain lock on file");
                }

                try {
                    ObjectMapper mapper = new ObjectMapper();
                    mapper.enable(SerializationFeature.INDENT_OUTPUT);
                    String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tableData);
                    
                    // Ghi trực tiếp bytes để tránh lỗi ClosedChannelException
                    fos.write(json.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                } finally {
                    lock.release();
                }
            } catch (Exception e) {
                e.printStackTrace();
                return response.put("error", "Failed to write updated table '" + table + "': " + e.getMessage());
            }
            // --------------------------------------------------

            // --- [FIX] CẬP NHẬT BACKGROUND FLUSHER & REALTIME ---
            String tableKey = db + "." + table;
            System.out.println("DEBUG: Marking dirty for " + tableKey + " (DELETE)");
            njsql.core.BackgroundFlusher.markDirty(tableKey, tableData);

            if (RealtimeTableManager.ramTables.containsKey(tableKey)) {
                // Với DELETE, cập nhật lại toàn bộ RAM table bằng newData cho đồng bộ
                List<NsonObject> remainingRows = new ArrayList<>();
                for(Object obj : newData) {
                    if (obj instanceof NsonObject) remainingRows.add((NsonObject)obj);
                }
                RealtimeTableManager.updateRamTable(tableKey, remainingRows, db);
                // Thông báo (tạm thời gửi danh sách rỗng hoặc logic diff nếu cần)
                RealtimeTableManager.notifyListeners(tableKey, "DELETE", Collections.emptyList());
            }
            // ----------------------------------------------------

            int rowsAffected = deletedRowsIndices.size();
            response.put("status", "success");
            response.put("message", "Deleted " + rowsAffected + " row(s) from '" + table + "'");
            response.put("tableName", table); // Để SQLMode dùng
            response.put("rowsAffected", rowsAffected);
            return response;

        } catch (Exception e) {
            return response.put("error", e.getMessage());
        }
    }

    private static boolean evaluateWhere(NsonObject row, String whereClause, NsonObject types) throws Exception {
        whereClause = whereClause.trim();

        if (whereClause.toUpperCase().contains(" AND ")) {
            String[] conditions = whereClause.split("(?i)\\s+AND\\s+", 2);
            return evaluateWhere(row, conditions[0], types) && evaluateWhere(row, conditions[1], types);
        } else if (whereClause.toUpperCase().contains(" OR ")) {
            String[] conditions = whereClause.split("(?i)\\s+OR\\s+", 2);
            return evaluateWhere(row, conditions[0], types) || evaluateWhere(row, conditions[1], types);
        }

        Pattern opPattern = Pattern.compile("(\\w+)\\s*([<>]=?|!=|=|LIKE|IN)\\s*(.+)", Pattern.CASE_INSENSITIVE);
        Matcher opMatcher = opPattern.matcher(whereClause);

        if (!opMatcher.matches()) {
            throw new IllegalArgumentException("Unsupported WHERE clause: '" + whereClause + "'.");
        }

        String column = opMatcher.group(1).trim();
        String operator = opMatcher.group(2).trim().toUpperCase();
        String valueExpr = opMatcher.group(3).trim();

        if (!types.containsKey(column)) {
            throw new IllegalArgumentException("Column '" + column + "' does not exist in table schema.");
        }

        Object rowValue = row.get(column);
        if (rowValue == null) return false;

        String colType = types.getString(column);

        if (operator.equals("IN")) {
            if (!valueExpr.startsWith("(") || !valueExpr.endsWith(")")) {
                throw new IllegalArgumentException("Invalid IN clause format. Expected: IN (value1, value2, ...)");
            }

            String innerValues = valueExpr.substring(1, valueExpr.length() - 1);
            String[] values = innerValues.split("\\s*,\\s*");

            if (colType.equals("int") || colType.equals("float") || colType.equals("double")) {
                double rowNum = Double.parseDouble(rowValue.toString());
                for (String v : values) {
                    v = v.replaceAll("^'|'$", "");
                    double valNum = Double.parseDouble(v);
                    if (rowNum == valNum) return true;
                }
            } else {
                String rowStr = rowValue.toString();
                for (String v : values) {
                    v = v.replaceAll("^'|'$", "");
                    if (rowStr.equals(v)) return true;
                }
            }
            return false;
        }

        String cleanValue = valueExpr.replaceAll("^'|'$", "");

        if (colType.equals("int") || colType.equals("float") || colType.equals("double")) {
            double rowNum = Double.parseDouble(rowValue.toString());
            double compareNum = Double.parseDouble(cleanValue);

            return switch (operator) {
                case "=" -> rowNum == compareNum;
                case ">" -> rowNum > compareNum;
                case "<" -> rowNum < compareNum;
                case ">=" -> rowNum >= compareNum;
                case "<=" -> rowNum <= compareNum;
                case "!=" -> rowNum != compareNum;
                default -> throw new IllegalArgumentException("Unsupported operator '" + operator + "' for numeric comparison.");
            };
        } else if (operator.equals("LIKE")) {
            String regex = cleanValue.replace("%", ".*").replace("_", ".");
            return rowValue.toString().matches(regex);
        } else {
            String rowStr = rowValue.toString();

            return switch (operator) {
                case "=" -> rowStr.equals(cleanValue);
                case "!=" -> !rowStr.equals(cleanValue);
                case ">" -> rowStr.compareTo(cleanValue) > 0;
                case "<" -> rowStr.compareTo(cleanValue) < 0;
                case ">=" -> rowStr.compareTo(cleanValue) >= 0;
                case "<=" -> rowStr.compareTo(cleanValue) <= 0;
                default -> throw new IllegalArgumentException("Unsupported operator '" + operator + "' for string comparison.");
            };
        }
    }
}