package njsql.grpc;

import io.grpc.stub.StreamObserver;
import njsql.core.*;
import njsql.models.User;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import njsql.proto.NJSQLProto.QueryRequest;
import njsql.proto.NJSQLProto.QueryResponse;
import njsql.proto.NJSQLProto.SubscribeRequest;
import njsql.proto.NJSQLProto.TableUpdate;
import njsql.proto.NJSQLProto.NsonRow;
import njsql.proto.NJSQLGrpc;

import java.io.File;

public class NJSQLGrpcService extends NJSQLGrpc.NJSQLImplBase {

    @Override
    public void execute(QueryRequest req, StreamObserver<QueryResponse> responseObserver) {
        QueryResponse.Builder resp = QueryResponse.newBuilder();

        try {
            String username = req.getUsername();
            String password = req.getPassword();
            String database = req.getDatabase();
            String sql = req.getSql().trim();

            // === 1. Xác thực ===
            NsonObject auth = UserManager.checkLogin(username, password);
            if (!auth.getBoolean("success")) {
                responseObserver.onNext(
                        resp.setSuccess(false)
                                .setError("Invalid username or password")
                                .build()
                );
                responseObserver.onCompleted();
                return;
            }

            // === 2. Tạo session user ===
            User user = new User(username, password, "grpc", 0);
            user.setCurrentDatabase(database);

            // === 3. Kiểm tra database tồn tại ===
            String dbPath = UserManager.getRootDirectory(username) + "/" + database;
            if (!new File(dbPath).isDirectory()) {
                responseObserver.onNext(
                        resp.setSuccess(false)
                                .setError("Database '" + database + "' does not exist")
                                .build()
                );
                responseObserver.onCompleted();
                return;
            }

            // === 4. Xử lý SQL ===
            String upperSql = sql.toUpperCase();
            NsonObject result;

            if (upperSql.startsWith("SELECT")) {
                result = SelectHandler.handleForAPI(sql, user);
            } else if (upperSql.startsWith("INSERT")) {
                result = InsertHandler.handleForAPI(sql, user);
            } else if (upperSql.startsWith("UPDATE")) {
                result = UpdateHandler.handleForAPI(sql, user);
            } else if (upperSql.startsWith("DELETE")) {
                result = DeleteHandler.handleForAPI(sql, user);
            } else {
                responseObserver.onNext(
                        resp.setSuccess(false)
                                .setError("Unsupported command. Use SELECT, INSERT, UPDATE, DELETE.")
                                .build()
                );
                responseObserver.onCompleted();
                return;
            }

            // === 5. Xây dựng phản hồi ===
            if (result.containsKey("error")) {
                resp.setSuccess(false).setError(result.getString("error"));
            } else {
                resp.setSuccess(true);

                if (result.containsKey("message")) {
                    resp.setMessage(result.getString("message"));
                }
                if (result.containsKey("rowsAffected")) {
                    resp.setRowsAffected(result.getInt("rowsAffected"));
                }

                // === 6. Gửi dữ liệu SELECT (NsonRow) ===
                if (result.containsKey("data")) {
                    NsonArray data = result.getArray("data");

                    for (int i = 0; i < data.size(); i++) {
                        NsonObject row = data.getObject(i);
                        NsonRow.Builder rowBuilder = NsonRow.newBuilder();

                        // Đổ dữ liệu từ NsonObject → map<string, string>
                        for (String key : row.keySet()) {
                            Object val = row.get(key);
                            String strVal = val == null ? "NULL" : val.toString();
                            rowBuilder.putFields(key, strVal);
                        }

                        resp.addData(rowBuilder.build()); // addData đúng kiểu
                    }
                }
            }

        } catch (Exception e) {
            resp.setSuccess(false).setError("Internal server error: " + e.getMessage());
            e.printStackTrace();
        }

        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    @Override
    public void subscribe(SubscribeRequest req, StreamObserver<TableUpdate> responseObserver) {
        String username = req.getUsername();
        String password = req.getPassword();
        String database = req.getDatabase();
        String table = req.getTable();

        try {
            // === 1. Xác thực ===
            NsonObject auth = UserManager.checkLogin(username, password);
            if (!auth.getBoolean("success")) {
                responseObserver.onError(new IllegalArgumentException("Invalid credentials"));
                return;
            }

            User user = new User(username, password, "grpc", 0);
            user.setCurrentDatabase(database);

            // === 2. Kiểm tra bảng tồn tại ===
            String rootDir = UserManager.getRootDirectory(username);
            File file = new File(rootDir + "/" + database + "/" + table + ".nson");
            if (!file.exists()) {
                responseObserver.onError(new IllegalArgumentException("Table '" + table + "' not found"));
                return;
            }

            String tableKey = database + "." + table;

            // === 3. Listener cho thay đổi realtime ===
            java.util.function.Consumer<RealtimeTableManager.TableChange> listener = change -> {
                for (NsonObject row : change.rows) {
                    TableUpdate update = TableUpdate.newBuilder()
                            .setAction(change.action)
                            .setRow(row.toString())  // .proto có field row → OK
                            .setTimestamp(change.timestamp)
                            .build();
                    responseObserver.onNext(update);
                }
            };

            // === 4. Đăng ký listener ===
            RealtimeTableManager.addListener(tableKey, listener);

            // === 5. Gửi thông báo kết nối thành công ===
            TableUpdate init = TableUpdate.newBuilder()
                    .setAction("SUBSCRIBED")
                    .setRow("Connected to realtime updates for table: " + tableKey)
                    .setTimestamp(System.currentTimeMillis())
                    .build();
            responseObserver.onNext(init);

            // KHÔNG GỌI onCompleted() → giữ stream mở

        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }
}