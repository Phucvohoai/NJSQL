package njsql.core;

import njsql.nson.NsonObject;
import njsql.nson.NsonArray;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;

public class InsertHandler {

    public static String handle(String sql, String username, String dbPath) throws Exception {
        Pattern pattern = Pattern.compile("INSERT INTO (\\w+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*(.+);?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql.trim());

        if (!matcher.find()) {
            throw new Exception("Invalid INSERT syntax. Expected format: INSERT INTO <table> (<columns>) VALUES (<values>);");
        }

        String table = matcher.group(1).trim();
        String columnsPart = matcher.group(2).trim();
        String valuesSection = matcher.group(3).trim();

        // Sử dụng dbPath để xây dựng đường dẫn bảng
        String tablePath = dbPath + "/" + table + ".nson";
        File tableFile = new File(tablePath);

        if (!tableFile.exists()) {
            throw new Exception("Table '" + table + "' does not exist in database at '" + dbPath + "'.");
        }

        // Đọc file .nson bằng NsonObject
        String fileContent = new String(Files.readAllBytes(tableFile.toPath()), StandardCharsets.UTF_8);
        NsonObject tableData;
        try {
            tableData = new NsonObject(fileContent);
        } catch (org.json.JSONException e) {
            throw new Exception("Failed to parse JSON of table '" + table + "' at '" + tablePath + "': " + e.getMessage());
        }
        NsonObject meta = tableData.getObject("_meta");
        NsonObject types = tableData.getObject("_types");
        NsonArray data = tableData.getArray("data");

        if (meta == null || types == null || data == null) {
            throw new Exception("Invalid table structure for '" + table + "'. Missing '_meta', '_types', or 'data' in '" + tablePath + "'.");
        }

        NsonArray indexCols = meta.getArray("index");
        if (indexCols == null) {
            indexCols = new NsonArray();
        }

        // Khởi tạo IndexManager
        IndexManager indexManager = new IndexManager();
        indexManager.loadIndexes(tablePath, data, indexCols);

        // Tách danh sách cột
        String[] insertColumns = columnsPart.split("\\s*,\\s*");

        // Xử lý từng bộ giá trị trong VALUES
        Pattern tuplePattern = Pattern.compile("\\(([^)]+)\\)");
        Matcher tupleMatcher = tuplePattern.matcher(valuesSection);
        int newRowIndex = data.size();

        while (tupleMatcher.find()) {
            String valuesPart = tupleMatcher.group(1).trim();

            // Tách giá trị
            Pattern valuePattern = Pattern.compile("'([^']*)'|([^,\\s]+)");
            Matcher valueMatcher = valuePattern.matcher(valuesPart);

            NsonArray insertValues = new NsonArray();
            while (valueMatcher.find()) {
                String strVal = valueMatcher.group(1);
                String numVal = valueMatcher.group(2);
                if (strVal != null) {
                    insertValues.add(strVal);
                } else {
                    try {
                        insertValues.add(Double.parseDouble(numVal));
                    } catch (NumberFormatException e) {
                        insertValues.add(numVal);
                    }
                }
            }

            if (insertColumns.length != insertValues.size()) {
                throw new Exception("Number of columns and values do not match.");
            }

            // Tạo hàng mới
            NsonObject row = new NsonObject();
            for (String colName : types.keySet()) {
                row.put(colName, null);
            }

            // Điền giá trị từ INSERT
            for (int i = 0; i < insertColumns.length; i++) {
                String colName = insertColumns[i].trim();
                if (types.containsKey(colName)) {
                    row.put(colName, insertValues.get(i));
                } else {
                    throw new Exception("Column '" + colName + "' does not exist in table '" + table + "'.");
                }
            }

            // Xử lý autoincrement và default
            NsonArray autoincrementCols = meta.getArray("autoincrement");
            if (autoincrementCols == null) {
                autoincrementCols = new NsonArray();
            }
            NsonArray primaryKeyCols = meta.getArray("primary_key");
            if (primaryKeyCols == null) {
                primaryKeyCols = new NsonArray();
            }
            int maxId = 0;
            for (int i = 0; i < data.size(); i++) {
                NsonObject existingRow = data.getObject(i);
                Object idValue = existingRow.get("id");
                if (idValue != null && idValue instanceof Number) {
                    maxId = Math.max(maxId, ((Number) idValue).intValue());
                }
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

            // Kiểm tra trùng primary key
            for (Object pkColObj : primaryKeyCols) {
                String pkCol = pkColObj.toString();
                Object newPkValue = row.get(pkCol);
                if (newPkValue != null) {
                    for (int j = 0; j < data.size(); j++) {
                        NsonObject existingRow = data.getObject(j);
                        if (existingRow.get(pkCol) != null && existingRow.get(pkCol).equals(newPkValue)) {
                            throw new Exception("Duplicate primary key '" + newPkValue + "' for column '" + pkCol + "' in table '" + table + "'.");
                        }
                    }
                }
            }

            // Cập nhật chỉ mục
            for (Object indexColObj : indexCols) {
                String indexCol = indexColObj.toString();
                Object value = row.get(indexCol);
                if (value != null) {
                    indexManager.updateIndex(indexCol, value, newRowIndex);
                }
            }

            data.add(row);
            newRowIndex++;
        }

        // Cập nhật last_modified
        meta.put("last_modified", Instant.now().toString());

        // Kiểm tra tableData trước khi ghi
        if (tableData.getObject("_meta") == null || tableData.getObject("_types") == null || tableData.getArray("data") == null) {
            throw new Exception("Invalid table data for '" + table + "': Missing '_meta', '_types', or 'data' before writing.");
        }

        // Ghi dữ liệu vào file với khóa tệp
        FileOutputStream fos = null;
        FileChannel channel = null;
        FileLock lock = null;
        OutputStreamWriter writer = null;
        try {
            fos = new FileOutputStream(tableFile);
            channel = fos.getChannel();
            lock = channel.lock();
            writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            String json = tableData.toString(2);
            writer.write(json);
            writer.flush();
        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception("Failed to write to table '" + table + "' at '" + tablePath + "': " + e.getClass().getSimpleName() + (e.getMessage() != null ? " - " + e.getMessage() : ""));
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception e) {
                    System.err.println("Warning: Failed to close OutputStreamWriter: " + e.getMessage());
                }
            }
            if (lock != null && lock.isValid()) {
                try {
                    lock.release();
                } catch (Exception e) {
                    System.err.println("Warning: Failed to release FileLock: " + e.getMessage());
                }
            }
            if (channel != null) {
                try {
                    channel.close();
                } catch (Exception e) {
                    System.err.println("Warning: Failed to close FileChannel: " + e.getMessage());
                }
            }
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception e) {
                    System.err.println("Warning: Failed to close FileOutputStream: " + e.getMessage());
                }
            }
        }

        // Kiểm tra tệp sau khi ghi
        if (!tableFile.exists() || tableFile.length() == 0) {
            throw new Exception("Failed to write to table '" + table + "': File is empty or does not exist after writing.");
        }

        return table;
    }
}