package njsql.core;

import njsql.models.User;
import njsql.nson.NsonObject;
import njsql.utils.TableFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.io.File;

public class SQLMode {

    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    public static void start(User user) {
        System.out.println("\u001B[36m---   ---   ---\u001B[0m");
        System.out.println(">> Entered SQL Mode as user:\u001B[36m " + user.getUsername() + "\u001B[0m !!");
        System.out.println(">> Type \u001B[31m/end\u001B[0m to exit SQL Mode.");
        System.out.println(">> Type \u001B[32m/r\u001B[0m to run SQL.");

        Scanner scanner = new Scanner(System.in);
        List<String> sqlBuffer = new ArrayList<>();
        String rootDir = UserManager.getRootDirectory(user.getUsername());

        // Kiểm tra xem user có phải admin không
        NsonObject userConfig = UserManager.getUserConfig(user.getUsername());
        boolean isAdmin = userConfig != null && userConfig.getBoolean("isAdmin");

        System.out.print("SQL> ");

        while (true) {
            String line = scanner.nextLine();

            if (line.startsWith("--")) {
                // Bỏ qua dòng ghi chú
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

            if (line.trim().equalsIgnoreCase("/r")) {
                // Nối các dòng bằng dấu cách thay vì xuống dòng
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

                        else if (lower.startsWith("insert into")) {
                            if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "INSERT")) {
                                throw new Exception("Permission denied for INSERT");
                            }
                            String dbName = user.getCurrentDatabase();
                            if (dbName == null || dbName.isEmpty()) {
                                throw new Exception("No database selected. Please use 'USE <database>'.");
                            }
                            String dbPath = rootDir + "/" + dbName;
                            String table = InsertHandler.handle(sql, user.getUsername(), dbPath); // Cập nhật cách gọi
                            System.out.println(">> \u001B[32mSuccess: Data inserted into table | \u001B[0m" + table + "\u001B[32m |." + RESET);
                        }

                        else if (lower.startsWith("delete from")) {
                            if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "DELETE")) {
                                throw new Exception("Permission denied for DELETE");
                            }
                            String table = DeleteHandler.handle(sql, user);
                            System.out.println(">>\u001B[32m Success: Data deleted from table | \u001B[0m" + table + "\u001B[32m |." + RESET);
                        }

                        else if (lower.startsWith("update")) {
                            if (!isAdmin && !PermissionManager.hasPermission(user.getUsername(), "UPDATE")) {
                                throw new Exception("Permission denied for UPDATE");
                            }
                            String table = UpdateHandler.handle(sql, user);
                            System.out.println(">>\u001B[32m Success: Table | \u001B[0m" + table + "\u001B[32m | updated." + RESET);
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
                                        String columnName = colParts[0].trim();
                                        String dataType = colParts[1].trim();
                                        AlterTableHandler.handle(sql, user);
                                    }
                                } else {
                                    System.out.println(RED + ">> ERROR: ALTER TABLE only supports ADD column for now." + RESET);
                                }
                            }
                        }

                    } catch (Exception e) {
                        System.out.println(RED + ">> ERROR: " + e.getMessage() + RESET);
                    }
                }

                System.out.print("SQL> ");
                continue;
            }

            sqlBuffer.add(line);
        }
    }
}