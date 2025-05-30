package njsql.core;

import njsql.models.User;
import njsql.indexing.BTreeIndexManager;
import njsql.nson.NsonObject;
import njsql.utils.TableFormatter;
import njsql.core.RealtimeTableManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.io.File;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;

public class SQLMode {

    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    /**
     * Starts the SQL command-line interface for the given user.
     * @param user The authenticated user
     */
    public static void start(User user) {
        System.out.println("\u001B[36m---   ---   ---\u001B[0m");
        System.out.println(">> Entered SQL Mode as user:\u001B[36m " + user.getUsername() + "\u001B[0m !!");
        System.out.println(">> Type \u001B[31m/end\u001B[0m to exit SQL Mode.");
        System.out.println(">> Type \u001B[32m/r\u001B[0m to run SQL.");

        Scanner scanner = new Scanner(System.in);
        List<String> sqlBuffer = new ArrayList<>();
        String rootDir = UserManager.getRootDirectory(user.getUsername());

        // Check if user is admin
        NsonObject userConfig = UserManager.getUserConfig(user.getUsername());
        boolean isAdmin = userConfig != null && userConfig.getBoolean("isAdmin");

        System.out.print("SQL> ");

        while (true) {
            String line = scanner.nextLine();

            if (line.startsWith("--")) {
                // Skip comment lines
                continue;
            }

            if (line.trim().equalsIgnoreCase("/end")) {
                System.out.println("\u001B[33m<!> \u001B[0mExiting SQL Mode...");
                break;
            }

            if (line.trim().toLowerCase().startsWith("/s * ")) {
                if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "SELECT")) {
                    System.out.println(RED + ">> ERROR: Permission denied for SELECT" + RESET);
                } else {
                    String tableName = line.trim().substring(5).trim();
                    String sql = "SELECT * FROM " + tableName;
                    try {
                        String result = SelectHandler.handle(sql, user);
                        System.out.println(result);
                    } catch (Exception e) {
                        System.out.println(RED + ">> ERROR: " + e.getMessage() + RESET);
                    }
                }
                System.out.print("SQL> ");
                continue;
            }

            if (line.trim().equalsIgnoreCase("/c")) {
                sqlBuffer.clear();
                System.out.println(GREEN + ">> Cleared SQL buffer." + RESET);
                System.out.print("SQL> ");
                continue;
            }
            if (line.trim().equalsIgnoreCase("/s")) {
                RealtimeTableManager.setServerRunning(true);
                System.out.println(GREEN + ">> Server started." + RESET);
                System.out.print("SQL> ");
                continue;
            }
            if (line.trim().toLowerCase().startsWith("/rt")) {
                if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "REALTIME")) {
                    System.out.println(RED + ">> ERROR: Permission denied for REALTIME" + RESET);
                    System.out.print("SQL> ");
                    continue;
                }
                RealtimeTableManager.start(user, scanner, line.trim());
                System.out.print("SQL> ");
                continue;
            }
            if (line.trim().equalsIgnoreCase("/r")) {
                String fullSQL = String.join(" ", sqlBuffer).trim();
                sqlBuffer.clear();

                String[] statements = fullSQL.split(";");

                for (String sql : statements) {
                    sql = sql.trim();
                    if (sql.isEmpty()) continue;

                    try {
                        String lower = sql.toLowerCase();

                        if (lower.startsWith("create database")) {
                            if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "CREATE_DB")) {
                                throw new Exception("Permission denied for CREATE_DB");
                            }
                            String dbName = sql.split("\\s+")[2].trim().toLowerCase();
                            String dbPath = rootDir + "/" + dbName;
                            File dbFolder = new File(dbPath);
                            if (!dbFolder.exists()) {
                                dbFolder.mkdirs();
                                System.out.println(">>" + GREEN + " Success: Database | \u001B[0m" + dbName + "\u001B[32m | created." + RESET);
                            } else {
                                System.out.println(RED + ">> ERROR: Database '" + dbName + "' already exists." + RESET);
                            }
                        }

                        else if (lower.startsWith("use")) {
                            String dbName = sql.split("\\s+")[1].trim().toLowerCase();
                            String dbPath = rootDir + "/" + dbName;
                            File dbFolder = new File(dbPath);
                            if (!dbFolder.exists()) {
                                System.out.println(RED + ">> ERROR: Database '" + dbName + "' not found." + RESET);
                            } else {
                                user.setCurrentDatabase(dbName);
                                System.out.println(">>\u001B[32m Using database | \u001B[0m" + dbName + " \u001B[32m|." + RESET);
                            }
                        }

                        else if (lower.startsWith("create table")) {
                            if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "CREATE_TABLE")) {
                                throw new Exception("Permission denied for CREATE_TABLE");
                            }
                            CreateTableHandler.handle(sql, user);
                            String tableName = CreateTableHandler.getTableName(sql);
                            System.out.println(">> \u001B[32mSuccess: Table | \u001B[0m" + tableName + "\u001B[32m | created successfully!" + RESET);
                        }

                        else if (lower.startsWith("insert into") && RealtimeTableManager.ramTables.containsKey(user.getCurrentDatabase() + "." + InsertHandler.getTableName(sql))) {                            if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "INSERT")) {
                                throw new Exception("Permission denied for INSERT");
                            }
                            String dbName = user.getCurrentDatabase();
                            if (dbName == null || dbName.isEmpty()) {
                                throw new Exception("No database selected. Please use 'USE <database>'.");
                            }
                            String tableName = InsertHandler.getTableName(sql);
                            RealtimeTableManager.handleInsert(sql, user, dbName, tableName);
                            System.out.println(">> \u001B[32mSuccess: Data inserted into table | \u001B[0m" + tableName + "\u001B[32m | in real-time mode." + RESET);
                        }

                        else if (lower.startsWith("delete from")) {
                            if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "DELETE")) {
                                throw new Exception("Permission denied for DELETE");
                            }
                            String table = DeleteHandler.handle(sql, user);
                            System.out.println(">>\u001B[32m Success: Data deleted from table | \u001B[0m" + table + "\u001B[32m |." + RESET);
                        }

                        else if (lower.startsWith("update") && RealtimeTableManager.ramTables.containsKey(user.getCurrentDatabase() + "." + UpdateHandler.getTableName(sql))) {                            if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "UPDATE")) {
                                throw new Exception("Permission denied for UPDATE");
                            }
                            String dbName = user.getCurrentDatabase();
                            if (dbName == null || dbName.isEmpty()) {
                                throw new Exception("No database selected. Please use 'USE <database>'.");
                            }
                            String tableName = UpdateHandler.getTableName(sql);
                            RealtimeTableManager.handleUpdate(sql, user, dbName, tableName);
                            System.out.println(">> \u001B[32mSuccess: Table | \u001B[0m" + tableName + "\u001B[32m | updated in real-time mode." + RESET);
                        }

                        else if (lower.startsWith("select")) {
                            if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "SELECT")) {
                                throw new Exception("Permission denied for SELECT");
                            }
                            String result = SelectHandler.handle(sql, user);
                            System.out.println(result);
                        }

                        else if (lower.startsWith("describe")) {
                            if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "SELECT")) {
                                throw new Exception("Permission denied for DESCRIBE (requires SELECT)");
                            }
                            String[] tokens = sql.split("\\s+");
                            if (tokens.length < 2) {
                                System.out.println(RED + ">> ERROR: Missing table name for DESCRIBE." + RESET);
                            } else {
                                String tableName = tokens[1].replace(";", "").trim();
                                String db = user.getCurrentDatabase();
                                if (db == null) {
                                    System.out.println(RED + ">> ERROR: No database selected." + RESET);
                                } else {
                                    String tablePath = rootDir + "/" + db + "/" + tableName + ".nson";
                                    File tableFile = new File(tablePath);
                                    if (!tableFile.exists()) {
                                        System.out.println(RED + ">> ERROR: Table '" + tableName + "' not found." + RESET);
                                    } else {
                                        List<String[]> tableStructure = DescribeHandler.handle(tableName, user);
                                        System.out.println(TableFormatter.format(tableStructure));
                                    }
                                }
                            }
                        }

                        else if (lower.startsWith("show tables")) {
                            if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "SELECT")) {
                                throw new Exception("Permission denied for SELECT");
                            }
                            String dbName = user.getCurrentDatabase();
                            if (dbName == null) {
                                System.out.println(RED + ">> ERROR: No database selected." + RESET);
                            } else {
                                File dbFolder = new File(rootDir + "/" + dbName);
                                if (!dbFolder.exists() || !dbFolder.isDirectory()) {
                                    System.out.println(RED + ">> ERROR: Database '" + dbName + "' not found." + RESET);
                                } else {
                                    File[] files = dbFolder.listFiles((dir, name) -> name.endsWith(".nson"));
                                    if (files == null || files.length == 0) {
                                        System.out.println(RED + ">> ERROR: No tables found in database '" + dbName + "'." + RESET);
                                    } else {
                                        System.out.println(GREEN + ">> Tables in database '" + dbName + "':" + RESET);
                                        for (File file : files) {
                                            System.out.println("- " + file.getName().replace(".nson", ""));
                                        }
                                    }
                                }
                            }
                        }

                        else if (lower.startsWith("alter table")) {
                            if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "ALTER_TABLE")) {
                                throw new Exception("Permission denied for ALTER_TABLE");
                            }
                            String[] tokens = sql.split("\\s+");
                            if (tokens.length < 5) {
                                System.out.println(RED + ">> ERROR: Invalid ALTER TABLE syntax." + RESET);
                            } else {
                                String tableName = tokens[2].trim();
                                String alterAction = tokens[3].trim();

                                if (alterAction.equalsIgnoreCase("add")) {
                                    String columnDefinition = sql.substring(sql.toLowerCase().indexOf("add") + 4).trim();
                                    if (columnDefinition.endsWith(";")) {
                                        columnDefinition = columnDefinition.substring(0, columnDefinition.length() - 1);
                                    }
                                    String[] colParts = columnDefinition.split("\\s+", 2);
                                    if (colParts.length != 2) {
                                        System.out.println(RED + ">> ERROR: Invalid column definition." + RESET);
                                    } else {
                                        AlterTableHandler.handle(sql, user);
                                        System.out.println(GREEN + ">> Column added to table '" + tableName + "'." + RESET);
                                    }
                                } else {
                                    System.out.println(RED + ">> ERROR: ALTER TABLE only supports ADD column for now." + RESET);
                                }
                            }
                        }

                        else if (lower.startsWith("create index")) {
                            if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "CREATE_INDEX")) {
                                throw new Exception("Permission denied for CREATE_INDEX");
                            }
                            String[] tokens = sql.split("\\s+");
                            if (tokens.length < 6 || !tokens[3].equalsIgnoreCase("on") || !tokens[5].startsWith("(") || !tokens[5].endsWith(")")) {
                                throw new Exception("Invalid CREATE INDEX syntax. Expected: CREATE INDEX index_name ON table_name (column_name)");
                            }
                            String indexName = tokens[2].trim();
                            String tableName = tokens[4].trim();
                            String columnName = tokens[5].replace("(", "").replace(")", "").replace(";", "").trim();

                            String dbName = user.getCurrentDatabase();
                            if (dbName == null || dbName.isEmpty()) {
                                throw new Exception("No database selected. Please use 'USE <database>'");
                            }

                            String dbPath = rootDir + "/" + dbName;
                            BTreeIndexManager.createBTreeIndex(dbPath, tableName, columnName, indexName);
                            System.out.println(GREEN + ">> Index '" + indexName + "' on column '" + columnName + "' created for table '" + tableName + "'" + RESET);
                        }

                        else if (lower.startsWith("drop index")) {
                            if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "DROP_INDEX")) {
                                throw new Exception("Permission denied for DROP_INDEX");
                            }
                            String[] tokens = sql.trim().split("\\s+");
                            System.out.println("Tokens: " + Arrays.toString(tokens)); // Log để debug
                            if (tokens.length >= 4 && tokens[0].equalsIgnoreCase("DROP") && tokens[1].equalsIgnoreCase("INDEX")) {
                                String indexName = tokens[2];
                                String tableName = tokens.length > 4 && tokens[3].equalsIgnoreCase("ON") ? tokens[4] : null;
                                if (tableName == null) {
                                    throw new Exception("Invalid DROP INDEX syntax. Expected: DROP INDEX index_name ON table_name");
                                }
                                // Xóa index
                                String dbPath = UserManager.getRootDirectory(user.getUsername()) + "/" + user.getCurrentDatabase();
                                File file = new File(dbPath + "/" + tableName + ".nson");
                                ObjectMapper mapper = new ObjectMapper();
                                Map<String, Object> tableJson = mapper.readValue(file, Map.class);
                                Map<String, Object> indexes = (Map<String, Object>) tableJson.get("_indexes");
                                if (indexes != null && indexes.containsKey(indexName)) {
                                    indexes.remove(indexName);
                                    tableJson.put("_indexes", indexes);
                                    mapper.writerWithDefaultPrettyPrinter().writeValue(file, tableJson);
                                    System.out.println("Index '" + indexName + "' dropped from table '" + tableName + "'");
                                    return; // hoặc xóa luôn dòng return
                                } else {
                                    throw new Exception("Index '" + indexName + "' does not exist on table '" + tableName + "'");
                                }
                            }
                            String indexName = tokens[1].trim();
                            String tableName = tokens[3].replace(";", "").trim();

                            String dbName = user.getCurrentDatabase();
                            if (dbName == null || dbName.isEmpty()) {
                                throw new Exception("No database selected. Please use 'USE <database>'");
                            }

                            String dbPath = rootDir + "/" + dbName;
                            BTreeIndexManager.dropBTreeIndex(dbPath, tableName, indexName);
                            System.out.println(GREEN + ">> Index '" + indexName + "' dropped from table '" + tableName + "'" + RESET);
                        }

                        else {
                            System.out.println(RED + ">> ERROR: Unsupported SQL command." + RESET);
                        }

                    } catch (Exception e) {
                        System.out.println(RED + ">> ERROR: " + e.getMessage() + RESET);
                    }
                }

                System.out.print("SQL> ");
                continue;
            }

            sqlBuffer.add(line);
            RealtimeTableManager.flushOnExit(user);
        }
}
}