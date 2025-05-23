package njsql.core;

import njsql.models.User;
import njsql.nson.NsonArray;
import njsql.nson.NsonObject;
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

public class UpdateHandler {

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
//        System.out.println("DEBUG: File content of " + table + ".nson: " + fileContent);
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
        NsonArray indexCols = meta.getArray("index");
        if (indexCols == null) {
            indexCols = new NsonArray();
        }

        IndexManager indexManager = new IndexManager();
        indexManager.loadIndexes(file.getPath(), data, indexCols);

        int updatedCount = 0;

        for (int i = 0; i < data.size(); i++) {
            NsonObject row = data.getObject(i);
            if (whereClause == null || evaluateWhereSimple(row, whereClause, types)) {
                for (Map.Entry<String, String> update : updates.entrySet()) {
                    String col = update.getKey();
                    if (!types.containsKey(col)) {
                        throw new IllegalArgumentException("Column '" + col + "' does not exist in table '" + table + "'.");
                    }
                    String val = update.getValue();
                    Object oldValue = row.get(col);
                    Object newValue = types.getString(col).equals("int") && isNumeric(val) ? Integer.parseInt(val) : val;
                    row.put(col, newValue);
                    if (indexCols.contains(col)) {
                        if (oldValue != null) {
                            indexManager.removeIndex(col, oldValue, i);
                        }
                        if (newValue != null) {
                            indexManager.updateIndex(col, newValue, i);
                        }
                    }
                }
                updatedCount++;
            }
        }

        meta.put("last_modified", Instant.now().toString());

        ObjectMapper mapper = new ObjectMapper();
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
//            System.out.println("DEBUG: Error details: " + e.getClass().getName() + ": " + e.getMessage());
            if (e.getCause() != null) {
//                System.out.println("DEBUG: Caused by: " + e.getCause().getMessage());
            }
            throw new Exception("Failed to write to table '" + table + "': " + e.getMessage());
        } finally {
            // Đóng các resource theo thứ tự ngược lại
            try {
                if (lock != null) lock.release();
                if (fw != null) fw.close();
                if (channel != null) channel.close();
                if (fos != null) fos.close();
            } catch (Exception e) {
//                System.out.println("DEBUG: Error while closing resources: " + e.getMessage());
            }
        }
    }

    private static boolean isNumeric(String val) {
        try {
            Integer.parseInt(val);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Phương thức xử lý WHERE clause
    private static boolean evaluateWhereSimple(NsonObject row, String whereClause, NsonObject types) throws Exception {
//        System.out.println("DEBUG: Full WHERE clause: " + whereClause);

        // Xử lý các toán tử AND/OR
        if (whereClause.toUpperCase().contains(" AND ")) {
            String[] conditions = whereClause.split("(?i)\\s+AND\\s+");
            for (String condition : conditions) {
                if (!evaluateWhereSimple(row, condition, types)) {
                    return false;
                }
            }
            return true;
        }

        if (whereClause.toUpperCase().contains(" OR ")) {
            String[] conditions = whereClause.split("(?i)\\s+OR\\s+");
            for (String condition : conditions) {
                if (evaluateWhereSimple(row, condition, types)) {
                    return true;
                }
            }
            return false;
        }

        // Phân tích điều kiện đơn giản
        String trimmedClause = whereClause.trim();

        // Tìm toán tử so sánh trong WHERE clause
        String operator = null;
        int operatorIndex = -1;

        // Kiểm tra các toán tử 2 ký tự trước
        String[] twoCharOps = {">=", "<=", "!=", "<>"};
        for (String op : twoCharOps) {
            if (trimmedClause.contains(op)) {
                operator = op;
                operatorIndex = trimmedClause.indexOf(op);
                break;
            }
        }

        // Nếu không tìm thấy, kiểm tra các toán tử 1 ký tự
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

        // Kiểm tra LIKE và IN
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

        // Lấy tên cột và giá trị
        String column = trimmedClause.substring(0, operatorIndex).trim();
        String valueStr;

        if (operator.equals("IN")) {
            // Xử lý IN clause
            int openParen = trimmedClause.indexOf('(', operatorIndex);
            int closeParen = trimmedClause.lastIndexOf(')');
            if (openParen == -1 || closeParen == -1) {
                throw new IllegalArgumentException("Invalid IN clause syntax in: '" + whereClause + "'");
            }
            valueStr = trimmedClause.substring(openParen + 1, closeParen).trim();
        } else {
            // Xử lý các trường hợp khác
            valueStr = trimmedClause.substring(operatorIndex + operator.length()).trim();
        }

        // Loại bỏ dấu nháy đơn nếu có
        if (valueStr.startsWith("'") && valueStr.endsWith("'")) {
            valueStr = valueStr.substring(1, valueStr.length() - 1);
        }

        // Kiểm tra column tồn tại
        if (!types.containsKey(column)) {
            throw new IllegalArgumentException("Column '" + column + "' does not exist in table schema.");
        }

        // Lấy giá trị từ hàng hiện tại
        Object rowValue = row.get(column);
        if (rowValue == null) return false;

        String type = types.getString(column);

        // Xử lý theo loại toán tử
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

    // Hàm so sánh giá trị dựa trên kiểu dữ liệu
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
}