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

public class InsertHandler {

    public static String handle(String sql, String username, String dbPath) throws Exception {
        Pattern pattern = Pattern.compile("INSERT INTO (\\w+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*([^;]+);?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql.trim());

        if (!matcher.matches()) {
            throw new Exception("Invalid INSERT syntax. Expected format: INSERT INTO <table> (<columns>) VALUES (<values>[,<values> ...]);");
        }

        String table = matcher.group(1).trim();
        String columnsPart = matcher.group(2).trim();
        String valuesSection = matcher.group(3).trim();

        String tablePath = dbPath + "/" + table + ".nson";
        File tableFile = new File(tablePath);

        if (!tableFile.exists()) {
            throw new Exception("Table '" + table + "' does not exist in database at '" + dbPath + "'.");
        }

        String fileContent = new String(Files.readAllBytes(tableFile.toPath()), StandardCharsets.UTF_8);
        NsonObject tableData;
        try {
            tableData = NsonObject.parse(fileContent);
        } catch (Exception e) {
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

        IndexManager indexManager = new IndexManager();
        indexManager.loadIndexes(tablePath, data, indexCols);

        String[] insertColumns = columnsPart.split("\\s*,\\s*");

        List<String> valueTuples = parseValueTuples(valuesSection);
        int newRowIndex = data.size();

        for (String valuesPart : valueTuples) {
            List<Object> insertValues = parseValues(valuesPart);
            if (insertColumns.length != insertValues.size()) {
                throw new Exception("Number of columns (" + insertColumns.length + ") and values (" + insertValues.size() + ") do not match for tuple: " + valuesPart);
            }

            NsonObject row = new NsonObject();
            for (String colName : types.keySet()) {
                row.put(colName, null);
            }

            for (int i = 0; i < insertColumns.length; i++) {
                String colName = insertColumns[i].trim();
                if (!types.containsKey(colName)) {
                    throw new Exception("Column '" + colName + "' does not exist in table '" + table + "'.");
                }
                row.put(colName, insertValues.get(i));
            }

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

            if (!primaryKeyCols.isEmpty()) {
                List<String> pkCols = new ArrayList<>();
                for (Object pkColObj : primaryKeyCols) {
                    pkCols.add(pkColObj.toString());
                }
                StringBuilder pkValueKey = new StringBuilder();
                for (String pkCol : pkCols) {
                    Object value = row.get(pkCol);
                    pkValueKey.append(value != null ? value.toString() : "null").append("|");
                }
                String pkKey = pkValueKey.toString();
                for (int j = 0; j < data.size(); j++) {
                    NsonObject existingRow = data.getObject(j);
                    StringBuilder existingPkKey = new StringBuilder();
                    for (String pkCol : pkCols) {
                        Object value = existingRow.get(pkCol);
                        existingPkKey.append(value != null ? value.toString() : "null").append("|");
                    }
                    if (existingPkKey.toString().equals(pkKey)) {
                        throw new Exception("Duplicate primary key '" + pkKey + "' for columns " + pkCols + " in table '" + table + "'.");
                    }
                }
            }

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

        meta.put("last_modified", Instant.now().toString());

        if (tableData.getObject("_meta") == null || tableData.getObject("_types") == null || tableData.getArray("data") == null) {
            throw new Exception("Invalid table data for '" + table + "': Missing '_meta', '_types', or 'data' before writing.");
        }

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

        if (!tableFile.exists() || tableFile.length() == 0) {
            throw new Exception("Failed to write to table '" + table + "': File is empty or does not exist after writing.");
        }

        return table;
    }

    public static Map<String, Object> parseInsert(String sql, User user) throws Exception {
        Pattern pattern = Pattern.compile("INSERT INTO (\\w+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*\\(([^)]+)\\);?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql.trim());

        if (!matcher.matches()) {
            throw new Exception("Invalid INSERT syntax. Expected format: INSERT INTO <table> (<columns>) VALUES (<values>);");
        }

        String tableName = matcher.group(1).trim();
        String columnsPart = matcher.group(2).trim();
        String valuesPart = matcher.group(3).trim();

        String dbName = user.getCurrentDatabase();
        if (dbName == null || dbName.isEmpty()) {
            throw new Exception("No database selected for INSERT.");
        }

        String[] insertColumns = columnsPart.split("\\s*,\\s*");

        List<Object> insertValues = parseValues(valuesPart);
        if (insertColumns.length != insertValues.size()) {
            throw new Exception("Number of columns (" + insertColumns.length + ") and values (" + insertValues.size() + ") do not match for tuple: " + valuesPart);
        }

        String rootDir = UserManager.getRootDirectory(user.getUsername());
        String tablePath = rootDir + "/" + dbName + "/" + tableName + ".nson";
        File tableFile = new File(tablePath);
        if (!tableFile.exists()) {
            throw new Exception("Table '" + tableName + "' does not exist at '" + tablePath + "'. Check if table was created successfully.");
        }
        if (!tableFile.canRead() || tableFile.length() == 0) {
            throw new Exception("Table file '" + tableName + "' at '" + tablePath + "' is inaccessible or empty. Check file permissions or integrity.");
        }

        String fileContent = new String(Files.readAllBytes(tableFile.toPath()), StandardCharsets.UTF_8);
        NsonObject tableData;
        try {
            tableData = NsonObject.parse(fileContent);
        } catch (Exception e) {
            throw new Exception("Failed to parse table '" + tableName + "': " + e.getMessage());
        }

        NsonObject meta = tableData.getObject("_meta");
        NsonObject types = tableData.getObject("_types");
        NsonArray data = tableData.getArray("data");

        if (meta == null || types == null || data == null) {
            throw new Exception("Invalid table structure for '" + tableName + "'.");
        }

        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < insertColumns.length; i++) {
            String colName = insertColumns[i].trim();
            if (!types.containsKey(colName)) {
                throw new Exception("Column '" + colName + "' does not exist in table '" + tableName + "'.");
            }
            row.put(colName, insertValues.get(i));
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

    public static String getTableName(String sql) throws Exception {
        Pattern pattern = Pattern.compile("INSERT INTO (\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(sql.trim());
        if (!matcher.find()) {
            throw new Exception("Invalid INSERT syntax. Cannot extract table name.");
        }
        return matcher.group(1).trim();
    }

    // Trong InsertHandler.java, thay thế method parseValueTuples()

    private static List<String> parseValueTuples(String valuesSection) throws Exception {
        List<String> tuples = new ArrayList<>();
        StringBuilder currentTuple = new StringBuilder();
        int parenLevel = 0;
        boolean inQuote = false;
        char prevChar = 0;

        // Normalize line endings và remove extra whitespace
        valuesSection = valuesSection.replaceAll("\\r?\\n", " ").replaceAll("\\s+", " ").trim();

        for (int i = 0; i < valuesSection.length(); i++) {
            char c = valuesSection.charAt(i);

            if (c == '\'' && prevChar != '\\') {
                inQuote = !inQuote;
            } else if (c == '(' && !inQuote) {
                parenLevel++;
            } else if (c == ')' && !inQuote) {
                parenLevel--;
                if (parenLevel == 0) {
                    currentTuple.append(c);
                    String tupleStr = currentTuple.toString().trim();
                    if (tupleStr.startsWith("(") && tupleStr.endsWith(")")) {
                        tuples.add(tupleStr.substring(1, tupleStr.length() - 1)); // Remove outer parentheses
                    }
                    currentTuple.setLength(0);
                    continue;
                }
            } else if (c == ',' && parenLevel == 0 && !inQuote) {
                // Skip commas between tuples
                continue;
            }

            if (parenLevel > 0) {
                currentTuple.append(c);
            }

            prevChar = c;
        }

        if (currentTuple.length() > 0) {
            String tupleStr = currentTuple.toString().trim();
            if (tupleStr.startsWith("(") && tupleStr.endsWith(")")) {
                tuples.add(tupleStr.substring(1, tupleStr.length() - 1));
            }
        }

        if (tuples.isEmpty()) {
            throw new Exception("No valid value tuples found in VALUES clause: " + valuesSection);
        }
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
                if (!inQuote) {
                    // End of quoted string
                    values.add(currentValue.toString());
                    currentValue.setLength(0);
                }
            } else if (c == ',' && !inQuote) {
                // End of unquoted value
                String valueStr = currentValue.toString().trim();
                if (!valueStr.isEmpty()) {
                    values.add(parseValue(valueStr));
                }
                currentValue.setLength(0);
            } else if (inQuote || !Character.isWhitespace(c) || currentValue.length() > 0) {
                if (!inQuote && Character.isWhitespace(c) && currentValue.length() == 0) {
                    // Skip leading whitespace for unquoted values
                    continue;
                }
                currentValue.append(c);
            }

            prevChar = c;
        }

        // Handle last value
        if (currentValue.length() > 0) {
            String valueStr = currentValue.toString().trim();
            if (!valueStr.isEmpty()) {
                values.add(parseValue(valueStr));
            }
        }

        return values;
    }

    private static Object parseValue(String valueStr) {
        valueStr = valueStr.trim();

        // Handle NULL values
        if (valueStr.equalsIgnoreCase("NULL")) {
            return null;
        }

        // Handle quoted strings - don't process them as numbers
        if (valueStr.startsWith("'") && valueStr.endsWith("'")) {
            return valueStr.substring(1, valueStr.length() - 1);
        }

        // Try to parse as number
        try {
            if (valueStr.contains(".")) {
                return Double.parseDouble(valueStr);
            } else {
                return Integer.parseInt(valueStr);
            }
        } catch (NumberFormatException e) {
            // Return as string if not a number
            return valueStr;
        }
    }
}