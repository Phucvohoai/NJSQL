package njsql.core;

import njsql.models.User;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Arrays;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;
import java.io.IOException;
import java.io.FileInputStream;
import java.nio.file.Files;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

// Xử lý câu lệnh CREATE TABLE
public class CreateTableHandler {

    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    // Danh sách kiểu dữ liệu được hỗ trợ
    private static final List<String> SUPPORTED_TYPES = Arrays.asList(
            "int", "varchar\\(\\d+\\)", "datetime", "text", "float", "double", "boolean"
    );

    public static String handle(String sql, User user) throws Exception {
        // Kiểm tra cơ sở dữ liệu hiện tại
        String currentDb = user.getCurrentDatabase();
        if (currentDb == null || currentDb.isEmpty()) {
            throw new Exception("No database selected. Please use 'USE <database>' before creating tables.");
        }

        // Kiểm tra cú pháp cơ bản
        String sqlLower = sql.toLowerCase().trim();
        if (!sqlLower.startsWith("create table")) {
            throw new Exception("Invalid query: Must start with 'CREATE TABLE'.");
        }
        if (!sqlLower.contains("(") || !sqlLower.contains(")")) {
            throw new Exception("Invalid CREATE TABLE syntax: Missing parentheses for column definitions.");
        }

        // Regex để phân tích CREATE TABLE
        Pattern pattern = Pattern.compile("CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*)\\)\\s*(;)?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql);

        if (!matcher.find()) {
            StringBuilder errorMsg = new StringBuilder("Invalid CREATE TABLE syntax. Please check:\n");
            errorMsg.append("- Expected format: CREATE TABLE <table_name> (<column_definitions>);\n");
            if (!sqlLower.contains("table")) {
                errorMsg.append("- Missing 'TABLE' keyword after 'CREATE'.\n");
            }
            if (matcher.group(1) == null) {
                errorMsg.append("- Missing table name after 'CREATE TABLE'.\n");
            }
            if (matcher.group(2) == null) {
                errorMsg.append("- Missing column definitions inside parentheses.\n");
            }
            throw new Exception(errorMsg.toString());
        }

        String tableName = matcher.group(1).trim();
        String columnsPart = matcher.group(2).trim();

        // Khởi tạo cấu trúc bảng
        NsonObject nsonTable = new NsonObject();
        NsonObject meta = new NsonObject();
        NsonObject types = new NsonObject();
        NsonArray data = new NsonArray();
        NsonArray autoincrementCols = new NsonArray();
        NsonArray indexCols = new NsonArray();
        NsonArray primaryKeyCols = new NsonArray();
        NsonArray foreignKeys = new NsonArray();
        HashSet<String> uniqueIndexCols = new HashSet<>();

        // Tách các định nghĩa cột
        String[] columnLines = splitColumns(columnsPart);
        for (String columnLine : columnLines) {
            columnLine = columnLine.trim();
            if (columnLine.isEmpty()) {
                throw new Exception("Invalid column definition: Empty column definition found.");
            }

            // Xử lý chỉ mục (INDEX)
            if (columnLine.toUpperCase().startsWith("INDEX")) {
                Pattern indexPattern = Pattern.compile("INDEX\\s*\\(\\s*(.+?)\\s*\\)", Pattern.CASE_INSENSITIVE);
                Matcher indexMatcher = indexPattern.matcher(columnLine);
                if (indexMatcher.find()) {
                    String indexedColsStr = indexMatcher.group(1).trim();
                    if (indexedColsStr.isEmpty()) {
                        throw new Exception("Invalid INDEX syntax: No columns specified in INDEX clause.");
                    }
                    String[] indexedCols = indexedColsStr.split("\\s*,\\s*");
                    for (String col : indexedCols) {
                        String trimmedCol = col.trim();
                        if (trimmedCol.isEmpty()) {
                            throw new Exception("Invalid INDEX syntax: Empty column name in INDEX clause.");
                        }
                        if (!types.containsKey(trimmedCol)) {
                            throw new Exception("Invalid INDEX syntax: Column '" + trimmedCol + "' not defined in table.");
                        }
                        if (uniqueIndexCols.add(trimmedCol)) {
                            indexCols.add(trimmedCol);
                        }
                    }
                } else {
                    throw new Exception("Invalid INDEX syntax: Expected format 'INDEX (<column_list>)', got '" + columnLine + "'.");
                }
            }
            // Xử lý khóa ngoại (FOREIGN KEY)
            else if (columnLine.toUpperCase().startsWith("FOREIGN KEY")) {
                Pattern fkPattern = Pattern.compile("FOREIGN\\s+KEY\\s*\\((\\w+)\\)\\s+REFERENCES\\s+(\\w+)\\s*\\((\\w+)\\)", Pattern.CASE_INSENSITIVE);
                Matcher fkMatcher = fkPattern.matcher(columnLine);
                if (fkMatcher.find()) {
                    String column = fkMatcher.group(1).trim();
                    String refTable = fkMatcher.group(2).trim();
                    String refColumn = fkMatcher.group(3).trim();

                    if (!types.containsKey(column)) {
                        throw new Exception("Invalid FOREIGN KEY syntax: Column '" + column + "' not defined in table.");
                    }
                    String refTablePath = UserManager.getRootDirectory(user.getUsername()) + "/" + currentDb + "/" + refTable + ".nson";
                    File refTableFile = new File(refTablePath);
                    if (!refTableFile.exists()) {
                        throw new Exception("Invalid FOREIGN KEY syntax: Referenced table '" + refTable + "' does not exist at '" + refTablePath + "'.");
                    }
                    if (!refTableFile.canRead()) {
                        throw new Exception("Invalid FOREIGN KEY syntax: No read permission for referenced table '" + refTable + "' at '" + refTablePath + "'.");
                    }
                    // Đọc tệp tham chiếu bằng JSONObject
                    try {
                        String fileContent = new String(Files.readAllBytes(refTableFile.toPath()), StandardCharsets.UTF_8);
                        NsonObject refTableData = new NsonObject(fileContent); // Sử dụng constructor của NsonObject
                        NsonObject refTypes = refTableData.getObject("_types");
                        if (refTypes == null) {
                            throw new Exception("Invalid FOREIGN KEY syntax: Table '" + refTable + "' has no '_types' metadata at '" + refTablePath + "'.");
                        }
                        if (!refTypes.containsKey(refColumn)) {
                            throw new Exception("Invalid FOREIGN KEY syntax: Referenced column '" + refColumn + "' does not exist in table '" + refTable + "'.");
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        throw new Exception("Failed to read referenced table '" + refTable + "' at '" + refTablePath + "': " + e.getMessage());
                    } catch (org.json.JSONException e) {
                        e.printStackTrace();
                        throw new Exception("Failed to parse JSON of referenced table '" + refTable + "' at '" + refTablePath + "': " + e.getMessage());
                    }

                    NsonObject fk = new NsonObject();
                    fk.put("column", column);
                    fk.put("references_table", refTable);
                    fk.put("references_column", refColumn);
                    foreignKeys.add(fk);
                } else {
                    throw new Exception("Invalid FOREIGN KEY syntax: Expected format 'FOREIGN KEY (<column>) REFERENCES <table>(<column>)', got '" + columnLine + "'.");
                }
            }
            // Xử lý định nghĩa cột
            else {
                String[] tokens = columnLine.split("\\s+");
                if (tokens.length < 2) {
                    throw new Exception("Invalid column definition: Missing column name or data type in '" + columnLine + "'.");
                }

                String colName = tokens[0].trim();
                if (colName.isEmpty()) {
                    throw new Exception("Invalid column definition: Column name cannot be empty in '" + columnLine + "'.");
                }
                StringBuilder colTypeBuilder = new StringBuilder(tokens[1].toLowerCase());
                boolean isAutoIncrement = false;

                String colTypeCheck = colTypeBuilder.toString();
                if (!SUPPORTED_TYPES.stream().anyMatch(type -> colTypeCheck.matches(type))) {
                    throw new Exception("Unsupported data type '" + colTypeCheck + "' for column '" + colName + "'. Supported types: int, varchar(n), datetime, text, float, double, boolean.");
                }

                for (int i = 2; i < tokens.length; i++) {
                    String token = tokens[i].toUpperCase();
                    switch (token) {
                        case "PRIMARY":
                            if (i + 1 < tokens.length && tokens[i + 1].equalsIgnoreCase("KEY")) {
                                primaryKeyCols.add(colName);
                                if (uniqueIndexCols.add(colName)) {
                                    indexCols.add(colName);
                                }
                                i++;
                            } else {
                                throw new Exception("Invalid PRIMARY KEY syntax: Expected 'PRIMARY KEY' in '" + columnLine + "'.");
                            }
                            break;
                        case "AUTOINCREMENT":
                            isAutoIncrement = true;
                            autoincrementCols.add(colName);
                            if (uniqueIndexCols.add(colName)) {
                                indexCols.add(colName);
                            }
                            if (!colTypeCheck.equals("int")) {
                                throw new Exception("Invalid AUTOINCREMENT syntax: AUTOINCREMENT requires 'int' type for column '" + colName + "'.");
                            }
                            break;
                        case "NOT":
                            if (i + 1 < tokens.length && tokens[i + 1].equalsIgnoreCase("NULL")) {
                                i++;
                            } else {
                                throw new Exception("Invalid NOT NULL syntax: Expected 'NOT NULL' in '" + columnLine + "'.");
                            }
                            break;
                        case "DEFAULT":
                            if (i + 1 < tokens.length) {
                                String defaultValue = tokens[i + 1];
                                if (defaultValue.equalsIgnoreCase("CURRENT_TIMESTAMP") && !colTypeCheck.equals("datetime")) {
                                    throw new Exception("Invalid DEFAULT syntax: CURRENT_TIMESTAMP requires 'datetime' type for column '" + colName + "'.");
                                }
                                i++;
                            } else {
                                throw new Exception("Invalid DEFAULT syntax: Missing default value in '" + columnLine + "'.");
                            }
                            break;
                        default:
                            colTypeBuilder.append(" ").append(tokens[i]);
                            break;
                    }
                }

                String colType = colTypeBuilder.toString().trim();
                if (colType.matches("varchar\\(\\d+")) {
                    colType += ")";
                }
                types.put(colName, colType);
            }
        }

        // Thiết lập metadata cho bảng
        String now = Instant.now().toString();
        meta.put("table", tableName);
        meta.put("primary_key", primaryKeyCols);
        meta.put("autoincrement", autoincrementCols);
        meta.put("index", indexCols);
        meta.put("foreign_keys", foreignKeys);
        meta.put("created_at", now);
        meta.put("last_modified", now);

        nsonTable.put("_meta", meta);
        nsonTable.put("_types", types);
        nsonTable.put("data", data);

        // Kiểm tra thư mục cơ sở dữ liệu
        String rootDir = UserManager.getRootDirectory(user.getUsername());
        String dbDir = rootDir + "/" + currentDb;
        File dbFolder = new File(dbDir);
        if (!dbFolder.exists()) {
            throw new Exception("Database '" + currentDb + "' does not exist at path '" + dbDir + "'.");
        }
        if (!dbFolder.canWrite()) {
            throw new Exception("No write permission for database directory '" + dbDir + "'. Please check folder permissions.");
        }

        // Kiểm tra dung lượng ổ đĩa
        File disk = dbFolder.getParentFile();
        if (disk.getUsableSpace() < 1024 * 1024) { // Kiểm tra dung lượng trống < 1MB
            throw new Exception("Insufficient disk space on drive '" + disk.getPath() + "'. Please free up space.");
        }

        // Kiểm tra bảng đã tồn tại chưa
        String tableFilePath = dbDir + "/" + tableName + ".nson";
        File tableFile = new File(tableFilePath);
        if (tableFile.exists()) {
            throw new Exception("Table '" + tableName + "' already exists in database '" + currentDb + "'.");
        }

        // Lưu bảng vào tệp với khóa tệp
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT); // Đảm bảo định dạng JSON
        String json;
        try {
            json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(nsonTable);
        } catch (IOException e) {
            e.printStackTrace();
            throw new Exception("Failed to serialize table '" + tableName + "' to JSON: " + e.getMessage());
        }

        try (FileOutputStream fos = new FileOutputStream(tableFile)) {
            FileChannel channel = fos.getChannel();
            FileLock lock = null;
            try {
                lock = channel.lock();
                try (OutputStreamWriter fw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    fw.write(json);
                }
            } catch (IOException e) {
                e.printStackTrace();
                throw new Exception("Failed to write table '" + tableName + "' to file: " + e.getMessage() + ". Check file permissions, disk space, read-only status, or concurrent access.");
            } finally {
                if (lock != null && lock.isValid()) {
                    try {
                        lock.release();
                    } catch (IOException e) {
                        e.printStackTrace();
                        System.err.println("Warning: Failed to release file lock for table '" + tableName + "': " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new Exception("Failed to open or lock file for table '" + tableName + "' at '" + tableFilePath + "': " + e.getMessage());
        }

        return tableName;
    }

    // Tách các định nghĩa cột, xử lý dấu phẩy trong ngoặc
    private static String[] splitColumns(String columnsPart) {
        List<String> columns = new ArrayList<>();
        int parenthesesLevel = 0;
        StringBuilder currentColumn = new StringBuilder();

        for (int i = 0; i < columnsPart.length(); i++) {
            char c = columnsPart.charAt(i);
            if (c == '(') parenthesesLevel++;
            else if (c == ')') parenthesesLevel--;
            if (c == ',' && parenthesesLevel == 0) {
                columns.add(currentColumn.toString().trim());
                currentColumn.setLength(0);
            } else {
                currentColumn.append(c);
            }
        }
        if (currentColumn.length() > 0) {
            columns.add(currentColumn.toString().trim());
        }
        return columns.toArray(new String[0]);
    }

    // Lấy tên bảng từ câu lệnh SQL
    public static String getTableName(String sql) throws Exception {
        sql = sql.trim().replaceAll("\\s+", " ");
        if (!sql.toLowerCase().startsWith("create table")) {
            throw new Exception("Not a CREATE TABLE statement: Query must start with 'CREATE TABLE'.");
        }
        String[] parts = sql.split("\\s+");
        if (parts.length < 3) {
            throw new Exception("Invalid CREATE TABLE syntax: Missing table name after 'CREATE TABLE'.");
        }
        return parts[2];
    }
}