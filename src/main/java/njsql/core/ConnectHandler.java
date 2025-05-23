package njsql.core;

import njsql.utils.FileUtils;
import njsql.nson.NsonObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

public class ConnectHandler {
    public static String handle(String ip) throws Exception {
        Scanner scanner = new Scanner(System.in);
        System.out.print(">> Enter username of the database owner: ");
        String shareUsername = scanner.nextLine().trim();
        if (shareUsername.isEmpty()) {
            throw new IllegalArgumentException("Share username cannot be empty.");
        }

        String serverUrl = "http://" + ip + ":1201/connect";
        System.out.println("DEBUG: Sending POST to: " + serverUrl); // Thêm debug
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            NsonObject request = new NsonObject()
                    .put("share_username", shareUsername);
            System.out.println("DEBUG: Request body: " + request.toString()); // Thêm debug
            try (OutputStream os = conn.getOutputStream()) {
                os.write(request.toString().getBytes("UTF-8"));
            }

            int responseCode = conn.getResponseCode();
            System.out.println("DEBUG: Response code: " + responseCode); // Thêm debug
            if (responseCode != 200) {
                String errorResponse = new String(conn.getErrorStream().readAllBytes(), "UTF-8");
                throw new IllegalArgumentException("Failed to retrieve users.nson from server at " + ip + ". Server response: " + errorResponse);
            }

            String response = new String(conn.getInputStream().readAllBytes(), "UTF-8");
            NsonObject responseObj = NsonObject.parse(response);
            if (!responseObj.getBoolean("success")) {
                throw new IllegalArgumentException("Failed to retrieve users.nson: " + responseObj.getString("error"));
            }

            String usersNsonPath = "njsql_data/" + shareUsername + "/users.nson";
            String usersContent = responseObj.getString("users");
            FileUtils.createDirectory("njsql_data/" + shareUsername);
            FileUtils.writeFileUtf8(usersNsonPath, usersContent);

            return "- \u001B[32mSuccessfully\u001B[0m retrieved users.nson from \u001B[36m" + ip + "\u001B[0m for user \u001B[32m" + shareUsername + "\u001B[0m and saved to \u001B[33m" + usersNsonPath + "\u001B[0m.";
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to connect to server at " + ip + ": " + e.getMessage());
        }
    }
}