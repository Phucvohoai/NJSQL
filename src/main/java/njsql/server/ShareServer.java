package njsql.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import njsql.core.UserManager;
import njsql.core.CommitManager;
import njsql.utils.FileUtils;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class ShareServer {
    private static HttpServer server;
    private static Consumer<String> logger;

    public static void start(Consumer<String> logger) throws IOException {
        ShareServer.logger = logger;
        int port = 1201;
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        String ip = InetAddress.getLocalHost().getHostAddress();
        logger.accept("\u001B[36m Sharing server running on \u001B[33mhttp://localhost:" + port + "\u001B[0m or \u001B[33mhttp://" + ip + ":" + port + "\u001B[0m");
        logger.accept(" Enter \u001B[33m/help\u001B[0m for a list of commands.");

        server.createContext("/clone", new CloneHandler());
        server.createContext("/push", new PushHandler());
        server.createContext("/connect", new ConnectHandler());
        server.setExecutor(null);
        server.start();
    }

    public static void stop() {
        if (server != null) {
            server.stop(0);
            logger.accept("\u001B[33m- Sharing server stopped.\u001B[0m");
        }
    }

    static class CloneHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String client = exchange.getRemoteAddress().toString();
            logger.accept("\u001B[33m-\u001B[0m Incoming clone request from \u001B[33m" + client + "\u001B[0m");

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Only POST method supported");
                return;
            }

            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                logger.accept("DEBUG: Clone request body: " + body);
                NsonObject request = NsonObject.parse(body);
                String username = request.getString("username");
                String password = request.getString("password");
                String database = request.getString("database");
                String shareUsername = request.getString("share_username");

                if (shareUsername == null || shareUsername.isEmpty()) {
                    sendError(exchange, 400, "Missing share_username in request");
                    return;
                }

                NsonObject authResult = UserManager.checkLogin(username, password, shareUsername);
                logger.accept("DEBUG: authResult: " + authResult.toString());
                if (!authResult.getBoolean("success")) {
                    sendError(exchange, 401, "Invalid username or password");
                    return;
                }

                NsonObject user = authResult.getObject("user");
                logger.accept("DEBUG: user: " + user.toString());
                boolean isAdmin = user.getBoolean("isAdmin");
                NsonArray children = user.getArray("children");
                logger.accept("DEBUG: isAdmin: " + isAdmin + ", children: " + (children != null ? children.toString() : "null") + ", username: " + username);
                if (!isAdmin && (children == null || !children.contains(username))) {
                    sendError(exchange, 403, "User not authorized to clone this database");
                    return;
                }

                String dbPath = "njsql_data/" + shareUsername + "/" + database;
                File dbDir = new File(dbPath);
                if (!dbDir.exists() || !dbDir.isDirectory()) {
                    sendError(exchange, 404, "Database '" + database + "' does not exist");
                    return;
                }

                NsonObject files = new NsonObject();
                Files.walk(Paths.get(dbPath))
                        .filter(path -> path.toString().endsWith(".nson"))
                        .filter(path -> !path.toString().contains("/.commits/") && !path.toString().contains("\\.commits\\"))
                        .forEach(path -> {
                            try {
                                String relativePath = Paths.get(dbPath).relativize(path).toString();
                                String content = FileUtils.readFileUtf8(path.toString());
                                files.put(relativePath.replace("\\", "/"), content);
                            } catch (IOException e) {
                                throw new RuntimeException("Error reading file: " + path, e);
                            }
                        });

                NsonObject response = new NsonObject()
                        .put("success", true)
                        .put("files", files);
                sendResponse(exchange, 200, response.toString(2));
            } catch (Exception e) {
                logger.accept("\u001B[33m-\u001B[31m Error processing clone request: \u001B[0m" + e.getMessage());
                sendError(exchange, 500, "Server error: " + e.getMessage());
            }
        }
    }

    static class PushHandler implements HttpHandler {
        private static final DateTimeFormatter formatter = DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneId.of("UTC"));

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                logger.accept("DEBUG: Push request body: " + body);
                NsonObject request = NsonObject.parse(body);
                String username = request.getString("username");
                String password = request.getString("password");
                String database = request.getString("database");
                String commitMessage = request.getString("commit_message");
                String shareUsername = request.getString("share_username");
                NsonObject files = request.getObject("files");

                if (shareUsername == null || shareUsername.isEmpty()) {
                    sendError(exchange, 400, "Missing share_username in request");
                    return;
                }

                NsonObject authResult = UserManager.checkLogin(username, password, shareUsername);
                if (!authResult.getBoolean("success")) {
                    sendError(exchange, 401, "Invalid username or password");
                    return;
                }

                NsonObject user = authResult.getObject("user");
                boolean isAdmin = user.getBoolean("isAdmin");
                NsonArray children = user.getArray("children");
                if (!isAdmin && (children == null || !children.contains(username))) {
                    sendError(exchange, 403, "User not authorized to push to this database");
                    return;
                }

                String dbPath = "njsql_data/" + shareUsername + "/" + database;
                File dbDir = new File(dbPath);
                if (!dbDir.exists() || !dbDir.isDirectory()) {
                    sendError(exchange, 404, "Database '" + database + "' does not exist");
                    return;
                }

                // Tạo commit
                String commitId = UUID.randomUUID().toString();
                String commitPath = dbPath + "/.commits/" + commitId + ".nson";
                FileUtils.createDirectory(dbPath + "/.commits");
                long timestamp = System.currentTimeMillis();
                NsonObject commit = new NsonObject()
                        .put("id", commitId)
                        .put("username", username)
                        .put("db_name", database)
                        .put("message", commitMessage)
                        .put("timestamp", formatter.format(Instant.ofEpochMilli(timestamp)))
                        .put("files", new NsonObject());

                // Tạo diff chi tiết
                CommitManager commitManager = new CommitManager(dbPath + "/.commits");
                NsonObject diff = new NsonObject();
                for (String tableName : files.keySet()) {
                    if (tableName.contains(".commits/") || tableName.contains(".commits\\")) {
                        logger.accept("DEBUG: Skipping commit file: " + tableName);
                        continue;
                    }
                    String clientContent = files.getString(tableName);
                    logger.accept("DEBUG: Processing table: " + tableName);
                    NsonObject clientTable = NsonObject.parse(clientContent);
                    String serverTablePath = dbPath + "/" + tableName;
                    NsonObject serverTable = FileUtils.exists(serverTablePath)
                            ? NsonObject.parse(FileUtils.readFileUtf8(serverTablePath))
                            : new NsonObject().put("data", new NsonArray()).put("_meta", new NsonObject()).put("_types", new NsonObject());
                    NsonObject tableDiff = commitManager.compareTables(serverTable, clientTable);
                    logger.accept("DEBUG: Table diff for " + tableName + ": " + tableDiff.toString());
                    if (!tableDiff.isEmpty()) {
                        diff.put(tableName, tableDiff);
                    }
                }

                commit.put("files", diff);
                commitManager.saveCommit(commit);

                // Hiển thị thay đổi
                displayChanges(commit, dbPath);

                // Gửi response
                NsonObject response = new NsonObject()
                        .put("success", true)
                        .put("commit_id", commitId);
                sendResponse(exchange, 200, response.toString(2));
            } catch (Exception e) {
                logger.accept("\u001B[33m- \u001B[31mError processing push request: \u001B[0m" + e.getMessage());
                sendError(exchange, 500, "Server error: " + e.getMessage());
            }
        }

        private void displayChanges(NsonObject commit, String dbPath) {
            String username = commit.getString("username");
            String commitId = commit.getString("id");
            String message = commit.getString("message");
            String timestamp = commit.getString("timestamp");

            StringBuilder output = new StringBuilder();
            output.append("\u001B[32m [Server] \u001B[36mChanges for database '\u001B[33m").append(commit.getString("db_name"))
                    .append("\u001B[36m' by user '\u001B[33m").append(username).append("\u001B[36m':\u001B[0m\n");
            output.append("\u001B[33m-\u001B[0m Commit ID: ").append(commitId).append("\n");
            output.append("\u001B[33m-\u001B[0m Commit message: ").append(message).append("\n");
            output.append("\u001B[33m-\u001B[0m Timestamp: ").append(timestamp).append("\n");

            NsonObject files = commit.getObject("files");
            if (files == null || files.isEmpty()) {
                output.append("No changes found.\n");
                logger.accept(output.toString());
                return;
            }

            for (String tableName : files.keySet()) {
                output.append("\u001B[33m-\u001B[0m Table: ").append(tableName.replace(".nson", "")).append("\n");
                output.append("\u001B[33m-\u001B[0m Changes:\n");

                NsonObject tableDiff = files.getObject(tableName);
                if (tableDiff.getString("type") != null && tableDiff.getString("type").equals("deleted")) {
                    output.append("Table deleted.\n");
                    continue;
                }

                NsonArray changes = tableDiff.getArray("changes");
                if (changes == null) {
                    output.append("No row changes (changes array is null).\n");
                    logger.accept("DEBUG: Changes array is null for table " + tableName);
                    continue;
                }
                if (changes.isEmpty()) {
                    output.append("No row changes (changes array is empty).\n");
                    continue;
                }

                // Đọc schema bảng
                List<String> columns = new ArrayList<>();
                try {
                    NsonObject table = NsonObject.parse(FileUtils.readFileUtf8(dbPath + "/" + tableName));
                    NsonObject types = table.getObject("_types");
                    if (types == null) {
                        output.append("\u001B[33m-\u001B[31m [ERROR] No _types found for \u001B[0m").append(tableName).append("\n");
                        logger.accept("DEBUG: No _types found for table " + tableName);
                        continue;
                    }
                    columns.addAll(types.keySet());
                    Collections.sort(columns);
                } catch (Exception e) {
                    output.append("\u001B[33m-\u001B[31m [ERROR] Failed to read table schema for \u001B[0m").append(tableName)
                            .append(": ").append(e.getMessage()).append("\n");
                    logger.accept("Server> " + output.toString());
                    return;
                }

                // Tạo bảng với cột Status
                final int colWidth = 20;
                List<String> displayColumns = new ArrayList<>();
                displayColumns.add("Status");
                displayColumns.addAll(columns);

                StringBuilder separator = new StringBuilder("+");
                for (int i = 0; i < displayColumns.size(); i++) {
                    separator.append("-".repeat(colWidth)).append("+");
                }
                output.append(separator).append("\n");

                StringBuilder header = new StringBuilder("|");
                for (String col : displayColumns) {
                    header.append(String.format(" %-" + (colWidth - 2) + "s |", col));
                }
                output.append(header).append("\n").append(separator).append("\n");

                for (int i = 0; i < changes.size(); i++) {
                    NsonObject change = changes.getObject(i);
                    String type = change.getString("type");
                    NsonObject before = change.getObject("before");
                    NsonObject after = change.getObject("after");

                    if (type.equals("modified")) {
                        StringBuilder beforeRow = new StringBuilder("|");
                        beforeRow.append(String.format(" %-" + (colWidth - 2) + "s |", "Before"));
                        for (String col : columns) {
                            String val = before != null && before.get(col) != null ? before.get(col).toString() : "NULL";
                            beforeRow.append(String.format(" %-" + (colWidth - 2) + "s |",
                                    val.length() > colWidth - 2 ? val.substring(0, colWidth - 5) + "..." : val));
                        }
                        output.append(beforeRow).append("\n");

                        StringBuilder afterRow = new StringBuilder("|");
                        afterRow.append(String.format(" %-" + (colWidth - 2) + "s |", "After"));
                        for (String col : columns) {
                            String val = after != null && after.get(col) != null ? after.get(col).toString() : "NULL";
                            afterRow.append(String.format(" %-" + (colWidth - 2) + "s |",
                                    val.length() > colWidth - 2 ? val.substring(0, colWidth - 5) + "..." : val));
                        }
                        output.append(afterRow).append("\n");
                    } else if (type.equals("added")) {
                        StringBuilder row = new StringBuilder("|");
                        row.append(String.format(" %-" + (colWidth - 2) + "s |", "Added"));
                        for (String col : columns) {
                            String val = after != null && after.get(col) != null ? after.get(col).toString() : "NULL";
                            row.append(String.format(" %-" + (colWidth - 2) + "s |",
                                    val.length() > colWidth - 2 ? val.substring(0, colWidth - 5) + "..." : val));
                        }
                        output.append(row).append("\n");
                    } else if (type.equals("deleted")) {
                        StringBuilder row = new StringBuilder("|");
                        row.append(String.format(" %-" + (colWidth - 2) + "s |", "Deleted"));
                        for (String col : columns) {
                            String val = before != null && before.get(col) != null ? before.get(col).toString() : "NULL";
                            row.append(String.format(" %-" + (colWidth - 2) + "s |",
                                    val.length() > colWidth - 2 ? val.substring(0, colWidth - 5) + "..." : val));
                        }
                        output.append(row).append("\n");
                    }
                }

                output.append(separator).append("\n");
            }

            output.append("\u001B[33m-\u001B[0m To approve, use: /approve ").append(username).append("\n");
            output.append("\u001B[33m-\u001B[0m To reject, use: /reject ").append(username).append("\n");
            logger.accept(output.toString());
        }
    }

    static class ConnectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String client = exchange.getRemoteAddress().toString();
            logger.accept("\u001B[33m-\u001B[0m Incoming connect request from " + client);

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Only POST method supported");
                return;
            }

            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                logger.accept("DEBUG: Connect request body: " + body);
                NsonObject request = NsonObject.parse(body);
                String shareUsername = request.getString("share_username");

                if (shareUsername == null || shareUsername.isEmpty()) {
                    sendError(exchange, 400, "Missing share_username in request");
                    return;
                }

                String usersFilePath = "njsql_data/" + shareUsername + "/users.nson";
                File usersFile = new File(usersFilePath);
                if (!usersFile.exists()) {
                    sendError(exchange, 404, "Users file not found for user: " + shareUsername);
                    return;
                }

                String usersContent = FileUtils.readFileUtf8(usersFilePath);
                NsonObject response = new NsonObject()
                        .put("success", true)
                        .put("users", usersContent);
                sendResponse(exchange, 200, response.toString(2));
            } catch (IOException e) {
                logger.accept("Server> Failed to read users.nson: " + e.getMessage());
                sendError(exchange, 500, "Failed to read users.nson: " + e.getMessage());
            } catch (Exception e) {
                logger.accept("Server> Server error: " + e.getMessage());
                sendError(exchange, 500, "Server error: " + e.getMessage());
            }
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    private static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        NsonObject error = new NsonObject().put("error", message);
        sendResponse(exchange, statusCode, error.toString(2));
    }
}