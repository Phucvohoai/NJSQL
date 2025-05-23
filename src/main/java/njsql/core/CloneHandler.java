package njsql.core;

import njsql.models.User;
import njsql.utils.FileUtils;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;

public class CloneHandler {
    public static String handle(String command, User user, String ip, String shareUsername) throws Exception {
        String[] parts = command.trim().split("\\s+");
        if (parts.length != 2 || !parts[0].equalsIgnoreCase("/clone")) {
            throw new IllegalArgumentException("Invalid command. Usage: /clone <db_name>");
        }
        String dbName = parts[1];

        if (user == null || user.getUsername() == null || user.getPassword() == null) {
            throw new IllegalArgumentException("Please provide username and password for authentication.");
        }

        if (shareUsername == null || shareUsername.isEmpty()) {
            throw new IllegalArgumentException("Share username cannot be empty.");
        }

        // Kiểm tra xem user có trong danh sách children của chủ database
        NsonObject usersData = UserManager.readUsersData();
        if (usersData == null) {
            throw new IllegalStateException("Cannot read users data.");
        }
        NsonArray users = usersData.getArray("users");
        boolean isChild = false;
        String ownerUsername = null;
        for (int i = 0; i < users.size(); i++) {
            NsonObject u = users.getObject(i);
            NsonArray children = u.getArray("children");
            if (children != null && children.contains(user.getUsername()) && u.getString("username").equals(shareUsername)) {
                isChild = true;
                ownerUsername = u.getString("username");
                break;
            }
        }
        if (!isChild) {
            throw new IllegalArgumentException("You are not authorized to clone this database. Only children of the owner (" + shareUsername + ") can clone.");
        }

        // Gửi yêu cầu đến server để kiểm tra và lấy database
        String serverUrl = "http://" + ip + ":1201/clone";
        NsonObject request = new NsonObject()
                .put("username", user.getUsername())
                .put("password", user.getPassword())
                .put("database", dbName)
                .put("share_username", shareUsername);

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(request.toString().getBytes("UTF-8"));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                String errorResponse = new String(conn.getErrorStream().readAllBytes(), "UTF-8");
                NsonObject errorObj = NsonObject.parse(errorResponse);
                String errorMessage = errorObj.getString("error");
                if (errorMessage == null || errorMessage.isEmpty()) {
                    errorMessage = "Unknown error from server";
                }
                throw new IllegalArgumentException("Failed to clone database '" + dbName + "' from server at " + ip + ": " + errorMessage);
            }

            // Đọc dữ liệu database từ server
            String response = new String(conn.getInputStream().readAllBytes(), "UTF-8");
            NsonObject responseObj = NsonObject.parse(response);
            if (!responseObj.getBoolean("success")) {
                throw new IllegalArgumentException("Failed to clone database: " + responseObj.getString("error"));
            }

            // Lưu database vào thư mục client
            String clientRootDir = UserManager.getRootDirectory(user.getUsername());
            String clientDbPath = clientRootDir + "/" + dbName;
            FileUtils.createDirectory(clientDbPath);

            // Giả sử server trả về danh sách file .nson và nội dung
            NsonObject files = responseObj.getObject("files");
            for (String tableName : files.keySet()) {
                String content = files.getString(tableName);
                String filePath = clientDbPath + "/" + tableName;
                if (!tableName.toLowerCase().endsWith(".nson")) {
                    filePath += ".nson";
                }
                FileUtils.writeFileUtf8(filePath, content);
            }

            return "Cloned database '" + dbName + "' from " + ip + " to " + clientDbPath + ".";
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to connect to server at " + ip + ": " + e.getMessage());
        }
    }
}