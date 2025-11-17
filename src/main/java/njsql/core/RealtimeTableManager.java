package njsql.core;

import njsql.models.User;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import njsql.utils.TableFormatter; // Dù không dùng .format() nhưng vẫn cần import
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class RealtimeTableManager {
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";
    private static final ObjectMapper mapper = new ObjectMapper();

    public static final Map<String, List<Map<String, Object>>> ramTables = new ConcurrentHashMap<>();
    private static final Set<String> dirtyTables = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static boolean isServerRunning = false;

    public static void start(User user, Scanner scanner, String command) {
        if (!isServerRunning) {
            System.out.println(RED + ">> ERROR: Server is not running. Please start the server with /s first." + RESET);
            return;
        }

        String rootDir = UserManager.getRootDirectory(user.getUsername());
        String dbName = user.getCurrentDatabase();
        if (dbName == null || dbName.isEmpty()) {
            System.out.println(RED + ">> ERROR: No database selected. Please use 'USE <database>'." + RESET);
            return;
        }

        String[] tokens = command.trim().split("\\s+");
        if (tokens.length == 1 && tokens[0].equalsIgnoreCase("/rt")) {
            listTables(user, rootDir, dbName, scanner);
        } else if (tokens.length == 2) {
            String target = tokens[1].trim();
            File targetPath = new File(rootDir + "/" + dbName + "/" + target + ".nson");
            if (targetPath.exists() && targetPath.isFile()) {
                enterRealtimeMode(user, dbName, target);
            } else {
                File dbPath = new File(rootDir + "/" + target);
                if (dbPath.exists() && dbPath.isDirectory()) {
                    user.setCurrentDatabase(target);
                    dbName = target;
                    listTables(user, rootDir, dbName, scanner);
                } else {
                    System.out.println(RED + ">> ERROR: Table or database '" + target + "' not found." + RESET);
                }
            }
        } else {
            System.out.println(RED + ">> ERROR: Invalid /rt command. Use /rt, /rt <table_name>, or /rt <db_name>." + RESET);
        }
    }

    private static void listTables(User user, String rootDir, String dbName, Scanner scanner) {
        File dbFolder = new File(rootDir + "/" + dbName);
        if (!dbFolder.exists() || !dbFolder.isDirectory()) {
            System.out.println(RED + ">> ERROR: Database '" + dbName + "' not found." + RESET);
            return;
        }

        File[] tableFiles = dbFolder.listFiles((dir, name) -> name.endsWith(".nson"));
        if (tableFiles == null || tableFiles.length == 0) {
            System.out.println(RED + ">> ERROR: No tables found in database '" + dbName + "'." + RESET);
            return;
        }

        System.out.println(GREEN + ">> Tables in database '" + dbName + "':" + RESET);
        List<String> tableNames = new ArrayList<>();
        for (File file : tableFiles) {
            String tableName = file.getName().replace(".nson", "");
            tableNames.add(tableName);
            System.out.println("- " + tableName);
        }

        System.out.println(GREEN + ">> Enter table name to monitor, 'all' for all tables, or 'exit' to quit." + RESET);
        String input = scanner.nextLine().trim().toLowerCase();

        if (input.equals("exit")) {
            return;
        } else if (input.equals("all")) {
            for (String tableName : tableNames) {
                loadTableToRam(user, dbName, tableName);
            }
            System.out.println(GREEN + ">> Monitoring all tables in real-time." + RESET);
            realtimeLoop(user, scanner, dbName, tableNames);
        } else {
            if (tableNames.contains(input)) {
                loadTableToRam(user, dbName, input);
                System.out.println(GREEN + ">> Monitoring table '" + input + "' in real-time." + RESET);
                realtimeLoop(user, scanner, dbName, Collections.singletonList(input));
            } else {
                System.out.println(RED + ">> ERROR: Table '" + input + "' not found." + RESET);
            }
        }
    }

    private static void enterRealtimeMode(User user, String dbName, String tableName) {
        loadTableToRam(user, dbName, tableName);
        System.out.println(GREEN + ">> Monitoring table '" + tableName + "' in real-time." + RESET);
        realtimeLoop(user, new Scanner(System.in), dbName, Collections.singletonList(tableName));
    }

    private static void loadTableToRam(User user, String dbName, String tableName) {
        String rootDir = UserManager.getRootDirectory(user.getUsername());
        String tableKey = dbName + "." + tableName;
        File tableFile = new File(rootDir + "/" + dbName + "/" + tableName + ".nson");

        if (!tableFile.exists()) {
            System.out.println(RED + ">> ERROR: Table '" + tableName + "' not found." + RESET);
            return;
        }

        try {
            String fileContent = new String(Files.readAllBytes(tableFile.toPath()), StandardCharsets.UTF_8);
            NsonObject tableData = NsonObject.parse(fileContent);
            NsonObject meta = tableData.getObject("_meta");
            NsonObject types = tableData.getObject("_types");
            NsonArray data = tableData.getArray("data");

            if (meta == null || types == null || data == null) {
                System.out.println(RED + ">> ERROR: Invalid table structure: Missing '_meta', '_types', or 'data'." + RESET);
                return;
            }

            List<Map<String, Object>> ramData = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                NsonObject row = (NsonObject) data.getObject(i); // Thêm ép kiểu (NsonObject) cho an toàn
                Map<String, Object> ramRow = new HashMap<>();
                for (String key : row.keySet()) {
                    ramRow.put(key, row.get(key));
                }
                ramData.add(ramRow);
            }

            ramTables.put(tableKey, ramData);
            System.out.println(GREEN + ">> Table '" + tableName + "' loaded into RAM." + RESET);
        } catch (Exception e) {
            System.out.println(RED + ">> ERROR: Failed to load table '" + tableName + "': " + e.getMessage() + RESET);
        }
    }

    private static void realtimeLoop(User user, Scanner scanner, String dbName, List<String> tables) {
        System.out.println(GREEN + ">> Realtime mode commands: SELECT/INSERT/UPDATE/DELETE queries, /flush, /exit" + RESET);
        while (true) {
            System.out.print(GREEN + ">> " + RESET);
            String sql = scanner.nextLine().trim();

            if (sql.equalsIgnoreCase("/exit")) {
                flushOnExit(user);
                System.out.println(GREEN + ">> Exiting real-time mode." + RESET);
                break;
            } else if (sql.equalsIgnoreCase("/flush")) {
                for (String tableName : tables) {
                    flushTableToDisk(user, dbName, tableName, "manual");
                }
            } else {
                String upperSql = sql.toUpperCase();
                if (upperSql.startsWith("SELECT")) {
                    handleSelect(sql, user, dbName, tables);
                } else if (upperSql.startsWith("INSERT")) {
                    handleInsert(sql, user, dbName, tables.get(0));
                } else if (upperSql.startsWith("UPDATE")) {
                    // FIX 1: Bọc trong try...catch
                    try {
                        handleUpdate(sql, user, dbName, tables.get(0));
                    } catch (Exception e) {
                        System.out.println(RED + ">> ERROR: UPDATE failed: " + e.getMessage() + RESET);
                    }
                } else if (upperSql.startsWith("DELETE")) {
                    handleDelete(sql, user, dbName, tables.get(0));
                } else {
                    System.out.println(RED + ">> ERROR: Unsupported command in real-time mode." + RESET);
                }
            }
        }
    }

    private static void handleSelect(String sql, User user, String dbName, List<String> tables) {
        try {
            NsonObject result = SelectHandler.handleForAPI(sql, user);
            if (result.containsKey("error")) {
                System.out.println(RED + ">> ERROR: " + result.getString("error") + RESET);
            } else {
                NsonArray data = result.getArray("data");
                // Tạm thời dùng toString() như lần trước
                System.out.println(data.toString());
            }
        } catch (Exception e) {
            System.out.println(RED + ">> ERROR: SELECT failed: " + e.getMessage() + RESET);
        }
    }

    public static void handleInsert(String sql, User user, String dbName, String tableName) {
        if (!ramTables.containsKey(dbName + "." + tableName)) {
            loadTableToRam(user, dbName, tableName);
        }
        List<Map<String, Object>> rows = ramTables.get(dbName + "." + tableName);
        try {
            Map<String, Object> newRow = parseInsertRow(sql, user, dbName, tableName);
            rows.add(newRow);
            dirtyTables.add(dbName + "." + tableName);
            String flushMode = getTableFlushMode(dbName, tableName);
            if (flushMode.equalsIgnoreCase("immediate")) {
                flushTableToDisk(user, dbName, tableName, "immediate");
            }
            NsonObject nsonRow = new NsonObject();
            nsonRow.putAll(newRow);
            List<NsonObject> nsonList = Collections.singletonList(nsonRow);
            notifyListeners(dbName + "." + tableName, "INSERT", nsonList);
        } catch (Exception e) {
            System.out.println(RED + ">> ERROR: INSERT failed: " + e.getMessage() + RESET);
        }
    }

    public static void handleUpdate(String sql, User user, String dbName, String tableName) throws Exception {
        String result = UpdateHandler.handle(sql, user);
        List<NsonObject> updatedRows = UpdateHandler.getUpdatedRows();

        String tableKey = dbName + "." + tableName;
        updateRamTable(tableKey, updatedRows, dbName);
        notifyListeners(tableKey, "UPDATE", updatedRows);

        NsonArray response = new NsonArray();
        response.add(new NsonObject().put("message", result));
        // Tạm thời dùng toString() như lần trước
        System.out.println(response.toString());
    }

    private static void handleDelete(String sql, User user, String dbName, String tableName) {
        // TODO: Tạo DeleteHandler nếu cần
        System.out.println(RED + ">> DELETE not implemented in real-time mode." + RESET);
    }

    // FIX: Thêm hàm updateRamTable
    public static void updateRamTable(String tableKey, List<NsonObject> rows, String dbName) {
        List<Map<String, Object>> ram = ramTables.get(tableKey);
        if (ram == null) return;
        ram.clear();
        for (NsonObject row : rows) {
            ram.add(row.toMap());
        }
        dirtyTables.add(tableKey);
    }

    private static void flushTableToDisk(User user, String dbName, String tableName, String flushMode) {
        String tableKey = dbName + "." + tableName;
        List<Map<String, Object>> rows = ramTables.get(tableKey);
        if (rows == null) return;

        String rootDir = UserManager.getRootDirectory(user.getUsername());
        File tableFile = new File(rootDir + "/" + dbName + "/" + tableName + ".nson");

        try {
            NsonObject tableData = new NsonObject();
            NsonObject meta = new NsonObject();
            NsonObject types = new NsonObject();
            NsonArray data = new NsonArray();

            String fileContent = new String(Files.readAllBytes(tableFile.toPath()), StandardCharsets.UTF_8);
            NsonObject original = NsonObject.parse(fileContent);
            meta = original.getObject("_meta");
            types = original.getObject("_types");

            for (Map<String, Object> row : rows) {
                NsonObject nsonRow = new NsonObject();
                nsonRow.putAll(row);
                data.add(nsonRow);
            }

            tableData.put("_meta", meta);
            tableData.put("_types", types);
            tableData.put("data", data);
            meta.put("last_modified", Instant.now().toString());

            try (FileOutputStream fos = new FileOutputStream(tableFile);
                 FileChannel channel = fos.getChannel();
                 FileLock lock = channel.lock();
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(tableData.toString(2));
                writer.flush();
            }
            dirtyTables.remove(tableKey);
            System.out.println(GREEN + ">> Table '" + tableName + "' flushed (mode: " + flushMode + ")." + RESET);
        } catch (Exception e) {
            System.out.println(RED + ">> ERROR: Flush failed: " + e.getMessage() + RESET);
        }
    }

    private static void startLazyFlushScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            for (String tableKey : dirtyTables) {
                String[] parts = tableKey.split("\\.");
                String dbName = parts[0], tableName = parts[1];
                if (getTableFlushMode(dbName, tableName).equalsIgnoreCase("lazy")) {
                    flushTableToDisk(null, dbName, tableName, "lazy");
                }
            }
        }, 0, 60, TimeUnit.SECONDS);
    }

    public static void flushOnExit(User user) {
        for (String tableKey : dirtyTables) {
            String[] parts = tableKey.split("\\.");
            flushTableToDisk(user, parts[0], parts[1], "on-exit");
        }
    }

    public static void setServerRunning(boolean running) {
        isServerRunning = running;
        if (running) startLazyFlushScheduler();
    }

    private static String getTableFlushMode(String dbName, String tableName) {
        return "lazy";
    }

    public static class TableChange {
        public final String action;
        public final List<NsonObject> rows;
        public final long timestamp;
        public TableChange(String action, List<NsonObject> rows) {
            this.action = action;
            this.rows = rows;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static final Map<String, List<java.util.function.Consumer<TableChange>>> listeners = new ConcurrentHashMap<>();

    public static void addListener(String tableKey, java.util.function.Consumer<TableChange> listener) {
        listeners.computeIfAbsent(tableKey, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public static void removeListener(String tableKey, java.util.function.Consumer<TableChange> listener) {
        List<java.util.function.Consumer<TableChange>> list = listeners.get(tableKey);
        if (list != null) list.remove(listener);
    }

    public static void notifyListeners(String tableKey, String action, List<NsonObject> rows) {
        List<java.util.function.Consumer<TableChange>> list = listeners.get(tableKey);
        if (list != null) {
            TableChange change = new TableChange(action, rows);
            list.forEach(l -> {
                try { l.accept(change); } catch (Exception e) { e.printStackTrace(); }
            });
        }
    }

    private static Map<String, Object> parseInsertRow(String sql, User user, String dbName, String tableName) throws Exception {
        Pattern p = Pattern.compile("INSERT INTO \\w+\\s*\\(([^)]+)\\)\\s*VALUES\\s*\\((.+)\\)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(sql);
        if (!m.find()) throw new Exception("Invalid INSERT syntax");

        String[] cols = m.group(1).split(",\\s*");
        String[] vals = m.group(2).split(",\\s*");

        Map<String, Object> row = new HashMap<>();
        String rootDir = UserManager.getRootDirectory(user.getUsername());
        String filePath = rootDir + "/" + dbName + "/" + tableName + ".nson";
        NsonObject tableData = NsonObject.parse(new String(Files.readAllBytes(new File(filePath).toPath())));
        NsonObject types = tableData.getObject("_types");

        for (int i = 0; i < cols.length; i++) {
            String col = cols[i].trim();
            String val = vals[i].trim().replaceAll("^'|'$", "");
            String type = types.getString(col);
            Object parsed = type.equals("int") ? Integer.parseInt(val) : val;
            row.put(col, parsed);
        }
        return row;
    }
}