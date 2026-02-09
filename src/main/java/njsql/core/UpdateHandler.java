package njsql.core;

import njsql.models.User;
import njsql.nson.NsonArray;
import njsql.nson.NsonObject;
import njsql.indexing.BTreeIndexManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializationFeature; // Import thêm cái này cho đẹp

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

public class UpdateHandler {

    private static final List<NsonObject> updatedRows = new ArrayList<>();

    public static String handle(String sql, User user) throws Exception {
        sql = sql.replace(";", "").trim();

        Pattern pattern = Pattern.compile(
                "(?i)UPDATE\\s+(\\w+)\\s+SET\\s+(.+?)(?:\\s+WHERE\\s+(.+))?$",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(sql);

        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid UPDATE syntax.");
        }

        String table = matcher.group(1).trim();
        String setClause = matcher.group(2).trim();
        String whereClause = matcher.group(3);

        Map<String, String> updates = parseSetClause(setClause);
        if (updates.isEmpty()) throw new IllegalArgumentException("Invalid SET clause.");

        String db = user.getCurrentDatabase();
        if (db == null) throw new IllegalArgumentException("No database selected.");

        String rootDir = UserManager.getRootDirectory(user.getUsername());
        File file = new File(rootDir + "/" + db + "/" + table + ".nson");
        if (!file.exists()) throw new IllegalArgumentException("Table '" + table + "' not found.");

        String fileContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        NsonObject nson = NsonObject.parse(fileContent);

        NsonObject meta = nson.getObject("_meta");
        NsonObject types = nson.getObject("_types");
        NsonArray data = nson.getArray("data");
        if (meta == null || types == null || data == null) {
            throw new IllegalArgumentException("Invalid table structure.");
        }

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT); // Format JSON đẹp

        // Đọc file thành Map để lấy _indexes
        TypeReference<Map<String, Object>> typeRef = new TypeReference<>() {};
        Map<String, Object> tableJson = mapper.readValue(fileContent, typeRef);
        Object indexesObj = tableJson.get("_indexes");
        Map<String, Object> indexes = indexesObj instanceof Map<?, ?> ? castToStringObjectMap(indexesObj) : Collections.emptyMap();

        updatedRows.clear();
        int updatedCount = 0;

        for (int i = 0; i < data.size(); i++) {
            Object rawRow = data.get(i);
            if (rawRow == null || !(rawRow instanceof NsonObject)) {
                throw new IllegalStateException("Row at index " + i + " is not a valid NsonObject");
            }
            NsonObject row = (NsonObject) rawRow;

            if (whereClause == null || evaluateWhere(row, whereClause, types, rootDir + "/" + db, table)) {
                for (Map.Entry<String, String> update : updates.entrySet()) {
                    String col = update.getKey();
                    if (!types.containsKey(col)) {
                        throw new IllegalArgumentException("Column '" + col + "' does not exist.");
                    }
                    String val = update.getValue();
                    Object newValue = types.getString(col).equals("int") && isNumeric(val) ? Integer.parseInt(val) : val;
                    row.put(col, newValue);

                    if (!indexes.isEmpty()) {
                        for (Map.Entry<String, Object> entry : indexes.entrySet()) {
                            String indexName = entry.getKey();
                            Object indexObj = entry.getValue();
                            if (!(indexObj instanceof Map<?, ?> indexMap)) continue;

                            Object columnObj = indexMap.get("column");
                            if (!(columnObj instanceof String indexedColumn)) continue;

                            if (col.equals(indexedColumn)) {
                                BTreeIndexManager.updateIndexOnUpdate(rootDir + "/" + db, table, col, indexName, i, newValue);
                            }
                        }
                    }
                }
                updatedCount++;
                updatedRows.add(row.clone());
            }
        }

        if (updatedCount > 0) {
            meta.put("last_modified", Instant.now().toString());
            nson.put("_meta", meta);
            
            // --- [FIX 1] Ghi file an toàn (tránh ClosedChannelException) ---
            writeWithLock(file, nson, mapper);

            // --- [FIX 2] Báo cáo BackgroundFlusher ---
            String tableKey = db + "." + table;
            System.out.println("DEBUG: Marking dirty for " + tableKey + " (UPDATE)");
            njsql.core.BackgroundFlusher.markDirty(tableKey, nson);
        }

        String tableKey = db + "." + table;
        if (RealtimeTableManager.ramTables.containsKey(tableKey)) {
            RealtimeTableManager.updateRamTable(tableKey, updatedRows, db);
            RealtimeTableManager.notifyListeners(tableKey, "UPDATE", updatedRows);
        }

        return "Updated " + updatedCount + " row(s) in table '" + table + "'.";
    }

    public static NsonObject handleForAPI(String sql, User user) {
        NsonObject response = new NsonObject();
        try {
            String msg = handle(sql, user);
            return response
                    .put("status", "success")
                    .put("message", msg)
                    .put("rowsAffected", Integer.parseInt(msg.split(" ")[1]));
        } catch (Exception e) {
            return response.put("error", e.getMessage());
        }
    }

    public static List<NsonObject> getUpdatedRows() {
        return new ArrayList<>(updatedRows);
    }

    // === HELPER METHODS ===

    private static Map<String, String> parseSetClause(String setClause) {
        Map<String, String> updates = new HashMap<>();
        Pattern p = Pattern.compile("(\\w+)\\s*=\\s*('[^']*'|\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(setClause);
        while (m.find()) {
            String col = m.group(1).trim();
            String val = m.group(2).trim().replaceAll("^'|'$", "");
            updates.put(col, val);
        }
        return updates;
    }

    private static boolean evaluateWhere(NsonObject row, String whereClause, NsonObject types, String dbPath, String table) throws Exception {
        if (whereClause == null || whereClause.trim().isEmpty()) return true;
        if (whereClause.toUpperCase().contains(" AND ")) {
            for (String cond : whereClause.split("(?i)\\s+AND\\s+")) {
                if (!evaluateWhere(row, cond, types, dbPath, table)) return false;
            }
            return true;
        }
        if (whereClause.toUpperCase().contains(" OR ")) {
            for (String cond : whereClause.split("(?i)\\s+OR\\s+")) {
                if (evaluateWhere(row, cond, types, dbPath, table)) return true;
            }
            return false;
        }

        String trimmed = whereClause.trim();
        String operator = null;
        int opIndex = -1;
        String[] ops = {">=", "<=", "!=", "<>", "=", ">", "<"};
        for (String op : ops) {
            int idx = trimmed.toUpperCase().indexOf(" " + op + " ");
            if (idx == -1) continue;
            operator = op;
            opIndex = idx + op.length() + 1;
            break;
        }
        if (operator == null) throw new IllegalArgumentException("Invalid WHERE operator.");

        String column = trimmed.substring(0, trimmed.toUpperCase().indexOf(" " + operator)).trim();
        String valueStr = trimmed.substring(opIndex).trim();
        if (valueStr.startsWith("'") && valueStr.endsWith("'")) {
            valueStr = valueStr.substring(1, valueStr.length() - 1);
        }

        if (!types.containsKey(column)) throw new IllegalArgumentException("Column not found.");

        Object rowVal = row.get(column);
        if (rowVal == null) return false;

        String type = types.getString(column);
        if (operator.equals("IN")) {
            for (String v : valueStr.split(",")) {
                v = v.trim().replaceAll("^'|'$", "");
                if (compareValues(rowVal, v, "=", type)) return true;
            }
            return false;
        }
        return compareValues(rowVal, valueStr, operator, type);
    }

    private static boolean compareValues(Object a, Object b, String op, String type) {
        if (a == null && b == null) return op.equals("=");
        if (a == null || b == null) return false;
        try {
            double da = Double.parseDouble(a.toString());
            double db = Double.parseDouble(b.toString());
            return switch (op) {
                case "=" -> da == db;
                case "!=" -> da != db;
                case ">" -> da > db;
                case "<" -> da < db;
                case ">=" -> da >= db;
                case "<=" -> da <= db;
                default -> false;
            };
        } catch (Exception e) {
            boolean eq = a.toString().equals(b.toString());
            return op.equals("=") ? eq : !eq;
        }
    }

    private static boolean isNumeric(String s) {
        try { Integer.parseInt(s); return true; } catch (Exception e) { return false; }
    }

    // --- [FIXED] GHI FILE AN TOÀN TUYỆT ĐỐI ---
    private static void writeWithLock(File file, NsonObject nson, ObjectMapper mapper) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            FileChannel channel = fos.getChannel();
            FileLock lock = channel.tryLock();
            
            if (lock == null) {
                throw new IOException("Cannot obtain lock on file");
            }

            try {
                // 1. Convert sang chuỗi JSON
                String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(nson);
                
                // 2. Ghi byte trực tiếp (tránh dùng Writer gây đóng luồng sớm)
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.flush(); // Đẩy dữ liệu xuống đĩa ngay
            } finally {
                // 3. Giải phóng lock (An toàn)
                lock.release();
            }
        }
    }

    public static String getTableName(String sql) throws Exception {
        Matcher m = Pattern.compile("UPDATE (\\w+)", Pattern.CASE_INSENSITIVE).matcher(sql.trim());
        if (!m.find()) throw new Exception("Cannot extract table name.");
        return m.group(1).trim();
    }

    // === HELPER CAST AN TOÀN ===
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToStringObjectMap(Object obj) {
        return obj instanceof Map<?, ?> map ? (Map<String, Object>) map : Collections.emptyMap();
    }
}