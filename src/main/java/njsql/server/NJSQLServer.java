package njsql.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import njsql.core.SelectHandler;
import njsql.core.InsertHandler;
import njsql.core.DeleteHandler;
import njsql.core.UpdateHandler;
import njsql.core.UserManager;
import njsql.models.User;
import njsql.nson.NsonObject;

// --- [TẠM KHÓA gRPC ĐỂ FIX LỖI NoClassDefFoundError] ---
// import io.grpc.Server;
// import io.grpc.ServerBuilder;
// import njsql.grpc.NJSQLGrpcService;
// -------------------------------------------------------

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class NJSQLServer {

    // private static Server grpcServer; // Tạm khóa

    public static void start(Consumer<String> logger) {
        try {
            // --- 1. REST Server (Cái này quan trọng cho Web Demo) ---
            int restPort = 2801;
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", restPort), 0);
            httpServer.createContext("/query", new NsonFileHandler(logger));
            httpServer.setExecutor(null);
            httpServer.start();
            logger.accept("\u001B[36m[REST] Server running on http://0.0.0.0:" + restPort + "\u001B[0m");

            /* --- 2. gRPC Server (Tạm khóa vì thiếu thư viện) ---
            int grpcPort = 50051;
            grpcServer = ServerBuilder.forPort(grpcPort)
                    .addService(new NJSQLGrpcService())
                    .build()
                    .start();
            logger.accept("\u001B[35m[gRPC] Server running on port " + grpcPort + "\u001B[0m");
            */

            // Shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.accept("\u001B[33mShutting down servers...\u001B[0m");
                httpServer.stop(0);
                /*
                if (grpcServer != null) {
                    grpcServer.shutdown();
                }
                */
            }));

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
            // logger.accept("> Incoming request from " + client); // Comment bớt cho đỡ spam log

            // CORS Headers (Quan trọng cho Web)
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Only POST method supported", logger);
                return;
            }

            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                // logger.accept("> Body: " + body);

                NsonObject request = NsonObject.parse(body);
                String username = request.getString("username");
                String password = request.getString("password");
                String database = request.getString("database");
                String sql = request.getString("sql");

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

                User user = new User(username, password, "localhost", 2801);
                user.setCurrentDatabase(database);

                NsonObject response;
                String upperSql = sql.trim().toUpperCase();
                if (upperSql.startsWith("SELECT")) {
                    response = SelectHandler.handleForAPI(sql, user);
                } else if (upperSql.startsWith("INSERT")) {
                    response = InsertHandler.handleForAPI(sql, user);
                } else if (upperSql.startsWith("DELETE")) {
                    response = DeleteHandler.handleForAPI(sql, user);
                } else if (upperSql.startsWith("UPDATE")) {
                    response = UpdateHandler.handleForAPI(sql, user);
                } else {
                    sendError(exchange, 400, "Only SELECT, INSERT, DELETE, UPDATE are supported", logger);
                    return;
                }

                // Log câu lệnh SQL đã chạy thành công (Màu xanh lá)
                logger.accept("\u001B[32m[API] Executed: \u001B[0m" + sql);

                if (response.containsKey("error")) {
                    sendError(exchange, 500, response.getString("error"), logger);
                } else {
                    sendResponse(exchange, 200, response.toString(2), logger);
                }

            } catch (Exception e) {
                sendError(exchange, 500, "Query processing error: " + e.getMessage(), logger);
                e.printStackTrace();
            }
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String responseBody, Consumer<String> logger) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }

        private void sendError(HttpExchange exchange, int statusCode, String message, Consumer<String> logger) throws IOException {
            NsonObject error = new NsonObject().put("error", message);
            sendResponse(exchange, statusCode, error.toString(2), logger);
            logger.accept("\u001B[31m[API Error] \u001B[0m" + message);
        }
    }
}