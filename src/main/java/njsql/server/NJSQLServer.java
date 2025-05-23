package njsql.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import njsql.core.InsertHandler;
import njsql.core.UserManager;
import njsql.models.User;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NJSQLServer {

    public static void start(Consumer<String> logger) {
        try {
            int port = 2801;
            HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            logger.accept("\u001B[36m Server running on http://0.0.0.0:\u001B[0m" + port + " (accessible externally)");

            server.createContext("/query", new NsonFileHandler(logger));
            server.setExecutor(null);
            server.start();
        } catch (IOException e) {
            logger.accept("\u001B[31m Error starting server: \u001B[0m" + e.getMessage());
        }
    }

    static class NsonFileHandler implements HttpHandler {
        private final Consumer<String> logger;

        public NsonFileHandler(Consumer<String> logger) {
            this.logger = logger;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String client = exchange.getRemoteAddress().toString();
            logger.accept("> Incoming request from " + client);

            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                logger.accept("> Preflight (OPTIONS) request");
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Only POST method supported", logger);
                return;
            }

            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                logger.accept("> Request body: " + body);

                NsonObject request = NsonObject.parse(body);
                String username = request.getString("username");
                String password = request.getString("password");
                String database = request.getString("database");
                String sql = request.getString("sql");

                logger.accept("> Auth request for user: " + username + " | DB: " + database);
                logger.accept("> SQL: " + sql);

                NsonObject authResult = UserManager.checkLogin(username, password);
                if (!authResult.getBoolean("success")) {
                    sendError(exchange, 401, "Invalid username or password", logger);
                    return;
                }

                String dbPath = "njsql_data/" + username + "/" + database;
                File dbDir = new File(dbPath);
                if (!dbDir.exists() || !dbDir.isDirectory()) {
                    sendError(exchange, 404, "Database '" + database + "' does not exist", logger);
                    return;
                }

                NsonObject response;
                User user = new User(username, password, "localhost", 2801); // Constructor 4 tham số
                if (sql.trim().toUpperCase().startsWith("SELECT")) {
                    response = handleSelect(sql, dbPath);
                } else if (sql.trim().toUpperCase().startsWith("INSERT")) {
                    String result = InsertHandler.handle(sql, username, dbPath);
                    response = new NsonObject()
                            .put("status", "success")
                            .put("message", "Inserted into " + result)
                            .put("rowsAffected", countInsertedRows(sql));
                } else if (sql.trim().toUpperCase().startsWith("DELETE")) {
                    response = handleDelete(sql, dbPath);
                } else if (sql.trim().toUpperCase().startsWith("UPDATE")) {
                    response = handleUpdate(sql, dbPath);
                } else {
                    sendError(exchange, 400, "Only SELECT, INSERT, DELETE, UPDATE are supported", logger);
                    return;
                }

                sendResponse(exchange, 200, response.toString(2), logger);
                logger.accept("\u001B[32m>> Query executed: \u001B[0m" + sql);

            } catch (Exception e) {
                sendError(exchange, 500, "Query processing error: " + e.getMessage(), logger);
                logger.accept("\u001B[1m Query failed: \u001B[0m" + e.getMessage());
                e.printStackTrace();
            }
        }

        private int countInsertedRows(String sql) {
            Pattern tuplePattern = Pattern.compile("\\(([^)]+)\\)");
            Matcher tupleMatcher = tuplePattern.matcher(sql);
            int count = 0;
            while (tupleMatcher.find()) {
                count++;
            }
            return count;
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response, Consumer<String> logger) throws IOException {
            logger.accept("Server response: " + response);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }

        private void sendError(HttpExchange exchange, int statusCode, String message, Consumer<String> logger) throws IOException {
            NsonObject error = new NsonObject().put("error", message);
            sendResponse(exchange, statusCode, error.toString(2), logger);
            logger.accept("\u001B[31m Error (" + statusCode + "): \u001B[0m" + message);
        }

        private NsonObject handleSelect(String sql, String dbPath) throws IOException {
            Pattern selectPattern = Pattern.compile(
                    "SELECT\\s+(.+?)\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+?))?(?:\\s+ORDER\\s+BY\\s+(\\w+)\\s*(ASC|DESC)?)?",
                    Pattern.CASE_INSENSITIVE);
            Matcher matcher = selectPattern.matcher(sql.trim());
            if (!matcher.find()) {
                return new NsonObject().put("error", "Invalid SELECT syntax");
            }

            String columnsStr = matcher.group(1).trim();
            String tableName = matcher.group(2);
            String condition = matcher.group(3);
            String orderByColumn = matcher.group(4);
            String orderDirection = matcher.group(5) != null ? matcher.group(5).toUpperCase() : "ASC";

            String tablePath = dbPath + "/" + tableName + ".nson";
            File tableFile = new File(tablePath);

            if (!tableFile.exists()) {
                return new NsonObject().put("error", "Table '" + tableName + "' does not exist");
            }

            String nsonContent = Files.readString(tableFile.toPath(), StandardCharsets.UTF_8);
            NsonObject tableData = NsonObject.parse(nsonContent);
            NsonArray data = tableData.getArray("data");

            NsonArray filteredData = condition == null ? data : filterData(data, condition);

            if (orderByColumn != null) {
                filteredData = sortData(filteredData, orderByColumn, orderDirection);
            }

            return new NsonObject().put("data", filteredData);
        }

        private NsonArray sortData(NsonArray data, String column, String direction) {
            List<NsonObject> dataList = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                dataList.add(data.getObject(i));
            }

            dataList.sort((row1, row2) -> {
                Object val1 = row1.get(column);
                Object val2 = row2.get(column);
                if (val1 == null && val2 == null) return 0;
                if (val1 == null) return direction.equals("ASC") ? -1 : 1;
                if (val2 == null) return direction.equals("ASC") ? 1 : -1;

                try {
                    double num1 = Double.parseDouble(val1.toString());
                    double num2 = Double.parseDouble(val2.toString());
                    double diff = num1 - num2;
                    return direction.equals("ASC") ? (int) diff : -(int) diff;
                } catch (NumberFormatException e) {
                    String str1 = val1.toString();
                    String str2 = val2.toString();
                    return direction.equals("ASC") ? str1.compareTo(str2) : str2.compareTo(str1);
                }
            });

            NsonArray sortedData = new NsonArray();
            for (NsonObject row : dataList) {
                sortedData.add(row);
            }
            return sortedData;
        }

        private NsonObject handleDelete(String sql, String dbPath) throws IOException {
            Pattern deletePattern = Pattern.compile("DELETE\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+))?", Pattern.CASE_INSENSITIVE);
            Matcher matcher = deletePattern.matcher(sql.trim());
            if (!matcher.find()) {
                return new NsonObject().put("error", "Invalid DELETE syntax");
            }

            String tableName = matcher.group(1);
            String condition = matcher.group(2);
            String tablePath = dbPath + "/" + tableName + ".nson";
            File tableFile = new File(tablePath);

            if (!tableFile.exists()) {
                return new NsonObject().put("error", "Table '" + tableName + "' does not exist");
            }

            String nsonContent = Files.readString(tableFile.toPath(), StandardCharsets.UTF_8);
            NsonObject tableData = NsonObject.parse(nsonContent);
            NsonArray data = tableData.getArray("data");

            if (condition == null) {
                tableData.put("data", new NsonArray());
                Files.writeString(tableFile.toPath(), tableData.toString(2), StandardCharsets.UTF_8);
                return new NsonObject()
                        .put("status", "success")
                        .put("message", "Deleted all rows from '" + tableName + "'");
            }

            NsonArray filtered = filterData(data, condition);
            int deletedCount = 0;
            NsonArray newData = new NsonArray();
            for (int i = 0; i < data.size(); i++) {
                NsonObject row = (NsonObject) data.get(i);
                boolean matched = false;
                for (int j = 0; j < filtered.size(); j++) {
                    if (row.toString().equals(filtered.get(j).toString())) {
                        matched = true;
                        deletedCount++;
                        break;
                    }
                }
                if (!matched) {
                    newData.add(row);
                }
            }

            tableData.put("data", newData);
            Files.writeString(tableFile.toPath(), tableData.toString(2), StandardCharsets.UTF_8);
            return new NsonObject()
                    .put("status", "success")
                    .put("message", "Deleted " + deletedCount + " row(s) from '" + tableName + "'");
        }

        private NsonObject handleUpdate(String sql, String dbPath) throws IOException {
            Pattern updatePattern = Pattern.compile("UPDATE\\s+(\\w+)\\s+SET\\s+(.+?)\\s+WHERE\\s+(.+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = updatePattern.matcher(sql.trim());
            if (!matcher.find()) {
                return new NsonObject().put("error", "Invalid UPDATE syntax, WHERE clause required");
            }

            String tableName = matcher.group(1);
            String setClause = matcher.group(2).trim();
            String condition = matcher.group(3).trim();

            String tablePath = dbPath + "/" + tableName + ".nson";
            File tableFile = new File(tablePath);
            if (!tableFile.exists()) {
                return new NsonObject().put("error", "Table '" + tableName + "' does not exist");
            }

            String nsonContent = Files.readString(tableFile.toPath(), StandardCharsets.UTF_8);
            NsonObject tableData = NsonObject.parse(nsonContent);
            NsonArray data = tableData.getArray("data");

            NsonObject setValues = parseSetClause(setClause);
            NsonArray filtered = filterData(data, condition);
            int updatedRows = 0;

            for (int i = 0; i < data.size(); i++) {
                NsonObject row = (NsonObject) data.get(i);
                for (int j = 0; j < filtered.size(); j++) {
                    if (row.toString().equals(filtered.get(j).toString())) {
                        for (String colName : setValues.keySet()) {
                            row.put(colName, setValues.get(colName));
                        }
                        updatedRows++;
                        break;
                    }
                }
            }

            tableData.put("data", data);
            Files.writeString(tableFile.toPath(), tableData.toString(2), StandardCharsets.UTF_8);
            return new NsonObject()
                    .put("status", "success")
                    .put("message", "Updated " + updatedRows + " rows in '" + tableName + "'")
                    .put("rowsAffected", updatedRows);
        }

        private String[] parseValues(String values) {
            List<String> valueList = new ArrayList<>();
            Matcher matcher = Pattern.compile("'([^']*)'|([^,]+)").matcher(values);
            while (matcher.find()) {
                String quoted = matcher.group(1);
                String unquoted = matcher.group(2);
                valueList.add(quoted != null ? quoted : unquoted.trim());
            }
            return valueList.toArray(new String[0]);
        }

        private NsonObject parseSetClause(String setClause) {
            NsonObject setValues = new NsonObject();
            Matcher matcher = Pattern.compile("(\\w+)\\s*=\\s*(?:'([^']+)'|([^'\\s,]+))").matcher(setClause);
            while (matcher.find()) {
                String key = matcher.group(1);
                String quotedValue = matcher.group(2);
                String unquotedValue = matcher.group(3);
                Object value = quotedValue != null ? quotedValue : unquotedValue;
                try {
                    setValues.put(key, Integer.parseInt(value.toString()));
                } catch (NumberFormatException e) {
                    setValues.put(key, value);
                }
            }
            return setValues;
        }

        private NsonArray filterData(NsonArray data, String condition) {
            NsonArray result = new NsonArray();
            Pattern pattern = Pattern.compile("(\\w+)\\s*(?:LIKE\\s*['\"]([^'\"]+)['\"]|[=<>]\\s*['\"]?([^'\"]+)['\"]?)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(condition);
            if (!matcher.find()) {
                return data;
            }

            String column = matcher.group(1);
            String likePattern = matcher.group(2);
            String equalValue = matcher.group(3);

            for (int i = 0; i < data.size(); i++) {
                NsonObject row = (NsonObject) data.get(i);
                if (!row.containsKey(column)) {
                    continue;
                }
                String value = row.getString(column);

                if (likePattern != null) {
                    String regex = likePattern.replace("%", ".*").replace("*", ".*");
                    if (value.matches(regex)) {
                        result.add(row);
                    }
                } else if (equalValue != null) {
                    if (value.equals(equalValue)) {
                        result.add(row);
                    }
                }
            }
            return result;
        }
    }

    public static void main(String[] args) {
        start(System.out::println);
    }
}