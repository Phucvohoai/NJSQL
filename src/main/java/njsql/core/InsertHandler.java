package njsql.core;

import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import njsql.models.User;

import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;

// Import Jackson
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class InsertHandler {

    public static String handle(String sql, String username, String dbPath) throws Exception {
        User tempUser = new User(username, "", "", 0); 
        String dbName = new File(dbPath).getName();
        tempUser.setCurrentDatabase(dbName); 
        NsonObject result = handleForAPI(sql, tempUser);
        return result.containsKey("error") ? "ERROR: " + result.getString("error") : result.getString("message");
    }

    public static NsonObject handleForAPI(String sql, User user) {
        NsonObject response = new NsonObject();
        try {
            sql = sql.replace(";", "").trim();

            Pattern pattern = Pattern.compile("INSERT INTO (\\w+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*([^;]+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher matcher = pattern.matcher(sql);

            if (!matcher.find()) {
                return response.put("error", "Invalid INSERT syntax. Expected: INSERT INTO <table> (<columns>) VALUES (<values>[,...])");
            }

            String table = matcher.group(1).trim();
            String columnsPart = matcher.group(2).trim();
            String valuesSection = matcher.group(3).trim();

            String db = user.getCurrentDatabase();
            if (db == null) return response.put("error", "No database selected");

            String rootDir = UserManager.getRootDirectory(user.getUsername());
            String tablePath = rootDir + "/" + db + "/" + table + ".nson";
            File tableFile = new File(tablePath);

            if (!tableFile.exists()) {
                return response.put("error", "Table '" + table + "' not found");
            }

            String fileContent = new String(Files.readAllBytes(tableFile.toPath()), StandardCharsets.UTF_8);
            NsonObject tableData = NsonObject.parse(fileContent);
            NsonObject meta = tableData.getObject("_meta");
            NsonObject types = tableData.getObject("_types");
            NsonArray data = tableData.getArray("data");

            if (meta == null || types == null || data == null) {
                return response.put("error", "Invalid table structure");
            }

            NsonArray indexCols = meta.getArray("index");
            if (indexCols == null) indexCols = new NsonArray();

            IndexManager indexManager = new IndexManager();
            indexManager.loadIndexes(tablePath, data, indexCols);

            String[] insertColumns = columnsPart.split("\\s*,\\s*");
            List<String> valueTuples = parseValueTuples(valuesSection);
            int newRowIndex = data.size();
            List<NsonObject> insertedRows = new ArrayList<>();

            for (String valuesPart : valueTuples) {
                List<Object> insertValues = parseValues(valuesPart);
                if (insertColumns.length != insertValues.size()) {
                    return response.put("error", "Column and value count mismatch");
                }

                NsonObject row = new NsonObject();
                for (String colName : types.keySet()) row.put(colName, null);

                for (int i = 0; i < insertColumns.length; i++) {
                    String colName = insertColumns[i].trim();
                    if (!types.containsKey(colName)) return response.put("error", "Column '" + colName + "' not found");
                    row.put(colName, insertValues.get(i));
                }

                NsonArray autoincrementCols = meta.getArray("autoincrement");
                if (autoincrementCols == null) autoincrementCols = new NsonArray();
                NsonArray primaryKeyCols = meta.getArray("primary_key");
                if (primaryKeyCols == null) primaryKeyCols = new NsonArray();
                
                int maxId = 0;
                for (int i = 0; i < data.size(); i++) {
                    NsonObject existingRow = data.getObject(i);
                    Object idValue = existingRow.get("id");
                    if (idValue != null && idValue instanceof Number) maxId = Math.max(maxId, ((Number) idValue).intValue());
                }

                for (String colName : types.keySet()) {
                    if (row.get(colName) == null) {
                        if (autoincrementCols.contains(colName)) {
                            row.put(colName, maxId + 1);
                            maxId++;
                        } else if (colName.equals("created_at") && types.getString(colName).equals("datetime")) {
                            row.put(colName, Instant.now().toString());
                        }
                    }
                }

                if (!primaryKeyCols.isEmpty()) {
                    // (Logic check Primary Key giữ nguyên - tui rút gọn để đỡ dài, ní giữ nguyên code cũ đoạn này nha)
                    // ... Check Duplicate PK ...
                }

                for (Object indexColObj : indexCols) {
                    String indexCol = indexColObj.toString();
                    Object value = row.get(indexCol);
                    if (value != null) {
                        indexManager.updateIndex(indexCol, value, newRowIndex);
                    }
                }

                data.add(row);
                insertedRows.add(new NsonObject(row.toString()));
                newRowIndex++;
            }

            meta.put("last_modified", Instant.now().toString());

            // --- [FIXED] GHI FILE AN TOÀN TRÁNH CLOSED CHANNEL ---
            try (FileOutputStream fos = new FileOutputStream(tableFile)) {
                FileChannel channel = fos.getChannel();
                FileLock lock = channel.tryLock(); // Thử khóa file
                
                if (lock == null) {
                    return response.put("error", "Cannot obtain lock on table file '" + tablePath + "'");
                }

                try {
                    // 1. Tạo JSON String bằng Jackson
                    ObjectMapper mapper = new ObjectMapper();
                    mapper.enable(SerializationFeature.INDENT_OUTPUT);
                    String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tableData);
                    
                    // 2. Ghi trực tiếp Byte (Tránh dùng Writer đóng stream sớm)
                    fos.write(json.getBytes(StandardCharsets.UTF_8));
                    fos.flush(); // Đẩy dữ liệu xuống đĩa ngay
                    
                } finally {
                    // 3. Giải phóng lock (Lúc này Channel vẫn còn mở nên OK)
                    lock.release();
                }
            } catch (Exception e) {
                e.printStackTrace(); 
                return response.put("error", "Failed to write updated table '" + table + "': " + e.getMessage());
            }
            // -----------------------------------------------------

            int rowsAffected = valueTuples.size();

            // Notify BackgroundFlusher
            String tableKey = db + "." + table;
            System.out.println("DEBUG: Marking dirty for " + tableKey);
            njsql.core.BackgroundFlusher.markDirty(tableKey, tableData);

            if (RealtimeTableManager.ramTables.containsKey(tableKey)) {
                List<Map<String, Object>> ram = RealtimeTableManager.ramTables.get(tableKey);
                for (NsonObject insertedRow : insertedRows) {
                    ram.add(insertedRow.toMap());
                }
                RealtimeTableManager.notifyListeners(tableKey, "INSERT", insertedRows);
            }

            return response
                    .put("status", "success")
                    .put("message", "Inserted " + rowsAffected + " row(s) into '" + table + "'")
                    .put("rowsAffected", rowsAffected);

        } catch (Exception e) {
            return response.put("error", e.getMessage());
        }
    }
    
    // ... (Giữ nguyên các hàm helper parseValueTuples, parseValues, parseValue, getTableName...) ...
    public static String getTableName(String sql) throws Exception {
        Pattern pattern = Pattern.compile("INSERT INTO (\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(sql.trim());
        if (!matcher.find()) throw new Exception("Invalid INSERT syntax. Cannot extract table name.");
        return matcher.group(1).trim();
    }

    private static List<String> parseValueTuples(String valuesSection) throws Exception {
        List<String> tuples = new ArrayList<>();
        StringBuilder currentTuple = new StringBuilder();
        int parenLevel = 0;
        boolean inQuote = false;
        char prevChar = 0;
        valuesSection = valuesSection.replaceAll("\\r?\\n", " ").replaceAll("\\s+", " ").trim();
        for (int i = 0; i < valuesSection.length(); i++) {
            char c = valuesSection.charAt(i);
            if (c == '\'' && prevChar != '\\') inQuote = !inQuote;
            else if (c == '(' && !inQuote) parenLevel++;
            else if (c == ')' && !inQuote) {
                parenLevel--;
                if (parenLevel == 0) {
                    currentTuple.append(c);
                    String tupleStr = currentTuple.toString().trim();
                    if (tupleStr.startsWith("(") && tupleStr.endsWith(")")) tuples.add(tupleStr.substring(1, tupleStr.length() - 1));
                    currentTuple.setLength(0);
                    continue;
                }
            } else if (c == ',' && parenLevel == 0 && !inQuote) continue;
            if (parenLevel > 0) currentTuple.append(c);
            prevChar = c;
        }
        if (currentTuple.length() > 0) {
            String tupleStr = currentTuple.toString().trim();
            if (tupleStr.startsWith("(") && tupleStr.endsWith(")")) tuples.add(tupleStr.substring(1, tupleStr.length() - 1));
        }
        if (tuples.isEmpty()) throw new Exception("No valid value tuples found in VALUES clause: " + valuesSection);
        return tuples;
    }

    private static List<Object> parseValues(String valuesPart) throws Exception {
        List<Object> values = new ArrayList<>();
        StringBuilder currentValue = new StringBuilder();
        boolean inQuote = false;
        char prevChar = 0;
        for (int i = 0; i < valuesPart.length(); i++) {
            char c = valuesPart.charAt(i);
            if (c == '\'' && prevChar != '\\') {
                inQuote = !inQuote;
                if (!inQuote) { values.add(currentValue.toString()); currentValue.setLength(0); }
            } else if (c == ',' && !inQuote) {
                String valueStr = currentValue.toString().trim();
                if (!valueStr.isEmpty()) values.add(parseValue(valueStr));
                currentValue.setLength(0);
            } else if (inQuote || !Character.isWhitespace(c) || currentValue.length() > 0) {
                if (!inQuote && Character.isWhitespace(c) && currentValue.length() == 0) continue;
                currentValue.append(c);
            }
            prevChar = c;
        }
        if (currentValue.length() > 0) {
            String valueStr = currentValue.toString().trim();
            if (!valueStr.isEmpty()) values.add(parseValue(valueStr));
        }
        return values;
    }

    private static Object parseValue(String valueStr) {
        valueStr = valueStr.trim();
        if (valueStr.equalsIgnoreCase("NULL")) return null;
        if (valueStr.startsWith("'") && valueStr.endsWith("'")) return valueStr.substring(1, valueStr.length() - 1);
        try {
            if (valueStr.contains(".")) return Double.parseDouble(valueStr);
            else return Integer.parseInt(valueStr);
        } catch (NumberFormatException e) {
            return valueStr;
        }
    }
}