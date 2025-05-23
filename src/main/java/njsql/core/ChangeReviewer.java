package njsql.core;

import njsql.models.User;
import njsql.utils.FileUtils;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ChangeReviewer {
    private static final DateTimeFormatter formatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneId.of("UTC"));

    public static String approve(String username, User approver) {
        if (!approver.isAdmin()) {
            return "Only admins can approve changes.";
        }

        String ownerUsername = findDatabaseOwner(username);
        if (ownerUsername == null) {
            return "No database found where " + username + " is authorized to push.";
        }

        String dbName = getDatabaseNameForUser(username, ownerUsername);
        if (dbName == null) {
            return "No database found for user " + username + ".";
        }
        String commitsDir = UserManager.getRootDirectory(ownerUsername) + "/" + dbName + "/.commits";
        File dir = new File(commitsDir);
        if (!dir.exists()) {
            return "No commits found for user " + username;
        }

        File[] commitFiles = dir.listFiles((d, name) -> name.endsWith(".nson"));
        if (commitFiles == null || commitFiles.length == 0) {
            return "No commits found for user " + username;
        }

        File latestCommit = null;
        long latestTime = 0;
        for (File commitFile : commitFiles) {
            try {
                String content = FileUtils.readFileUtf8(commitFile.getPath());
                NsonObject commit = NsonObject.parse(content);
                if (commit.getString("username").equals(username)) {
                    long lastModified = commitFile.lastModified();
                    if (lastModified > latestTime) {
                        latestTime = lastModified;
                        latestCommit = commitFile;
                    }
                }
            } catch (IOException e) {
                System.err.println("Server> [ERROR] Failed to read commit file " + commitFile.getPath() + ": " + e.getMessage());
                continue;
            }
        }

        if (latestCommit == null) {
            return "No commits found for user " + username;
        }

        NsonObject commit;
        String commitDbName;
        String commitId;
        try {
            String commitContent = FileUtils.readFileUtf8(latestCommit.getPath());
            commit = NsonObject.parse(commitContent);
            commitDbName = commit.getString("db_name");
            commitId = commit.getString("id");
        } catch (IOException e) {
            return "Server> Failed to read commit: " + e.getMessage();
        }

        NsonObject files = commit.getObject("files");
        if (files == null) {
            return "Server> Invalid commit data: No files found.";
        }

        String dbPath = UserManager.getRootDirectory(ownerUsername) + "/" + commitDbName;
        CommitManager commitManager = new CommitManager(commitsDir);
        try {
            commitManager.applyChanges(dbPath, files);
        } catch (Exception e) {
            return "Server> Failed to apply changes: " + e.getMessage();
        }

        if (!latestCommit.delete()) {
            System.err.println("Server> [WARNING] Failed to delete commit file: " + latestCommit.getPath());
        }

        // Gửi thông báo cho user
        sendNotification(username, commitId, commitDbName, "approved");

        return "\u001B[33m- \u001B[32mApproved changes from \u001B[33m" + username + " \u001B[32mfor database '\u001B[33m" + commitDbName + "\u001B[32m'.\u001B[0m";
    }

    public static String reject(String username, User approver) {
        if (!approver.isAdmin()) {
            return "Only admins can reject changes.";
        }

        String ownerUsername = findDatabaseOwner(username);
        if (ownerUsername == null) {
            return "No database found where " + username + " is authorized to push.";
        }

        String dbName = getDatabaseNameForUser(username, ownerUsername);
        if (dbName == null) {
            return "No database found for user " + username + ".";
        }
        String commitsDir = UserManager.getRootDirectory(ownerUsername) + "/" + dbName + "/.commits";
        File dir = new File(commitsDir);
        if (!dir.exists()) {
            return "No commits found for user " + username;
        }

        File[] commitFiles = dir.listFiles((d, name) -> name.endsWith(".nson"));
        if (commitFiles == null || commitFiles.length == 0) {
            return "No commits found for user " + username;
        }

        File latestCommit = null;
        long latestTime = 0;
        for (File commitFile : commitFiles) {
            try {
                String content = FileUtils.readFileUtf8(commitFile.getPath());
                NsonObject commit = NsonObject.parse(content);
                if (commit.getString("username").equals(username)) {
                    long lastModified = commitFile.lastModified();
                    if (lastModified > latestTime) {
                        latestTime = lastModified;
                        latestCommit = commitFile;
                    }
                }
            } catch (IOException e) {
                System.err.println("Server> [ERROR] Failed to read commit file " + commitFile.getPath() + ": " + e.getMessage());
                continue;
            }
        }

        if (latestCommit == null) {
            return "No commits found for user " + username;
        }

        NsonObject commit;
        String commitDbName;
        String commitId;
        try {
            String commitContent = FileUtils.readFileUtf8(latestCommit.getPath());
            commit = NsonObject.parse(commitContent);
            commitDbName = commit.getString("db_name");
            commitId = commit.getString("id");
        } catch (IOException e) {
            return "Server> Failed to read commit: " + e.getMessage();
        }

        if (!latestCommit.delete()) {
            System.err.println("Server> [WARNING] Failed to delete commit file: " + latestCommit.getPath());
        }

        // Gửi thông báo cho user
        sendNotification(username, commitId, commitDbName, "rejected");

        return "\u001B[33m- \u001B[31mRejected changes from \u001B[33m" + username + " \u001B[31mfor database '\u001B[33m" + commitDbName + "\u001B[31m'.\u001B[0m";
    }

    private static void sendNotification(String username, String commitId, String dbName, String type) {
        String notificationDir = "njsql_data/" + username;
        String notificationFile = notificationDir + "/notifications.nson";
        try {
            FileUtils.createDirectory(notificationDir);
            NsonObject notifications;
            if (FileUtils.exists(notificationFile)) {
                notifications = NsonObject.parse(FileUtils.readFileUtf8(notificationFile));
            } else {
                notifications = new NsonObject().put("notifications", new NsonArray());
            }

            NsonArray notificationList = notifications.getArray("notifications");
            String notificationId = UUID.randomUUID().toString();
            String message = "Your commit " + commitId + " for database " + dbName + " has been " + type + ".";
            NsonObject notification = new NsonObject()
                    .put("id", notificationId)
                    .put("commit_id", commitId)
                    .put("db_name", dbName)
                    .put("type", type)
                    .put("message", message)
                    .put("timestamp", formatter.format(Instant.now()));
            notificationList.add(notification);

            FileUtils.writeFileUtf8(notificationFile, notifications.toString(2));
        } catch (Exception e) {
            System.err.println("Server> [ERROR] Failed to save notification for user " + username + ": " + e.getMessage());
        }
    }

    private static void displayChanges(NsonObject commit, String dbPath) {
        String username = commit.getString("username");
        String commitId = commit.getString("id");
        String message = commit.getString("message");
        String timestamp = commit.getString("timestamp");

        StringBuilder output = new StringBuilder();
        output.append("Server> [Server] Changes for database '").append(commit.getString("db_name"))
                .append("' by user '").append(username).append("':\n");
        output.append("Commit ID: ").append(commitId).append("\n");
        output.append("Commit message: ").append(message).append("\n");
        output.append("Timestamp: ").append(timestamp).append("\n");

        NsonObject files = commit.getObject("files");
        if (files == null || files.isEmpty()) {
            output.append("No changes found.\n");
            System.out.println(output.toString());
            return;
        }

        for (String tableName : files.keySet()) {
            output.append("Table: ").append(tableName.replace(".nson", "")).append("\n");
            output.append("Changes:\n");

            NsonObject tableDiff = files.getObject(tableName);
            if (tableDiff.getString("type") != null && tableDiff.getString("type").equals("deleted")) {
                output.append("Table deleted.\n");
                continue;
            }

            NsonArray changes = tableDiff.getArray("changes");
            if (changes == null || changes.isEmpty()) {
                output.append("No row changes.\n");
                continue;
            }

            List<String> columns = new ArrayList<>();
            try {
                NsonObject table = NsonObject.parse(FileUtils.readFileUtf8(dbPath + "/" + tableName));
                NsonObject types = table.getObject("_types");
                columns.addAll(types.keySet());
                Collections.sort(columns);
            } catch (Exception e) {
                output.append("[ERROR] Failed to read table schema for ").append(tableName)
                        .append(": ").append(e.getMessage()).append("\n");
                System.out.println("Server> " + output.toString());
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

        output.append("To approve, use: /approve ").append(username).append("\n");
        output.append("To reject, use: /reject ").append(username).append("\n");
        System.out.println(output.toString());
    }

    private static String findDatabaseOwner(String username) {
        NsonObject usersData = UserManager.readUsersData();
        if (usersData == null) {
            return null;
        }
        NsonArray users = usersData.getArray("users");
        for (int i = 0; i < users.size(); i++) {
            NsonObject user = users.getObject(i);
            NsonArray children = user.getArray("children");
            if (children != null && children.contains(username)) {
                return user.getString("username");
            }
        }
        return null;
    }

    private static String getDatabaseNameForUser(String username, String ownerUsername) {
        String ownerDir = UserManager.getRootDirectory(ownerUsername);
        File dir = new File(ownerDir);
        if (!dir.exists()) {
            return null;
        }
        File[] dbDirs = dir.listFiles(File::isDirectory);
        if (dbDirs == null) {
            return null;
        }
        for (File dbDir : dbDirs) {
            String commitsDir = dbDir.getPath() + "/.commits";
            File commits = new File(commitsDir);
            if (commits.exists()) {
                File[] commitFiles = commits.listFiles((d, name) -> name.endsWith(".nson"));
                if (commitFiles != null) {
                    for (File commitFile : commitFiles) {
                        try {
                            String content = FileUtils.readFileUtf8(commitFile.getPath());
                            NsonObject commit = NsonObject.parse(content);
                            if (commit.getString("username").equals(username)) {
                                return dbDir.getName();
                            }
                        } catch (IOException e) {
                            System.err.println("Server> [ERROR] Failed to read commit file " + commitFile.getPath() + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
        return null;
    }
}