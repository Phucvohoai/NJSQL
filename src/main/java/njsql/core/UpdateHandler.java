package njsql.core;

import njsql.models.User;
import njsql.nson.NsonArray;
import njsql.nson.NsonObject;
import njsql.indexing.BTreeIndexManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;
import java.util.TreeMap;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * Handles SQL UPDATE commands, modifying rows in a table and updating B-Tree indexes.
 */
public class UpdateHandler {

    /**
     * Processes an UPDATE SQL command, modifying matching rows and updating indexes.
     * @param sql The SQL UPDATE command
     * @param user The authenticated user
     * @return A message indicating the number of updated rows
     * @throws Exception If the syntax is invalid, table doesn't exist, or write operation fails
     */
    public static String handle(String sql, User user) throws Exception {
        sql = sql.replace(";", "").trim();

        Pattern pattern = Pattern.compile(
                "(?i)UPDATE\\s+(\\w+)\\s+SET\\s+(.+?)(?:\\s+WHERE\\s+(.+))?$",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(sql);

        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid UPDATE syntax. Expected format: UPDATE <table> SET <column>=<value>[,...] [WHERE <condition>];");
        }

        String table = matcher.group(1).trim();
        String setClause = matcher.group(2).trim();
        String whereClause = matcher.group(3);

        Map<String, String> updates = new HashMap<>();
        Pattern setPattern = Pattern.compile("(\\w+)\\s*=\\s*('[^']*'|\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher setMatcher = setPattern.matcher(setClause);
        while (setMatcher.find()) {
            String col = setMatcher.group(1).trim();
            String val = setMatcher.group(2).trim().replaceAll("^'|'$", "");
            updates.put(col, val);
        }
        if (updates.isEmpty()) {
            throw new IllegalArgumentException("Invalid SET clause: '" + setClause + "'. Expected format: <column>=<value>[,...]");
        }

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
        NsonObject nson;
        try {
            nson = NsonObject.parse(fileContent);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON of table '" + table + "' at '" + file.getPath() + "': " + e.getMessage());
        }

        NsonObject meta = nson.getObject("_meta");
        NsonObject types = nson.getObject("_types");
        NsonArray data = nson.getArray("data");
        if (meta == null || types == null || data == null) {
            StringBuilder errorMsg = new StringBuilder("Invalid table structure for '" + table + "'. Missing: ");
            if (meta == null) errorMsg.append("_meta ");
            if (types == null) errorMsg.append("_types ");
            if (data == null) errorMsg.append("data ");
            throw new IllegalArgumentException(errorMsg.toString());
        }

        int updatedCount = 0;

        // Load indexes
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> tableJson = mapper.readValue(fileContent, Map.class);
        Map<String, Object> indexes = (Map<String, Object>) tableJson.get("_indexes");

        for (int i = 0; i < data.size(); i++) {
            NsonObject row = data.getObject(i);
            if (whereClause == null || evaluateWhereSimple(row, whereClause, types, rootDir + "/" + db, table)) {
                for (Map.Entry<String, String> update : updates.entrySet()) {
                    String col = update.getKey();
                    if (!types.containsKey(col)) {
                        throw new IllegalArgumentException("Column '" + col + "' does not exist in table '" + table + "'.");
                    }
                    String val = update.getValue();
                    Object oldValue = row.get(col);
                    Object newValue = types.getString(col).equals("int") && isNumeric(val) ? Integer.parseInt(val) : val;
                    row.put(col, newValue);

                    // Update B-Tree index if column is indexed
                    if (indexes != null) {
                        for (Map.Entry<String, Object> entry : indexes.entrySet()) {
                            String indexName = entry.getKey();
                            Map<String, Object> index = (Map<String, Object>) entry.getValue();
                            String indexedColumn = (String) index.get("column");
                            if (col.equals(indexedColumn)) {
                                BTreeIndexManager.updateIndexOnUpdate(rootDir + "/" + db, table, col, indexName, i, newValue);
                            }
                        }
                    }
                }
                updatedCount++;
            }
        }

        meta.put("last_modified", Instant.now().toString());

        // Write updated data to file
        FileOutputStream fos = null;
        OutputStreamWriter fw = null;
        FileChannel channel = null;
        FileLock lock = null;

        try {
            fos = new FileOutputStream(file);
            channel = fos.getChannel();
            lock = channel.lock();

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(nson);
            fw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            fw.write(json);
            fw.flush();

            return "Updated " + updatedCount + " rows in table '" + table + "'.";
        } catch (Exception e) {
            throw new Exception("Failed to write to table '" + table + "': " + e.getMessage());
        } finally {
            try {
                if (lock != null) lock.release();
                if (fw != null) fw.close();
                if (channel != null) channel.close();
                if (fos != null) fos.close();
            } catch (Exception e) {
                System.err.println("Warning: Failed to close resources: " + e.getMessage());
            }
        }
    }

    /**
     * Checks if a string is a valid integer.
     */
    private static boolean isNumeric(String val) {
        try {
            Integer.parseInt(val);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Evaluates a WHERE clause for a row, leveraging B-Tree indexes for equality conditions.
     */
    private static boolean evaluateWhereSimple(NsonObject row, String whereClause, NsonObject types, String dbPath, String table) throws Exception {
        if (whereClause == null || whereClause.trim().isEmpty()) {
            return true;
        }

        // Handle AND/OR conditions
        if (whereClause.toUpperCase().contains(" AND ")) {
            String[] conditions = whereClause.split("(?i)\\s+AND\\s+");
            for (String condition : conditions) {
                if (!evaluateWhereSimple(row, condition, types, dbPath, table)) {
                    return false;
                }
            }
            return true;
        }

        if (whereClause.toUpperCase().contains(" OR ")) {
            String[] conditions = whereClause.split("(?i)\\s+OR\\s+");
            for (String condition : conditions) {
                if (evaluateWhereSimple(row, condition, types, dbPath, table)) {
                    return true;
                }
            }
            return false;
        }

        // Parse simple condition
        String trimmedClause = whereClause.trim();
        String operator = null;
        int operatorIndex = -1;

        String[] twoCharOps = {">=", "<=", "!=", "<>"};
        for (String op : twoCharOps) {
            if (trimmedClause.contains(op)) {
                operator = op;
                operatorIndex = trimmedClause.indexOf(op);
                break;
            }
        }

        if (operator == null) {
            String[] oneCharOps = {"=", ">", "<"};
            for (String op : oneCharOps) {
                if (trimmedClause.contains(op)) {
                    operator = op;
                    operatorIndex = trimmedClause.indexOf(op);
                    break;
                }
            }
        }

        if (operator == null) {
            if (trimmedClause.toUpperCase().contains(" LIKE ")) {
                operator = "LIKE";
                operatorIndex = trimmedClause.toUpperCase().indexOf(" LIKE ");
            } else if (trimmedClause.toUpperCase().contains(" IN ")) {
                operator = "IN";
                operatorIndex = trimmedClause.toUpperCase().indexOf(" IN ");
            }
        }

        if (operator == null || operatorIndex == -1) {
            throw new IllegalArgumentException("Unsupported WHERE clause: '" + whereClause +
                    "'. Expected format: <column> [=|>|<|>=|<=|!=|LIKE|IN] 'value'|number|(<values>)");
        }

        String column = trimmedClause.substring(0, operatorIndex).trim();
        String valueStr;

        if (operator.equals("IN")) {
            int openParen = trimmedClause.indexOf('(', operatorIndex);
            int closeParen = trimmedClause.lastIndexOf(')');
            if (openParen == -1 || closeParen == -1) {
                throw new IllegalArgumentException("Invalid IN clause syntax in: '" + whereClause + "'");
            }
            valueStr = trimmedClause.substring(openParen + 1, closeParen).trim();
        } else {
            valueStr = trimmedClause.substring(operatorIndex + operator.length()).trim();
        }

        if (valueStr.startsWith("'") && valueStr.endsWith("'")) {
            valueStr = valueStr.substring(1, valueStr.length() - 1);
        }

        if (!types.containsKey(column)) {
            throw new IllegalArgumentException("Column '" + column + "' does not exist in table schema.");
        }

        // Check for index on equality condition
        if (operator.equals("=")) {
            ObjectMapper mapper = new ObjectMapper();
            File file = new File(dbPath + "/" + table + ".nson");
            Map<String, Object> tableJson = mapper.readValue(file, Map.class);
            Map<String, Object> indexes = (Map<String, Object>) tableJson.get("_indexes");
            String indexName = null;
            if (indexes != null) {
                for (Map.Entry<String, Object> entry : indexes.entrySet()) {
                    Map<String, Object> index = (Map<String, Object>) entry.getValue();
                    if (column.equals(index.get("column"))) {
                        indexName = entry.getKey();
                        break;
                    }
                }
            }

            if (indexName != null) {
                Map<String, Object> index = (Map<String, Object>) indexes.get(indexName);
                Map<String, List<Integer>> indexMap = (Map<String, List<Integer>>) index.get("map");
                if (indexMap instanceof LinkedHashMap) {
                    indexMap = new TreeMap<>(indexMap); // Chuyển sang TreeMap nếu cần
                }
                String key = types.getString(column).equalsIgnoreCase("int") ? valueStr : valueStr;
                List<Integer> positions = indexMap.get(key);
                if (positions != null && positions.contains(row.getInt("_index"))) {
                    return true;
                }
                return false;
            }
        }

        Object rowValue = row.get(column);
        if (rowValue == null) return false;

        String type = types.getString(column);

        if (operator.equalsIgnoreCase("IN")) {
            String[] values = valueStr.split("\\s*,\\s*");
            for (String value : values) {
                value = value.trim();
                if (value.startsWith("'") && value.endsWith("'")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (compareValues(rowValue, value, "=", type)) {
                    return true;
                }
            }
            return false;
        } else {
            return compareValues(rowValue, valueStr, operator, type);
        }
    }

    /**
     * Compares row value with condition value based on data type and operator.
     */
    private static boolean compareValues(Object rowValue, String valueStr, String operator, String type) {
        if (type.equals("int") || type.equals("float") || type.equals("double")) {
            try {
                double rowNum = Double.parseDouble(rowValue.toString());
                double valNum = Double.parseDouble(valueStr);
                return switch (operator.toUpperCase()) {
                    case "=" -> rowNum == valNum;
                    case ">" -> rowNum > valNum;
                    case "<" -> rowNum < valNum;
                    case ">=" -> rowNum >= valNum;
                    case "<=" -> rowNum <= valNum;
                    case "!=" -> rowNum != valNum;
                    case "<>" -> rowNum != valNum;
                    default -> false;
                };
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number format in WHERE clause: '" + valueStr + "'");
            }
        } else if (operator.equalsIgnoreCase("LIKE")) {
            String regex = valueStr.replace("%", ".*").replace("_", ".");
            return rowValue.toString().matches(regex);
        } else {
            String rowStr = rowValue.toString();
            return switch (operator.toUpperCase()) {
                case "=" -> rowStr.equals(valueStr);
                case "!=" -> !rowStr.equals(valueStr);
                case "<>" -> !rowStr.equals(valueStr);
                case ">" -> rowStr.compareTo(valueStr) > 0;
                case "<" -> rowStr.compareTo(valueStr) < 0;
                case ">=" -> rowStr.compareTo(valueStr) >= 0;
                case "<=" -> rowStr.compareTo(valueStr) <= 0;
                default -> false;
            };
        }
    }
    /**
     * Applies an UPDATE SQL command to a list of rows in real-time mode.
     * @param sql The SQL UPDATE command
     * @param rows The list of rows to update
     * @param user The authenticated user
     * @throws Exception If the syntax is invalid or update fails
     */
    public static void applyUpdate(String sql, List<Map<String, Object>> rows, User user) throws Exception {
        Pattern pattern = Pattern.compile(
                "(?i)UPDATE\\s+(\\w+)\\s+SET\\s+(.+?)(?:\\s+WHERE\\s+(.+))?$",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(sql.replace(";", "").trim());

        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid UPDATE syntax for real-time mode. Expected: UPDATE <table> SET <column>=<value>[,...] [WHERE <condition>]");
        }

        String table = matcher.group(1).trim();
        String setClause = matcher.group(2).trim();
        String whereClause = matcher.group(3);

        Map<String, String> updates = new HashMap<>();
        Pattern setPattern = Pattern.compile("(\\w+)\\s*=\\s*('[^']*'|\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher setMatcher = setPattern.matcher(setClause);
        while (setMatcher.find()) {
            String col = setMatcher.group(1).trim();
            String val = setMatcher.group(2).trim().replaceAll("^'|'$", "");
            updates.put(col, val);
        }
        if (updates.isEmpty()) {
            throw new IllegalArgumentException("Invalid SET clause: '" + setClause + "'. Expected: <column>=<value>[,...]");
        }

        String db = user.getCurrentDatabase();
        if (db == null) {
            throw new IllegalArgumentException("No database selected.");
        }

        String tablePath = UserManager.getRootDirectory(user.getUsername()) + "/" + db + "/" + table + ".nson";
        File file = new File(tablePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("Table '" + table + "' not found.");
        }

        String fileContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        NsonObject nson = NsonObject.parse(fileContent);
        NsonObject types = nson.getObject("_types");
        if (types == null) {
            throw new IllegalArgumentException("Invalid table structure for '" + table + "'. Missing '_types'.");
        }

        for (Map<String, Object> row : rows) {
            // Evaluate WHERE clause (reusing logic from DeleteHandler)
            if (whereClause == null || evaluateWhereSimpleForMap(row, whereClause, types)) {
                for (Map.Entry<String, String> update : updates.entrySet()) {
                    String col = update.getKey();
                    if (!types.containsKey(col)) {
                        throw new IllegalArgumentException("Column '" + col + "' does not exist in table '" + table + "'.");
                    }
                    String val = update.getValue();
                    Object newValue = types.getString(col).equals("int") && isNumeric(val) ? Integer.parseInt(val) : val;
                    row.put(col, newValue);
                }
            }
        }
    }

    /**
     * Evaluates a WHERE clause for a row stored as a Map (for real-time mode).
     * @param row The row as a Map
     * @param whereClause The WHERE clause
     * @param types The column types
     * @return True if the row matches the condition
     * @throws Exception If the condition is invalid
     */
    private static boolean evaluateWhereSimpleForMap(Map<String, Object> row, String whereClause, NsonObject types) throws Exception {
        if (whereClause == null || whereClause.trim().isEmpty()) {
            return true;
        }

        if (whereClause.toUpperCase().contains(" AND ")) {
            String[] conditions = whereClause.split("(?i)\\s+AND\\s+");
            for (String condition : conditions) {
                if (!evaluateWhereSimpleForMap(row, condition, types)) {
                    return false;
                }
            }
            return true;
        }

        if (whereClause.toUpperCase().contains(" OR ")) {
            String[] conditions = whereClause.split("(?i)\\s+OR\\s+");
            for (String condition : conditions) {
                if (evaluateWhereSimpleForMap(row, condition, types)) {
                    return true;
                }
            }
            return false;
        }

        String trimmedClause = whereClause.trim();
        String operator = null;
        int operatorIndex = -1;

        String[] twoCharOps = {">=", "<=", "!=", "<>"};
        for (String op : twoCharOps) {
            if (trimmedClause.contains(op)) {
                operator = op;
                operatorIndex = trimmedClause.indexOf(op);
                break;
            }
        }

        if (operator == null) {
            String[] oneCharOps = {"=", ">", "<"};
            for (String op : oneCharOps) {
                if (trimmedClause.contains(op)) {
                    operator = op;
                    operatorIndex = trimmedClause.indexOf(op);
                    break;
                }
            }
        }

        if (operator == null) {
            if (trimmedClause.toUpperCase().contains(" LIKE ")) {
                operator = "LIKE";
                operatorIndex = trimmedClause.toUpperCase().indexOf(" LIKE ");
            } else if (trimmedClause.toUpperCase().contains(" IN ")) {
                operator = "IN";
                operatorIndex = trimmedClause.toUpperCase().indexOf(" IN ");
            }
        }

        if (operator == null || operatorIndex == -1) {
            throw new IllegalArgumentException("Unsupported WHERE clause: '" + whereClause + "'.");
        }

        String column = trimmedClause.substring(0, operatorIndex).trim();
        String valueStr;

        if (operator.equals("IN")) {
            int openParen = trimmedClause.indexOf('(', operatorIndex);
            int closeParen = trimmedClause.lastIndexOf(')');
            if (openParen == -1 || closeParen == -1) {
                throw new IllegalArgumentException("Invalid IN clause syntax.");
            }
            valueStr = trimmedClause.substring(openParen + 1, closeParen).trim();
        } else {
            valueStr = trimmedClause.substring(operatorIndex + operator.length()).trim();
            if (valueStr.startsWith("'") && valueStr.endsWith("'")) {
                valueStr = valueStr.substring(1, valueStr.length() - 1);
            }
        }

        if (!types.containsKey(column)) {
            throw new IllegalArgumentException("Column '" + column + "' does not exist.");
        }

        Object rowValue = row.get(column);
        if (rowValue == null) return false;

        String type = types.getString(column);

        if (operator.equalsIgnoreCase("IN")) {
            String[] values = valueStr.split("\\s*,\\s*");
            for (String value : values) {
                value = value.trim().replaceAll("^'|'$", "");
                if (compareValues(rowValue, value, "=", type)) {
                    return true;
                }
            }
            return false;
        } else {
            return compareValues(rowValue, valueStr, operator, type);
        }
    }

    /**
     * Extracts the table name from an UPDATE SQL command.
     * @param sql The SQL UPDATE command
     * @return The table name
     * @throws Exception If the syntax is invalid
     */
    public static String getTableName(String sql) throws Exception {
        Pattern pattern = Pattern.compile("UPDATE (\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(sql.trim());
        if (!matcher.find()) {
            throw new Exception("Invalid UPDATE syntax. Cannot extract table name.");
        }
        return matcher.group(1).trim();
    }
}