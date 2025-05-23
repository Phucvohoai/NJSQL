package njsql.core;

import njsql.models.User;
import njsql.utils.FileUtils;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.UUID;

public class PushHandler {
    public static String handle(String command, User user, String commitMessage, String clientDbPath, String shareUsername) throws Exception {
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

        // Kiểm tra xem user có trong danh sách children của chủ database
        NsonObject usersData = UserManager.readUsersData();
        if (usersData == null) {
            throw new IllegalStateException("Cannot read users data.");
        }
        NsonArray users = usersData.getArray("users");
        boolean isChild = false;
        for (int i = 0; i < users.size(); i++) {
            NsonObject u = users.getObject(i);
            NsonArray children = u.getArray("children");
            if (children != null && children.contains(user.getUsername()) && u.getString("username").equals(shareUsername)) {
                isChild = true;
                break;
            }
        }
        if (!isChild) {
            throw new IllegalArgumentException("You are not authorized to push to this database. Only children of the owner (" + shareUsername + ") can push.");
        }

        // Thu thập file từ client, loại bỏ file trong .commits/
        NsonObject files = new NsonObject();
        try {
            Files.walk(Paths.get(clientDbPath))
                    .filter(path -> path.toString().endsWith(".nson"))
                    .filter(path -> !path.toString().contains("/.commits/") && !path.toString().contains("\\.commits\\")) // Loại bỏ file commit
                    .forEach(path -> {
                        try {
                            String relativePath = Paths.get(clientDbPath).relativize(path).toString();
                            String content = FileUtils.readFileUtf8(path.toString());
                            files.put(relativePath.replace("\\", "/"), content);
                            System.out.println("DEBUG: Added file to push: " + relativePath);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read file " + path + ": " + e.getMessage(), e);
                        }
                    });
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage(), e.getCause());
        }

        // Gửi yêu cầu push đến server
        String serverUrl = "http://" + user.getHost() + ":" + user.getPort() + "/push";
        String commitId = UUID.randomUUID().toString();
        NsonObject request = new NsonObject()
                .put("username", user.getUsername())
                .put("password", user.getPassword())
                .put("database", dbName)
                .put("commit_id", commitId)
                .put("commit_message", commitMessage)
                .put("files", files)
                .put("share_username", shareUsername);

        System.out.println("DEBUG: Sending POST to: " + serverUrl);
        System.out.println("DEBUG: Request body: " + request.toString());
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(request.toString().getBytes("UTF-8"));
            }

            int responseCode = conn.getResponseCode();
            System.out.println("DEBUG: Response code: " + responseCode);
            if (responseCode != 200) {
                String errorResponse = new String(conn.getErrorStream().readAllBytes(), "UTF-8");
                throw new IllegalArgumentException("Failed to push changes: " + errorResponse);
            }

            String response = new String(conn.getInputStream().readAllBytes(), "UTF-8");
            NsonObject responseObj = NsonObject.parse(response);
            if (!responseObj.getBoolean("success")) {
                throw new IllegalArgumentException("Failed to push changes: " + responseObj.getString("error"));
            }

            // Lưu commit file tại client (dự phòng)
            String commitPath = clientDbPath + "/.commits/" + commitId + ".json";
            FileUtils.createDirectory(clientDbPath + "/.commits");
            NsonObject commitData = new NsonObject()
                    .put("username", user.getUsername())
                    .put("db_name", dbName)
                    .put("files", files);
            FileUtils.writeFileUtf8(commitPath, commitData.toString());

            return "Push successful. Waiting for manager approval. Manager has been notified to review commit " + commitId + ".";
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to connect to server: " + e.getMessage());
        }
    }
}