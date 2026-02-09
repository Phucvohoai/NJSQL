package njsql.core;

import njsql.models.User;
import njsql.utils.FileUtils;
import njsql.nson.NsonObject;
// import njsql.nson.NsonArray; // Không cần dùng nữa vì bỏ check quyền ở client
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.stream.Stream;

public class PushHandler {

    public static String handle(String command, User user, String commitMessage, String clientDbPath, String shareUsername) throws Exception {
        // 1. Validate đầu vào cơ bản
        String[] parts = command.trim().split("\\s+");
        if (parts.length != 2 || !parts[0].equalsIgnoreCase("/push")) {
            throw new IllegalArgumentException("Invalid command. Usage: /push <db_name>");
        }
        String dbName = parts[1];

        if (user == null || user.getUsername() == null || user.getPassword() == null) {
            throw new IllegalArgumentException("Please provide username and password for authentication.");
        }

        if (shareUsername == null || shareUsername.isEmpty()) {
            throw new IllegalArgumentException("Share username cannot be empty.");
        }

        // --- ĐOẠN CHECK QUYỀN CŨ (ĐÃ XÓA) ---
        // Lý do: Client không có trách nhiệm (và không đủ dữ liệu tin cậy) để check quyền.
        // Việc check quyền là của Server. Cứ gửi đi, sai thì Server báo lỗi về.

        // 2. Thu thập file từ client để đóng gói
        NsonObject files = new NsonObject();
        Path startPath = Paths.get(clientDbPath);

        if (!Files.exists(startPath)) {
            throw new IllegalArgumentException("Database path not found at client: " + clientDbPath);
        }

        try (Stream<Path> stream = Files.walk(startPath)) {
            stream.filter(path -> path.toString().endsWith(".nson")) // Chỉ lấy file .nson
                  .filter(path -> !path.toString().contains(".commits")) // Bỏ qua thư mục .commits (Backup)
                  .forEach(path -> {
                        try {
                            // Lấy đường dẫn tương đối (Ví dụ: users/data.nson)
                            String relativePath = startPath.relativize(path).toString();
                            
                            // CHUẨN HÓA PATH: Đổi hết \ thành / để gửi qua mạng (JSON chuẩn)
                            String normalizedPath = relativePath.replace("\\", "/");

                            String content = FileUtils.readFileUtf8(path.toString());
                            files.put(normalizedPath, content);
                            
                            System.out.println("DEBUG: Packed file: " + normalizedPath);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read file " + path + ": " + e.getMessage(), e);
                        }
                  });
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Error packing database files: " + e.getMessage(), e);
        }

        if (files.isEmpty()) {
            throw new IllegalArgumentException("No .nson files found to push in: " + clientDbPath);
        }

        // 3. Chuẩn bị Request gửi lên Server
        // URL này trỏ về Server thật (dựa trên cấu hình của user đang login)
        String serverUrl = "http://" + user.getHost() + ":" + user.getPort() + "/query"; // Lưu ý: Endpoint thường là /query hoặc /push tùy server ní định nghĩa
        
        // Nếu server ní tách riêng endpoint /push thì dùng dòng dưới:
        serverUrl = "http://" + user.getHost() + ":" + user.getPort() + "/push";

        String commitId = UUID.randomUUID().toString();
        
        NsonObject requestBody = new NsonObject()
                .put("username", user.getUsername())
                .put("password", user.getPassword()) // Gửi pass để Server check auth
                .put("database", dbName)
                .put("commit_id", commitId)
                .put("commit_message", commitMessage)
                .put("files", files)
                .put("share_username", shareUsername); // Báo cho server biết mình muốn push vào DB của ai

        System.out.println("DEBUG: Sending Push Request to " + serverUrl + " [CommitID: " + commitId + "]");

        // 4. Gửi HTTP POST
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000); // Timeout 5s cho đỡ treo

            // Ghi dữ liệu
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // 5. Xử lý phản hồi từ Server (QUAN TRỌNG)
            int responseCode = conn.getResponseCode();
            System.out.println("DEBUG: Server Response Code: " + responseCode);

            if (responseCode == 200) {
                // Thành công: Đọc phản hồi JSON
                String responseStr = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                NsonObject responseObj = NsonObject.parse(responseStr);

                if (responseObj.getBoolean("success")) {
                    // 6. Backup commit tại Client (Optional - Để lỡ mạng lag còn có cái mà xem lại)
                    saveLocalBackup(clientDbPath, commitId, user.getUsername(), dbName, files);
                    
                    return "✅ Push successful! Commit " + commitId + " sent for approval.";
                } else {
                    // Server trả về 200 nhưng logic lỗi (success: false)
                    throw new IllegalArgumentException("Server Rejected Push: " + responseObj.getString("error"));
                }
            } else {
                // Thất bại (403, 401, 500...): Đọc Error Stream để biết lý do
                String errorMsg = "Unknown Server Error";
                try (InputStream errorStream = conn.getErrorStream()) {
                    if (errorStream != null) {
                        errorMsg = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
                throw new IllegalArgumentException("❌ Push Failed (HTTP " + responseCode + "): " + errorMsg);
            }

        } catch (IOException e) {
            throw new IllegalArgumentException("Network Error: Could not connect to Server at " + serverUrl + ". Is it running? (" + e.getMessage() + ")");
        }
    }

    // Hàm phụ: Lưu backup commit ở client (Logic cũ của ní, tui tách ra cho gọn)
    private static void saveLocalBackup(String clientDbPath, String commitId, String username, String dbName, NsonObject files) {
        try {
            String commitPath = clientDbPath + "/.commits/" + commitId + ".json";
            FileUtils.createDirectory(clientDbPath + "/.commits");
            
            NsonObject commitData = new NsonObject()
                    .put("timestamp", System.currentTimeMillis())
                    .put("username", username)
                    .put("db_name", dbName)
                    .put("files", files);
            
            FileUtils.writeFileUtf8(commitPath, commitData.toString());
            System.out.println("DEBUG: Local backup saved to .commits/" + commitId + ".json");
        } catch (Exception e) {
            System.err.println("WARNING: Failed to save local backup (ignoring): " + e.getMessage());
        }
    }
}