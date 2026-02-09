package njsql.core;

import njsql.models.User;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class CreateTableHandler {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "int", "datetime", "text", "float", "double", "boolean"
    );

    private static final Pattern VARCHAR_PATTERN = Pattern.compile("varchar\\(\\s*\\d+\\s*\\)", Pattern.CASE_INSENSITIVE);

    public static String handle(String sql, User user) throws Exception {
        String currentDb = user.getCurrentDatabase();
        if (currentDb == null || currentDb.isEmpty()) {
            throw new Exception("No database selected. Please use 'USE <database>' first.");
        }

        String sqlLower = sql.toLowerCase().trim();
        if (!sqlLower.startsWith("create table")) {
            throw new Exception("Invalid query: Must start with 'CREATE TABLE'.");
        }
        if (!sqlLower.contains("(") || !sqlLower.contains(")")) {
            throw new Exception("Invalid syntax: Missing parentheses for column definitions.");
        }

        Pattern pattern = Pattern.compile("CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*)\\)\\s*(;)?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql);
        if (!matcher.find()) {
            throw new Exception("Invalid CREATE TABLE syntax. Expected: CREATE TABLE <name> (<definitions>);");
        }

        String tableName = matcher.group(1).trim();
        String columnsPart = matcher.group(2).trim();

        NsonObject nsonTable = new NsonObject();
        NsonObject meta = new NsonObject();
        NsonObject types = new NsonObject();
        NsonArray data = new NsonArray();
        NsonArray autoincrementCols = new NsonArray();
        NsonArray indexCols = new NsonArray();
        NsonArray primaryKeyCols = new NsonArray();
        NsonArray foreignKeys = new NsonArray();
        Set<String> uniqueIndexCols = new HashSet<>();

        String[] columnLines = splitColumns(columnsPart);
        for (String columnLine : columnLines) {
            columnLine = columnLine.trim();
            if (columnLine.isEmpty()) continue;

            if (columnLine.toUpperCase().startsWith("PRIMARY KEY")) {
                Pattern pkPattern = Pattern.compile("PRIMARY\\s+KEY\\s*\\(\\s*([^)]+)\\s*\\)", Pattern.CASE_INSENSITIVE);
                Matcher pkMatcher = pkPattern.matcher(columnLine);
                if (pkMatcher.find()) {
                    String[] pkCols = pkMatcher.group(1).trim().split("\\s*,\\s*");
                    for (String col : pkCols) {
                        col = col.trim();
                        if (col.isEmpty()) throw new Exception("Empty column in PRIMARY KEY.");
                        if (!types.containsKey(col)) throw new Exception("Column '" + col + "' not defined.");
                        primaryKeyCols.add(col);
                        if (uniqueIndexCols.add(col)) indexCols.add(col);
                    }
                }
                continue;
            }

            if (columnLine.toUpperCase().startsWith("FOREIGN KEY")) {
                Pattern fkPattern = Pattern.compile(
                        "FOREIGN KEY\\s*\\(([^)]+)\\)\\s+REFERENCES\\s+(\\w+)\\s*\\(([^)]+)\\)",
                        Pattern.CASE_INSENSITIVE
                );
                Matcher fkMatcher = fkPattern.matcher(columnLine);
                if (fkMatcher.find()) {
                    String[] fkCols = fkMatcher.group(1).trim().split("\\s*,\\s*");
                    String refTable = fkMatcher.group(2).trim();
                    String[] refCols = fkMatcher.group(3).trim().split("\\s*,\\s*");

                    if (fkCols.length != refCols.length) {
                        throw new Exception("FOREIGN KEY column count mismatch with REFERENCES.");
                    }
                    for (String col : fkCols) {
                        if (!types.containsKey(col.trim())) {
                            throw new Exception("FOREIGN KEY column '" + col.trim() + "' not defined.");
                        }
                    }
                    NsonObject fk = new NsonObject();
                    NsonArray fkColsArray = new NsonArray();
                    for (String col : fkCols) fkColsArray.add(col.trim());
                    NsonArray refColsArray = new NsonArray();
                    for (String col : refCols) refColsArray.add(col.trim());
                    fk.put("columns", fkColsArray);
                    fk.put("references_table", refTable);
                    fk.put("references_columns", refColsArray);
                    foreignKeys.add(fk);
                }
                continue;
            }

            Pattern colPattern = Pattern.compile("(\\w+)\\s+([\\w()]+)(?:\\s+(PRIMARY KEY|INDEX|UNIQUE|AUTO_INCREMENT))*", Pattern.CASE_INSENSITIVE);
            Matcher colMatcher = colPattern.matcher(columnLine);
            if (!colMatcher.find()) {
                throw new Exception("Invalid column: '" + columnLine + "'");
            }

            String colName = colMatcher.group(1).trim();
            String colType = colMatcher.group(2).trim().toLowerCase();
            String modifiers = colMatcher.group(3);

            if (types.containsKey(colName)) throw new Exception("Duplicate column: '" + colName + "'");
            if (!isSupportedType(colType)) throw new Exception("Unsupported type: '" + colType + "'");

            types.put(colName, colType);

            if (modifiers != null) {
                String[] mods = modifiers.toUpperCase().split("\\s+");
                for (String mod : mods) {
                    switch (mod) {
                        case "PRIMARY KEY" -> {
                            primaryKeyCols.add(colName);
                            if (uniqueIndexCols.add(colName)) indexCols.add(colName);
                        }
                        case "INDEX", "UNIQUE" -> {
                            if (uniqueIndexCols.add(colName)) indexCols.add(colName);
                        }
                        case "AUTO_INCREMENT" -> autoincrementCols.add(colName);
                    }
                }
            }
        }

        meta.put("created_at", Instant.now().toString());
        meta.put("last_modified", Instant.now().toString());
        meta.put("autoincrement", autoincrementCols);
        meta.put("index", indexCols);
        meta.put("primary_key", primaryKeyCols);
        meta.put("foreign_keys", foreignKeys);

        nsonTable.put("_meta", meta);
        nsonTable.put("_types", types);
        nsonTable.put("data", data);

        String dbDir = UserManager.getRootDirectory(user.getUsername()) + "/" + currentDb;
        File dbFolder = new File(dbDir);
        if (!dbFolder.exists() && !dbFolder.mkdirs()) {
            throw new Exception("Failed to create database directory: " + dbDir);
        }

        String tablePath = dbDir + "/" + tableName + ".nson";
        File tableFile = new File(tablePath);
        if (tableFile.exists()) {
            throw new Exception("Table '" + tableName + "' already exists.");
        }

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        writeWithLock(tableFile, nsonTable, mapper);

        String tableKey = currentDb + "." + tableName;
        System.out.println("DEBUG: Marking dirty for " + tableKey + " (CREATE)");
        njsql.core.BackgroundFlusher.markDirty(tableKey, nsonTable);

        // --- [FIX] CẬP NHẬT CACHE TYPES VÀO RAM ---
        if (RealtimeTableManager.ramTables != null) {
            RealtimeTableManager.ramTables.put(tableKey, new ArrayList<>());
            // QUAN TRỌNG: Nạp types vào cache để Insert đọc được ngay, không cần đọc đĩa
            RealtimeTableManager.tableSchemas.put(tableKey, types); 
            RealtimeTableManager.notifyListeners(tableKey, "CREATE", Collections.emptyList());
        }

        return tableName;
    }

    private static boolean isSupportedType(String type) {
        return SUPPORTED_TYPES.contains(type) || VARCHAR_PATTERN.matcher(type).matches();
    }

    private static String[] splitColumns(String columnsPart) {
        List<String> columns = new ArrayList<>();
        int level = 0;
        StringBuilder sb = new StringBuilder();
        for (char c : columnsPart.toCharArray()) {
            if (c == '(') level++;
            else if (c == ')') level--;
            if (c == ',' && level == 0) {
                columns.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) columns.add(sb.toString().trim());
        return columns.toArray(new String[0]);
    }

    private static void writeWithLock(File file, NsonObject nson, ObjectMapper mapper) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            FileChannel channel = fos.getChannel();
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Cannot obtain lock on file " + file.getName());
            try {
                String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(nson);
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            } finally {
                lock.release();
            }
        }
    }

    public static String getTableName(String sql) throws Exception {
        Matcher m = Pattern.compile("CREATE\\s+TABLE\\s+(\\w+)", Pattern.CASE_INSENSITIVE).matcher(sql);
        if (!m.find()) throw new Exception("Cannot extract table name.");
        return m.group(1).trim();
    }

    public static NsonObject handleForAPI(String sql, User user) {
        NsonObject res = new NsonObject();
        try {
            String name = handle(sql, user);
            res.put("status", "success");
            res.put("message", "Table '" + name + "' created.");
            return res;
        } catch (Exception e) {
            return res.put("error", e.getMessage());
        }
    }
}