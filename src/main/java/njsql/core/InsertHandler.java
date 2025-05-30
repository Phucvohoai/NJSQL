package njsql.core;

import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import njsql.indexing.BTreeIndexManager;

import njsql.models.User;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.TreeMap;
import java.util.HashMap;

/**
 * Handles SQL INSERT commands, adding new rows to a table and updating B-Tree indexes.
 */
public class InsertHandler {

    /**
     * Processes an INSERT SQL command, adding data to the specified table and updating indexes.
     * @param sql The SQL INSERT command
     * @param username The username of the authenticated user
     * @param dbPath The path to the database directory
     * @return The name of the table
     * @throws Exception If the syntax is invalid, table doesn't exist, or write operation fails
     */
    public static String handle(String sql, String username, String dbPath) throws Exception {
        Pattern pattern = Pattern.compile("INSERT INTO (\\w+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*(.+);?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql.trim());

        if (!matcher.find()) {
            throw new Exception("Invalid INSERT syntax. Expected format: INSERT INTO <table> (<columns>) VALUES (<values>);");
        }

        String table = matcher.group(1).trim();
        String columnsPart = matcher.group(2).trim();
        String valuesSection = matcher.group(3).trim();

        // Construct table file path
        String tablePath = dbPath + "/" + table + ".nson";
        File tableFile = new File(tablePath);

        if (!tableFile.exists()) {
            throw new Exception("Table '" + table + "' does not exist in database at '" + dbPath + "'.");
        }

        // Read table data using NsonObject
        String fileContent = new String(Files.readAllBytes(tableFile.toPath()), StandardCharsets.UTF_8);
        NsonObject tableData;
        try {
            tableData = new NsonObject(fileContent);
        } catch (Exception e) {
            throw new Exception("Failed to parse JSON of table '" + table + "' at '" + tablePath + "': " + e.getMessage());
        }
        NsonObject meta = tableData.getObject("_meta");
        NsonObject types = tableData.getObject("_types");
        NsonArray data = tableData.getArray("data");

        if (meta == null || types == null || data == null) {
            throw new Exception("Invalid table structure for '" + table + "'. Missing '_meta', '_types', or 'data' in '" + tablePath + "'.");
        }

        // Parse columns
        String[] insertColumns = columnsPart.split("\\s*,\\s*");

        // Process each value tuple in VALUES
        Pattern tuplePattern = Pattern.compile("\\(([^)]+)\\)");
        Matcher tupleMatcher = tuplePattern.matcher(valuesSection);
        int newRowIndex = data.size();

        while (tupleMatcher.find()) {
            String valuesPart = tupleMatcher.group(1).trim();

            // Parse values
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

            // Create new row
            NsonObject row = new NsonObject();
            for (String colName : types.keySet()) {
                row.put(colName, null);
            }

            // Fill values from INSERT
            for (int i = 0; i < insertColumns.length; i++) {
                String colName = insertColumns[i].trim();
                if (types.containsKey(colName)) {
                    row.put(colName, insertValues.get(i));
                } else {
                    throw new Exception("Column '" + colName + "' does not exist in table '" + table + "'.");
                }
            }

            // Handle autoincrement and default values
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

            // Check for duplicate primary key
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

            // Update B-Tree indexes
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> tableJson = mapper.readValue(fileContent, Map.class);
            Map<String, Object> indexes = (Map<String, Object>) tableJson.get("_indexes");
            if (indexes != null) {
                Map<String, Object> newRecord = new HashMap<>();
                for (String key : row.keySet()) {
                    newRecord.put(key, row.get(key));
                }
                for (Map.Entry<String, Object> entry : indexes.entrySet()) {
                    String indexName = entry.getKey();
                    Map<String, Object> index = (Map<String, Object>) entry.getValue();
                    String column = (String) index.get("column");
                    BTreeIndexManager.updateIndexOnInsert(dbPath, table, column, indexName, newRecord, newRowIndex);
                }
            }

            data.add(row);
            newRowIndex++;
        }

        // Update last_modified
        meta.put("last_modified", Instant.now().toString());

        // Validate tableData before writing
        if (tableData.getObject("_meta") == null || tableData.getObject("_types") == null || tableData.getArray("data") == null) {
            throw new Exception("Invalid table data for '" + table + "': Missing '_meta', '_types', or 'data' before writing.");
        }

        // Write data to file with file locking
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

        // Verify file after writing
        if (!tableFile.exists() || tableFile.length() == 0) {
            throw new Exception("Failed to write to table '" + table + "': File is empty or does not exist after writing.");
        }

        return table;
    }
    /**
     * Parses an INSERT SQL command to create a row for real-time mode.
     * @param sql The SQL INSERT command
     * @param user The authenticated user
     * @return A Map representing the new row
     * @throws Exception If the syntax is invalid
     */
    public static Map<String, Object> parseInsert(String sql, User user) throws Exception {
        Pattern pattern = Pattern.compile("INSERT INTO (\\w+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*\\(([^)]+)\\)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql.trim());

        if (!matcher.find()) {
            throw new Exception("Invalid INSERT syntax for real-time mode. Expected: INSERT INTO <table> (<columns>) VALUES (<values>)");
        }

        String columnsPart = matcher.group(2).trim();
        String valuesPart = matcher.group(3).trim();

        String[] columns = columnsPart.split("\\s*,\\s*");
        Pattern valuePattern = Pattern.compile("'([^']*)'|([^,\\s]+)");
        Matcher valueMatcher = valuePattern.matcher(valuesPart);
        List<Object> values = new ArrayList<>();
        while (valueMatcher.find()) {
            String strVal = valueMatcher.group(1);
            String numVal = valueMatcher.group(2);
            if (strVal != null) {
                values.add(strVal);
            } else {
                try {
                    values.add(Double.parseDouble(numVal));
                } catch (NumberFormatException e) {
                    values.add(numVal);
                }
            }
        }

        if (columns.length != values.size()) {
            throw new Exception("Number of columns and values do not match in INSERT statement.");
        }

        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < columns.length; i++) {
            row.put(columns[i].trim(), values.get(i));
        }

        // Handle auto-increment and default values
        String dbName = user.getCurrentDatabase();
        if (dbName == null || dbName.isEmpty()) {
            throw new Exception("No database selected for INSERT.");
        }
        String tableName = matcher.group(1).trim();
        String tablePath = UserManager.getRootDirectory(user.getUsername()) + "/" + dbName + "/" + tableName + ".nson";
        File tableFile = new File(tablePath);
        if (!tableFile.exists()) {
            throw new Exception("Table '" + tableName + "' does not exist.");
        }

        String fileContent = new String(Files.readAllBytes(tableFile.toPath()), StandardCharsets.UTF_8);
        NsonObject tableData = new NsonObject(fileContent);
        NsonObject meta = tableData.getObject("_meta");
        NsonObject types = tableData.getObject("_types");
        NsonArray data = tableData.getArray("data");

        if (meta == null || types == null || data == null) {
            throw new Exception("Invalid table structure for '" + tableName + "'.");
        }

        NsonArray autoincrementCols = meta.getArray("autoincrement");
        if (autoincrementCols == null) {
            autoincrementCols = new NsonArray();
        }
        int maxId = 0;
        for (int i = 0; i < data.size(); i++) {
            NsonObject existingRow = data.getObject(i);
            Object idValue = existingRow.get("id");
            if (idValue instanceof Number) {
                maxId = Math.max(maxId, ((Number) idValue).intValue());
            }
        }

        for (String colName : types.keySet()) {
            if (!row.containsKey(colName)) {
                if (autoincrementCols.contains(colName)) {
                    row.put(colName, maxId + 1);
                    maxId++;
                } else if (colName.equals("created_at") && types.getString(colName).equals("datetime")) {
                    row.put(colName, Instant.now().toString());
                }
            }
        }

        return row;
    }

    /**
     * Extracts the table name from an INSERT SQL command.
     * @param sql The SQL INSERT command
     * @return The table name
     * @throws Exception If the syntax is invalid
     */
    public static String getTableName(String sql) throws Exception {
        Pattern pattern = Pattern.compile("INSERT INTO (\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(sql.trim());
        if (!matcher.find()) {
            throw new Exception("Invalid INSERT syntax. Cannot extract table name.");
        }
        return matcher.group(1).trim();
    }
}