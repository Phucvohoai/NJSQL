package njsql.core;

import njsql.models.User;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import njsql.utils.TableFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
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

public class RealtimeTableManager {
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";
    private static final ObjectMapper mapper = new ObjectMapper();

    // In-memory storage for tables in real-time mode
    public static final Map<String, List<Map<String, Object>>> ramTables = new ConcurrentHashMap<>();
    // Tracks dirty tables that need to be flushed
    private static final Set<String> dirtyTables = ConcurrentHashMap.newKeySet();
    // Scheduler for lazy flushing
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    // Flag to check if the server is running
    private static boolean isServerRunning = false;

    /**
     * Starts real-time mode for an authenticated user.
     * @param user The authenticated user
     * @param scanner Scanner to receive user input
     * @param command The initial command (e.g., /rt, /rt <table_name>, /rt <db_name>)
     */
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
            // Display available tables and prompt for selection
            listTables(user, rootDir, dbName, scanner);
        } else if (tokens.length == 2) {
            String target = tokens[1].trim();
            File targetPath = new File(rootDir + "/" + dbName + "/" + target + ".nson");
            if (targetPath.exists() && targetPath.isFile()) {
                // Target is a table
                enterRealtimeMode(user, dbName, target);
            } else {
                // Check if the target is a database
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

    /**
     * Lists all tables in the current database and prompts the user to select one or all.
     */
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

        System.out.println(">> Enter the table name to monitor (or 'all' to monitor all tables, '/end' to exit): ");
        while (true) {
            System.out.print("Realtime> ");
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("/end")) {
                System.out.println("\u001B[33m<!> \u001B[0mExiting real-time mode...");
                return;
            }
            if (input.equalsIgnoreCase("all")) {
                for (String tableName : tableNames) {
                    enterRealtimeMode(user, dbName, tableName);
                }
                System.out.println(GREEN + ">> Monitoring all tables in real-time mode." + RESET);
                break;
            }
            if (tableNames.contains(input)) {
                enterRealtimeMode(user, dbName, input);
                break;
            } else {
                System.out.println(RED + ">> ERROR: Table '" + input + "' not found." + RESET);
            }
        }
    }

    /**
     * Enters real-time mode for a specific table.
     */
    private static void enterRealtimeMode(User user, String dbName, String tableName) {
        String tableKey = dbName + "." + tableName;
        if (!ramTables.containsKey(tableKey)) {
            loadTableToRam(user, dbName, tableName);
        }
        System.out.println(GREEN + ">> Entered real-time mode for table '" + tableName + "' in database '" + dbName + "'." + RESET);
        System.out.println(">> Enter /flush to manually flush, /end to exit real-time mode.");

        // Start lazy flush scheduler if not already running
        startLazyFlushScheduler();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("Realtime> ");
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("/end")) {
                System.out.println("\u001B[33m<!> \u001B[0mExiting real-time mode for '" + tableName + "'...");
                break;
            } else if (input.equalsIgnoreCase("/flush")) {
                flushTableToDisk(user, dbName, tableName, "manual");
                System.out.println(GREEN + ">> Table '" + tableName + "' has been flushed to disk." + RESET);
            } else {
                System.out.println(RED + ">> ERROR: Unknown command. Use /flush or /end." + RESET);
            }
        }
    }

    /**
     * Loads table data into RAM for real-time mode.
     */
    private static void loadTableToRam(User user, String dbName, String tableName) {
        String rootDir = UserManager.getRootDirectory(user.getUsername());
        String tablePath = rootDir + "/" + dbName + "/" + tableName + ".nson";
        File tableFile = new File(tablePath);
        try {
            if (tableFile.exists()) {
                String fileContent = new String(Files.readAllBytes(tableFile.toPath()), StandardCharsets.UTF_8);
                NsonObject tableData = new NsonObject(fileContent);
                NsonArray data = tableData.getArray("data");
                if (data == null) {
                    throw new Exception("Invalid table structure: Missing 'data' field.");
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int i = 0; i < data.size(); i++) {
                    NsonObject nsonRow = data.getObject(i);
                    Map<String, Object> row = new HashMap<>();
                    for (String key : nsonRow.keySet()) {
                        row.put(key, nsonRow.get(key));
                    }
                    rows.add(row);
                }
                ramTables.put(dbName + "." + tableName, rows);
                System.out.println(GREEN + ">> Table '" + tableName + "' has been loaded into RAM." + RESET);
            } else {
                ramTables.put(dbName + "." + tableName, new ArrayList<>());
                System.out.println(GREEN + ">> Table '" + tableName + "' has been initialized in RAM." + RESET);
            }
        } catch (Exception e) {
            System.out.println(RED + ">> ERROR: Unable to load table '" + tableName + "': " + e.getMessage() + RESET);
        }
    }

    /**
     * Handles INSERT operations in real-time mode.
     */
    public static void handleInsert(String sql, User user, String dbName, String tableName) {
        if (!ramTables.containsKey(dbName + "." + tableName)) {
            loadTableToRam(user, dbName, tableName);
        }
        List<Map<String, Object>> rows = ramTables.get(dbName + "." + tableName);
        try {
            Map<String, Object> newRow = InsertHandler.parseInsert(sql, user);
            rows.add(newRow);
            dirtyTables.add(dbName + "." + tableName);
            String flushMode = getTableFlushMode(dbName, tableName);
            if (flushMode.equalsIgnoreCase("immediate")) {
                flushTableToDisk(user, dbName, tableName, "immediate");
            }
        }
        catch (Exception e) {
            System.out.println(RED + ">> ERROR: Unable to perform INSERT in real-time mode: " + e.getMessage() + RESET);
        }
    }

    /**
     * Handles UPDATE operations in real-time mode.
     */
    public static void handleUpdate(String sql, User user, String dbName, String tableName) {
        if (!ramTables.containsKey(dbName + "." + tableName)) {
            loadTableToRam(user, dbName, tableName);
        }
        List<Map<String, Object>> rows = ramTables.get(dbName + "." + tableName);
        try {
            UpdateHandler.applyUpdate(sql, rows, user);
            dirtyTables.add(dbName + "." + tableName);
            String flushMode = getTableFlushMode(dbName, tableName);
            if (flushMode.equalsIgnoreCase("immediate")) {
                flushTableToDisk(user, dbName, tableName, "immediate");
            }
        } catch (Exception e) {
            System.out.println(RED + ">> ERROR: Unable to perform UPDATE in real-time mode: " + e.getMessage() + RESET);
        }
    }

    /**
     * Flushes table data from RAM to disk.
     */
    private static void flushTableToDisk(User user, String dbName, String tableName, String flushMode) {
        String tableKey = dbName + "." + tableName;
        if (!ramTables.containsKey(tableKey) || !dirtyTables.contains(tableKey)) {
            return;
        }
        String rootDir = user != null ? UserManager.getRootDirectory(user.getUsername()) : UserManager.getRootDirectory("default");
        String tablePath = rootDir + "/" + dbName + "/" + tableName + ".nson";
        File tableFile = new File(tablePath);
        try {
            NsonObject tableData;
            NsonObject meta = new NsonObject();
            NsonObject types = new NsonObject();
            NsonArray data = new NsonArray();
            if (tableFile.exists()) {
                String fileContent = new String(Files.readAllBytes(tableFile.toPath()), StandardCharsets.UTF_8);
                tableData = new NsonObject(fileContent);
                meta = tableData.getObject("_meta");
                types = tableData.getObject("_types");
                if (meta == null || types == null) {
                    throw new Exception("Invalid table structure: Missing '_meta' or '_types'.");
                }
            } else {
                meta.put("last_modified", Instant.now().toString());
            }

            List<Map<String, Object>> rows = ramTables.get(tableKey);
            for (Map<String, Object> row : rows) {
                NsonObject nsonRow = new NsonObject();
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    nsonRow.put(entry.getKey(), entry.getValue());
                }
                data.add(nsonRow);
            }

            tableData = new NsonObject();
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
            System.out.println(GREEN + ">> Table '" + tableName + "' has been flushed to disk (mode: " + flushMode + ")." + RESET);
        } catch (Exception e) {
            System.out.println(RED + ">> ERROR: Unable to flush table '" + tableName + "': " + e.getMessage() + RESET);
        }
    }

    /**
     * Starts the lazy flush scheduler.
     */
    private static void startLazyFlushScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            for (String tableKey : dirtyTables) {
                String[] parts = tableKey.split("\\.");
                String dbName = parts[0];
                String tableName = parts[1];
                if (getTableFlushMode(dbName, tableName).equalsIgnoreCase("lazy")) {
                    flushTableToDisk(null, dbName, tableName, "lazy");
                }
            }
        }, 0, 60, TimeUnit.SECONDS); // Flush every 60 seconds
    }

    /**
     * Flushes all dirty tables when exiting the system.
     */
    public static void flushOnExit(User user) {
        for (String tableKey : dirtyTables) {
            String[] parts = tableKey.split("\\.");
            String dbName = parts[0];
            String tableName = parts[1];
            flushTableToDisk(user, dbName, tableName, "on-exit");
        }
    }

    /**
     * Sets the server running status.
     */
    public static void setServerRunning(boolean running) {
        isServerRunning = running;
    }

    /**
     * Gets the flush mode for a table (defaults to lazy for simplicity).
     */
    private static String getTableFlushMode(String dbName, String tableName) {
        // For simplicity, assume all tables use lazy mode unless specified
        // In a full implementation, this could be configured per table in a config file
        return "lazy";
    }
}