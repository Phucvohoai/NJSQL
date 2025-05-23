package njsql.core;

import njsql.NJSQL;
import njsql.nson.NSON;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;

public class UserManager {
    private static final String BASE_PATH = "njsql_data/";
    private static final String CLIENT_USERS_FILE = BASE_PATH + "users.nson";
    private static String firstUser = null;

    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : encodedHash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Error hashing password: " + e.getMessage());
            return password;
        }
    }

    public static NsonObject checkLogin(String username, String password) {
        String configPath = CLIENT_USERS_FILE;
        File configFile = new File(configPath);
        String hashedPassword = hashPassword(password);

        if (configFile.exists()) {
            NsonObject config = readConfig(configPath);
            NsonArray users = config.getArray("users");
            if (users != null) {
                for (int i = 0; i < users.size(); i++) {
                    NsonObject user = users.getObject(i);
                    if (user.getString("username").equals(username) && user.getString("password").equals(hashedPassword)) {
                        if (firstUser == null) {
                            firstUser = username;
                        }
                        return new NsonObject().put("success", true).put("username", username);
                    }
                }
            }
        }

        File baseDir = new File(BASE_PATH);
        File[] adminDirs = baseDir.listFiles(File::isDirectory);
        if (adminDirs == null) {
//            System.out.println("DEBUG: No directories found in " + BASE_PATH);
            return new NsonObject().put("success", false).put("message", "No users found");
        }

        for (File adminDir : adminDirs) {
            configPath = adminDir.getPath() + "/users.nson";
//            System.out.println("DEBUG: Checking login configPath: " + configPath);
            if (new File(configPath).exists()) {
                NsonObject config = readConfig(configPath);
                NsonArray users = config.getArray("users");
                if (users != null) {
                    for (int i = 0; i < users.size(); i++) {
                        NsonObject user = users.getObject(i);
                        if (user.getString("username").equals(username) && user.getString("password").equals(hashedPassword)) {
                            if (firstUser == null) {
                                firstUser = adminDir.getName();
                            }
                            return new NsonObject().put("success", true).put("username", username);
                        }
                    }
                }
            }
        }
        return new NsonObject().put("success", false).put("message", "User not found or invalid credentials");
    }

    public static NsonObject checkLogin(String username, String password, String shareUsername) {
        String configPath = BASE_PATH + shareUsername + "/users.nson";
        File configFile = new File(configPath);
        String hashedPassword = hashPassword(password);

        if (!configFile.exists()) {
            return new NsonObject().put("success", false).put("message", "Users file not found for " + shareUsername);
        }

        NsonObject config = readConfig(configPath);
        NsonArray users = config.getArray("users");
        if (users == null) {
            return new NsonObject().put("success", false).put("message", "No users found in " + configPath);
        }

        boolean userValid = false;
        for (int i = 0; i < users.size(); i++) {
            NsonObject user = users.getObject(i);
            if (user.getString("username").equals(username) && user.getString("password").equals(hashedPassword)) {
                userValid = true;
                break;
            }
        }
        if (!userValid) {
            return new NsonObject().put("success", false).put("message", "Invalid credentials");
        }

        for (int i = 0; i < users.size(); i++) {
            NsonObject user = users.getObject(i);
            if (user.getString("username").equals(shareUsername)) {
                return new NsonObject()
                        .put("success", true)
                        .put("username", username)
                        .put("user", user);
            }
        }
        return new NsonObject().put("success", false).put("message", "Share username " + shareUsername + " not found");
    }

    public static boolean createUser(String username, String password, String parent) {
        if (username == null || username.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            System.out.println("\u001B[31m>> Username and password cannot be null or empty.\u001B[0m");
            return false;
        }

        String configPath;
        if (parent == null) {
            configPath = BASE_PATH + username + "/users.nson";
            File configFile = new File(configPath);
            if (configFile.exists()) {
                System.out.println("\u001B[31m>> User folder '" + username + "' already exists.\u001B[0m");
                return false;
            }
            configFile.getParentFile().mkdirs();
            if (firstUser == null) {
                firstUser = username;
            }
        } else {
            configPath = BASE_PATH + parent + "/users.nson";
            if (!new File(configPath).exists()) {
                System.out.println("\u001B[31m>> Parent user folder '" + parent + "' does not exist.\u001B[0m");
                return false;
            }
        }

        NsonObject config = readConfig(configPath);
        NsonArray users = config.getArray("users");
        if (users == null) {
            users = new NsonArray();
            config.put("users", users);
        }

        for (int i = 0; i < users.size(); i++) {
            NsonObject user = users.getObject(i);
            if (user.getString("username").equals(username)) {
                System.out.println("\u001B[31m>> User '" + username + "' already exists.\u001B[0m");
                return false;
            }
        }

        NsonObject newUser = new NsonObject();
        newUser.put("username", username);
        newUser.put("password", hashPassword(password));
        newUser.put("isAdmin", parent == null);

        if (parent != null) {
            newUser.put("permissions", new NsonArray());
            newUser.put("parent", parent);
        } else {
            newUser.put("children", new NsonArray());
        }

        if (parent != null) {
            for (int i = 0; i < users.size(); i++) {
                NsonObject user = users.getObject(i);
                if (user.getString("username").equals(parent)) {
                    NsonArray children = user.getArray("children");
                    if (children == null) {
                        children = new NsonArray();
                        user.put("children", children);
                    }
                    children.add(username);
                    break;
                }
            }
        }

        users.add(newUser);
        saveConfig(config, configPath);
        System.out.println(">>\u001B[32m User |\u001B[0m " + username + " \u001B[32m| created successfully.\u001B[0m");
        return true;
    }

    public static String getRootDirectory(String username) {
        if (firstUser == null) {
            return BASE_PATH + username;
        }
        return BASE_PATH + firstUser;
    }

    public static NsonObject getUserConfig(String username) {
        String configPath = CLIENT_USERS_FILE;
//        System.out.println("DEBUG: Checking configPath: " + configPath);
        File configFile = new File(configPath);
        NsonObject config = null;
        NsonArray users = null;

        if (configFile.exists()) {
            config = readConfig(configPath);
//            System.out.println("DEBUG: config from " + configPath + ": " + (config != null ? config.toString() : "null"));
            users = config.getArray("users");
//            System.out.println("DEBUG: users from " + configPath + ": " + (users != null ? users.toString() : "null"));
            if (users != null) {
                for (int i = 0; i < users.size(); i++) {
                    NsonObject user = users.getObject(i);
//                    System.out.println("DEBUG: checking user in " + configPath + ": " + user.getString("username"));
                    if (user.getString("username").equals(username)) {
//                        System.out.println("DEBUG: found user in " + configPath + ": " + user.toString());
                        return user;
                    }
                }
            }
        }

        File baseDir = new File(BASE_PATH);
//        System.out.println("DEBUG: Scanning directories in " + BASE_PATH);
        File[] adminDirs = baseDir.listFiles(File::isDirectory);
        if (adminDirs == null) {
//            System.out.println("DEBUG: No directories found in " + BASE_PATH);
            return null;
        }
//        System.out.println("DEBUG: Found directories: " + adminDirs.length);

        for (File adminDir : adminDirs) {
            configPath = adminDir.getPath() + "/users.nson";
//            System.out.println("DEBUG: Checking configPath: " + configPath);
            if (new File(configPath).exists()) {
                config = readConfig(configPath);
//                System.out.println("DEBUG: config from " + configPath + ": " + (config != null ? config.toString() : "null"));
                users = config.getArray("users");
//                System.out.println("DEBUG: users from " + configPath + ": " + (users != null ? users.toString() : "null"));
                if (users != null) {
                    for (int i = 0; i < users.size(); i++) {
                        NsonObject user = users.getObject(i);
//                        System.out.println("DEBUG: checking user in " + configPath + ": " + user.getString("username"));
                        if (user.getString("username").equals(username)) {
//                            System.out.println("DEBUG: found user in " + configPath + ": " + user.toString());
                            return user;
                        }
                    }
                }
            } else {
//                System.out.println("DEBUG: File does not exist: " + configPath);
            }
        }

//        System.out.println("DEBUG: user not found for username: " + username);
        return null;
    }

    public static boolean userExists(String username) {
        String configPath = CLIENT_USERS_FILE;
        File configFile = new File(configPath);
        NsonObject config = null;
        NsonArray users = null;

        if (configFile.exists()) {
            config = readConfig(configPath);
            users = config.getArray("users");
            if (users != null) {
                for (int i = 0; i < users.size(); i++) {
                    NsonObject user = users.getObject(i);
                    if (user.getString("username").equals(username)) {
                        return true;
                    }
                }
            }
        }

        File baseDir = new File(BASE_PATH);
        File[] adminDirs = baseDir.listFiles(File::isDirectory);
        if (adminDirs != null) {
            for (File adminDir : adminDirs) {
                configPath = adminDir.getPath() + "/users.nson";
                if (new File(configPath).exists()) {
                    config = readConfig(configPath);
                    users = config.getArray("users");
                    if (users != null) {
                        for (int i = 0; i < users.size(); i++) {
                            NsonObject user = users.getObject(i);
                            if (user.getString("username").equals(username)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    public static void updateUserConfig(String username, NsonObject updateData) {
        String configPath = CLIENT_USERS_FILE;
        NsonObject config = readConfig(configPath);
        NsonArray users = config.getArray("users");
        if (users == null) {
            System.out.println("\u001B[31m>> No users found to update.\u001B[0m");
            return;
        }

        boolean updated = false;
        for (int i = 0; i < users.size(); i++) {
            NsonObject user = users.getObject(i);
            if (user.getString("username").equals(username)) {
                for (String key : updateData.keySet()) {
                    user.put(key, updateData.get(key));
                }
                if (updateData.containsKey("username")) {
                    String newUsername = updateData.getString("username");
                    updateChildrenReferences(config, username, newUsername);
                }
                updated = true;
                break;
            }
        }

        if (updated) {
            saveConfig(config, configPath);
        } else {
            System.out.println("\u001B[31m>> User '" + username + "' not found.\u001B[0m");
        }
    }

    private static void updateChildrenReferences(NsonObject config, String oldUsername, String newUsername) {
        NsonArray users = config.getArray("users");
        if (users != null) {
            for (int i = 0; i < users.size(); i++) {
                NsonObject user = users.getObject(i);
                String parent = user.getString("parent");
                if (parent != null && parent.equals(oldUsername)) {
                    user.put("parent", newUsername);
                }
                NsonArray children = user.getArray("children");
                if (children != null) {
                    for (int j = 0; j < children.size(); j++) {
                        if (children.get(j).equals(oldUsername)) {
                            children.set(j, newUsername);
                        }
                    }
                }
            }
        }
    }

    public static NsonObject readUsersData() {
        String currentUser = NJSQL.getCurrentUser();
        if (currentUser == null) {
            return null;
        }
        String configPath = CLIENT_USERS_FILE;
        return readConfig(configPath);
    }

    private static NsonObject readConfig(String configPath) {
        File file = new File(configPath);
        if (!file.exists()) {
//            System.out.println("DEBUG: File does not exist: " + configPath);
            return new NsonObject().put("users", new NsonArray());
        }
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
//            System.out.println("DEBUG: Successfully read file: " + configPath);
            return NSON.parse(content).getAsObject();
        } catch (IOException e) {
//            System.err.println("DEBUG: Error reading config " + configPath + ": " + e.getMessage());
            return new NsonObject().put("users", new NsonArray());
        }
    }

    private static void saveConfig(NsonObject config, String configPath) {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(configPath), StandardCharsets.UTF_8)) {
            writer.write(toJsonString(config, 4));
//            System.out.println("DEBUG: Successfully saved config to: " + configPath);
        } catch (IOException e) {
//            System.err.println("DEBUG: Error saving config to " + configPath + ": " + e.getMessage());
        }
    }

    private static String toJsonString(NsonObject obj, int indent) {
        StringBuilder sb = new StringBuilder();
        String indentStr = " ".repeat(indent);
        sb.append("{\n");

        boolean first = true;
        for (String key : obj.keySet()) {
            if (!first) sb.append(",\n");
            sb.append(indentStr).append("\"").append(key).append("\": ");
            Object value = obj.get(key);
            if (value instanceof NsonObject) {
                sb.append(toJsonString((NsonObject) value, indent + 4));
            } else if (value instanceof NsonArray) {
                sb.append(toJsonArrayString((NsonArray) value, indent + 4));
            } else if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else if (value == null) {
                sb.append("null");
            } else {
                sb.append(value);
            }
            first = false;
        }

        sb.append("\n").append(" ".repeat(indent - 4)).append("}");
        return sb.toString(); // Sửa từ sb.toString thành sb.toString()
    }

    private static String toJsonArrayString(NsonArray arr, int indent) {
        if (arr.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        String indentStr = " ".repeat(indent);
        sb.append("[\n");

        for (int i = 0; i < arr.size(); i++) {
            Object value = arr.get(i);
            sb.append(indentStr);
            if (value instanceof NsonObject) {
                sb.append(toJsonString((NsonObject) value, indent + 4));
            } else if (value instanceof NsonArray) {
                sb.append(toJsonArrayString((NsonArray) value, indent + 4));
            } else if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else if (value == null) {
                sb.append("null");
            } else {
                sb.append(value);
            }
            if (i < arr.size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append(" ".repeat(indent - 4)).append("]");
        return sb.toString(); // Sửa từ sb.toString thành sb.toString()
    }
}