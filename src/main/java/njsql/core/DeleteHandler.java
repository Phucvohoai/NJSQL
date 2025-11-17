package njsql.core;

import njsql.models.User;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import com.fasterxml.jackson.databind.ObjectMapper;

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

public class DeleteHandler {

    public static String handle(String sql, User user) throws Exception {
        sql = sql.replace(";", "").trim();
        Pattern pattern = Pattern.compile(
                "(?i)DELETE FROM (\\w+)(?: WHERE (.+))?$",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(sql);

        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid DELETE syntax. Expected format: DELETE FROM <table> [WHERE <condition>];");
        }

        String table = matcher.group(1);
        String whereClause = matcher.group(2);

        String db = user.getCurrentDatabase();
        if (db == null) {
            throw new IllegalArgumentException("No database selected. Please use `USE <dbname>` first.");
        }

        String rootDir = UserManager.getRootDirectory(user.getUsername());
        File file = new File(rootDir + "/" + db + "/" + table + ".nson");
        if (!file.exists()) {
            throw new IllegalArgumentException("Table '" + table + "' not found in database '" + db + "'.");
        }

        String fileContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        NsonObject tableData;
        try {
            tableData = NsonObject.parse(fileContent);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON of table '" + table + "' at '" + file.getPath() + "': " + e.getMessage());
        }

        NsonObject meta = tableData.getObject("_meta");
        NsonObject types = tableData.getObject("_types");
        NsonArray data = tableData.getArray("data");
        if (meta == null || types == null || data == null) {
            StringBuilder errorMsg = new StringBuilder("Invalid table structure for '" + table + "'. Missing: ");
            if (meta == null) errorMsg.append("_meta ");
            if (types == null) errorMsg.append("_types ");
            if (data == null) errorMsg.append("data ");
            throw new IllegalArgumentException(errorMsg.toString());
        }
        NsonArray indexCols = meta.getArray("index");
        if (indexCols == null) {
            indexCols = new NsonArray();
        }

        IndexManager indexManager = new IndexManager();
        indexManager.loadIndexes(file.getPath(), data, indexCols);

        NsonArray newData = new NsonArray();
        List<Integer> deletedRows = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            NsonObject row = data.getObject(i);
            if (whereClause == null || evaluateWhere(row, whereClause, types)) {
                deletedRows.add(i);
            } else {
                newData.add(row);
            }
        }

        for (int i : deletedRows) {
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

        ObjectMapper mapper = new ObjectMapper();
        try {
            // Xóa tệp cũ với xử lý lỗi
            try {
                Files.deleteIfExists(file.toPath());
            } catch (Exception e) {
                System.err.println("ERROR: Failed to delete old file '" + file.getPath() + "': " + e.getClass().getName() + " - " + (e.getMessage() != null ? e.getMessage() : "Unknown error"));
                throw new Exception("Failed to delete old file '" + table + "': " + (e.getMessage() != null ? e.getMessage() : "Unknown error"));
            }

            // Ghi tệp mới
            try (FileOutputStream fos = new FileOutputStream(file)) {
                // Lấy kênh và khóa tệp sau khi mở luồng
                FileChannel channel = fos.getChannel();
                FileLock lock = null;

                try {
                    // Thử khóa tệp
                    lock = channel.tryLock();
                    if (lock == null) {
                        throw new IOException("Cannot obtain lock on file");
                    }

                    // Ghi dữ liệu
                    try (OutputStreamWriter fw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                        fw.write(tableData.toString(2));
                    }
                } finally {
                    if (lock != null) {
                        lock.release();
                    }
                }
            }
        } catch (Exception e) {
            throw new Exception("Failed to write updated table '" + table + "': " + e.getMessage());
        }

        return "Deleted " + deletedRows.size() + " row(s) from '" + table + "'";
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
            if (indexCols == null) {
                indexCols = new NsonArray();
            }

            IndexManager indexManager = new IndexManager();
            indexManager.loadIndexes(file.getPath(), data, indexCols);

            NsonArray newData = new NsonArray();
            List<Integer> deletedRows = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                NsonObject row = data.getObject(i);
                if (whereClause == null || evaluateWhere(row, whereClause, types)) {
                    deletedRows.add(i);
                } else {
                    newData.add(row);
                }
            }

            for (int i : deletedRows) {
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

            ObjectMapper mapper = new ObjectMapper();
            try {
                Files.deleteIfExists(file.toPath());

                try (FileOutputStream fos = new FileOutputStream(file)) {
                    FileChannel channel = fos.getChannel();
                    FileLock lock = channel.tryLock();
                    if (lock == null) {
                        return response.put("error", "Cannot obtain lock on file");
                    }

                    try (OutputStreamWriter fw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                        fw.write(tableData.toString(2));
                    } finally {
                        lock.release();
                    }
                }
            } catch (Exception e) {
                return response.put("error", "Failed to write updated table '" + table + "': " + e.getMessage());
            }

            int rowsAffected = deletedRows.size();
            response.put("status", "success");
            response.put("message", "Deleted " + rowsAffected + " row(s) from '" + table + "'");
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