package njsql;

import java.util.Scanner;
import njsql.core.SQLMode;
import njsql.core.Authenticator;
import njsql.models.User;
import njsql.server.ShareServer;
import njsql.server.NJSQLServer;
import java.io.File;
import java.util.List;
import njsql.core.PermissionManager;
import java.util.ArrayList;
import njsql.core.UserManager;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import njsql.core.CloneHandler;
import njsql.core.PushHandler;
import njsql.core.ChangeReviewer;
import njsql.core.ConnectHandler;
import njsql.utils.FileUtils;

public class NJSQL {
    private static String currentUser = null;
    private static String currentPassword = null;
    private static boolean apiServerStarted = false; // Cho /s (NJSQLServer)
    private static boolean shareServerStarted = false; // Cho /share (ShareServer)

    public static String getCurrentUser() {
        return currentUser;
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println(">> Welcome to NJSQL (Not Just SQL)");
        System.out.println(">> Type \u001B[36m/help\u001B[0m for commands. Type \u001B[31m/exit\u001B[0m to quit.");

        while (true) {
            System.out.print("NJSQL> ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("/exit")) {
                System.out.println(">> Exiting NJSQL. See you soon <3");
                ShareServer.stop();
                // Giả sử NJSQLServer cũng có phương thức stop
                // NJSQLServer.stop(); // Nếu NJSQLServer có phương thức stop
                apiServerStarted = false;
                shareServerStarted = false;
                break;
            }

            if (input.toLowerCase().startsWith("/clone") || input.toLowerCase().startsWith("/push") ||
                    input.toLowerCase().startsWith("/approve") || input.toLowerCase().startsWith("/reject") ||
                    input.toLowerCase().startsWith("/connect") || input.toLowerCase().startsWith("/notifications")) {
                if (currentUser == null && !input.toLowerCase().startsWith("/connect")) {
                    System.out.println("\u001B[31m>> Please login first using /login.\u001B[0m");
                    continue;
                }
                try {
                    if (input.toLowerCase().startsWith("/clone")) {
                        System.out.print(">> Enter IP (press Enter for localhost): ");
                        String ip = scanner.nextLine().trim();
                        if (ip.isEmpty()) {
                            ip = "localhost";
                        }
                        System.out.print(">> Enter Database: ");
                        String dbName = scanner.nextLine().trim();
                        System.out.print(">> Enter username of the database owner: ");
                        String shareUsername = scanner.nextLine().trim();
                        String command = "/clone " + dbName;
                        User user = new User(currentUser, currentPassword, ip, 1201); // Cổng 1201 cho ShareServer
                        user.setCurrentDatabase(dbName);
                        System.out.println(CloneHandler.handle(command, user, ip, shareUsername));
                    } else if (input.toLowerCase().startsWith("/push")) {
                        System.out.print(">> Enter IP (press Enter for localhost): ");
                        String ip = scanner.nextLine().trim();
                        if (ip.isEmpty()) {
                            ip = "localhost";
                        }
                        System.out.print(">> Enter Database: ");
                        String dbName = scanner.nextLine().trim();
                        System.out.print(">> Enter username of the database owner: ");
                        String shareUsername = scanner.nextLine().trim();
                        System.out.print(">> Enter commit message: ");
                        String commitMessage = scanner.nextLine().trim();
                        String command = "/push " + dbName;
                        String clientDbPath = UserManager.getRootDirectory(currentUser) + "/" + dbName;
                        File dbDir = new File(clientDbPath);
                        if (!dbDir.exists() || !dbDir.isDirectory()) {
                            System.out.println(">> \u001B[31mDatabase not found at: \u001B[0m" + clientDbPath);
                        } else {
                            User user = new User(currentUser, currentPassword, ip, 1201); // Cổng 1201 cho ShareServer
                            user.setCurrentDatabase(dbName);
                            System.out.println(PushHandler.handle(command, user, commitMessage, clientDbPath, shareUsername));
                            displayNotifications();
                        }
                    } else if (input.toLowerCase().startsWith("/approve")) {
                        String[] parts = input.split("\\s+");
                        if (parts.length != 2) {
                            System.out.println("\u001B[31m>> Invalid command. Usage: /approve <username>\u001B[0m");
                        } else {
                            User user = new User(currentUser, currentPassword, "localhost", 1201, true);
                            System.out.println(ChangeReviewer.approve(parts[1], user));
                        }
                    } else if (input.toLowerCase().startsWith("/reject")) {
                        String[] parts = input.split("\\s+");
                        if (parts.length != 2) {
                            System.out.println("\u001B[31m>> Invalid command. Usage: /reject <username>\u001B[0m");
                        } else {
                            User user = new User(currentUser, currentPassword, "localhost", 1201, true);
                            System.out.println(ChangeReviewer.reject(parts[1], user));
                        }
                    } else if (input.toLowerCase().startsWith("/connect")) {
                        System.out.print(">> Enter IP (press Enter for localhost): ");
                        String ip = scanner.nextLine().trim();
                        if (ip.isEmpty()) {
                            ip = "localhost";
                        }
                        System.out.println(ConnectHandler.handle(ip));
                    } else if (input.toLowerCase().startsWith("/notifications")) {
                        displayNotifications();
                    }
                } catch (Exception e) {
                    System.out.println("\u001B[31m>> Error: \u001B[0m" + e.getMessage());
                }
                continue;
            }

            switch (input.toLowerCase()) {
                case "/help":
                    showHelp();
                    break;

                case "/s":
                    if (currentUser == null) {
                        System.out.println("\u001B[31m>> Please login first.\u001B[0m");
                    } else if (!apiServerStarted) {
                        try {
                            NJSQLServer.start((msg) -> System.out.println("Server> " + msg));
                            apiServerStarted = true;
                        } catch (Exception e) {
                            System.out.println("Server>\u001B[31m Error: \u001B[0m" + e.getMessage());
                        }
                    } else {
                        System.out.println("Server>\u001B[32m API Server is already running on port 2801.\u001B[0m");
                    }
                    break;

                case "/share":
                    if (currentUser == null) {
                        System.out.println("\u001B[31m>> Please login first.\u001B[0m");
                    } else if (!shareServerStarted) {
                        try {
                            ShareServer.start((msg) -> System.out.println("Server> " + msg));
                            shareServerStarted = true;
                        } catch (Exception e) {
                            System.out.println("Server>\u001B[31m Error: \u001B[0m" + e.getMessage());
                        }
                    } else {
                        System.out.println("Server>\u001B[32m Share Server is already running on port 1201.\u001B[0m");
                    }
                    break;

                case "/cre user":
                    handleCreateUser(scanner);
                    break;

                case "/login":
                    handleLogin(scanner);
                    break;

                case "/logout":
                    handleLogout();
                    break;

                case "/config":
                    if (currentUser == null) {
                        System.out.println("\u001B[31m>> Please login first.\u001B[0m");
                    } else {
                        handleConfigUser(scanner);
                    }
                    break;

                case "/sql":
                    if (currentUser == null) {
                        System.out.println("\u001B[33m<!> \u001B[0mPlease login first using /login.");
                    } else {
                        User user = new User(currentUser, currentPassword, "localhost", 1201);
                        SQLMode.start(user);
                    }
                    break;

                case "/zipdb":
                    if (currentUser == null) {
                        System.out.println(">> Please login first using /login.");
                    } else {
                        System.out.print(">> Enter database name to zip: ");
                        String dbName = scanner.nextLine().trim();
                        njsql.utils.DBZipper.zipDatabase(currentUser, dbName);
                    }
                    break;

                case "/unzipdb":
                    if (currentUser == null) {
                        System.out.println("\u001B[33m<!>\u001B[0m Please login first using /login.");
                    } else {
                        System.out.print(">> Enter database name to unzip to: ");
                        String dbName = scanner.nextLine().trim();
                        System.out.print(">> Enter path to .njsql file: ");
                        String zipPath = scanner.nextLine().trim();
                        if (zipPath.startsWith("\"") && zipPath.endsWith("\"")) {
                            zipPath = zipPath.substring(1, zipPath.length() - 1);
                        }
                        zipPath = zipPath.replace("\\", "/");
                        File zipFile = new File(zipPath);
                        if (!zipFile.exists()) {
                            System.out.println(">>\u001B[33m File not found at: \u001B[0m" + zipPath);
                        } else {
                            njsql.utils.DBZipper.unzipDatabase(currentUser, dbName, zipPath);
                        }
                    }
                    break;

                case "/show user":
                    if (currentUser == null) {
                        System.out.println("\u001B[31m>> Please login first.\u001B[0m");
                    } else {
                        handleShowUsers();
                    }
                    break;

                case "/grant":
                    if (currentUser == null) {
                        System.out.println(">> Please login first.");
                        break;
                    }

                    System.out.println("\u001B[36m>> Available permissions:\u001B[0m");
                    List<String> permList = new ArrayList<>(PermissionManager.getValidPermissions());
                    System.out.printf("%2d. %s - %s%n", 1, "ALL", "All permissions");
                    for (int i = 0; i < permList.size(); i++) {
                        if (!permList.get(i).equals("ALL")) {
                            System.out.printf("%2d. %s%n", i + 2, permList.get(i));
                        }
                    }

                    System.out.print(">> Select permissions (numbers/names, comma separated): ");
                    String permInput = scanner.nextLine().trim();

                    System.out.print(">> Grant to user: ");
                    String targetUsername = scanner.nextLine().trim();

                    String[] permissions = permInput.split("\\s*,\\s*");
                    for (String perm : permissions) {
                        String permission;
                        try {
                            int choice = Integer.parseInt(perm.trim());
                            if (choice == 1) {
                                permission = "ALL";
                            } else if (choice > 1 && choice <= permList.size() + 1) {
                                permission = permList.get(choice - 2);
                            } else {
                                System.out.println("\u001B[31m>> Invalid selection: " + choice + "\u001B[0m");
                                continue;
                            }
                        } catch (NumberFormatException e) {
                            permission = perm.trim().toUpperCase();
                        }

                        PermissionManager.grant(targetUsername, permission);
                    }
                    break;

                case "/revoke":
                    if (currentUser == null) {
                        System.out.println(">> Please login first.");
                    } else {
                        System.out.print(">> Revoke which permission? ");
                        String permission = scanner.nextLine().trim();
                        System.out.print(">> From which user? ");
                        String targetUser = scanner.nextLine().trim();
                        PermissionManager.revoke(targetUser, permission);
                    }
                    break;

                case "/listperm":
                    if (currentUser == null) {
                        System.out.println(">> Please login first.");
                    } else {
                        System.out.print(">> Enter username to check: ");
                        String targetUser = scanner.nextLine().trim();
                        PermissionManager.listPermissions(targetUser);
                    }
                    break;

                case "/myperms":
                    if (currentUser == null) {
                        System.out.println(">> Please login first.");
                    } else {
                        List<String> perms = new ArrayList<>(PermissionManager.getPermissions(currentUser));
                        if (perms.isEmpty()) {
                            System.out.println("\u001B[31m>>\u001B[0m You have no permissions. Please contact the owner for assistance.");
                        } else {
                            System.out.println("\u001B[36m>>\u001B[0m Your permissions: " + String.join(", ", perms));
                        }
                    }
                    break;

                default:
                    System.out.println(">> Unknown command. Try \u001B[33m/help.\u001B[0m");
                    break;
            }
        }
        scanner.close();
    }

    private static void handleCreateUser(Scanner scanner) {
        System.out.print(">> Enter new username: ");
        String username = scanner.nextLine().trim();
        System.out.print(">> Enter password: ");
        String password = scanner.nextLine().trim();

        String parent = null;
        if (currentUser != null) {
            NsonObject currentConfig = UserManager.getUserConfig(currentUser);
            if (currentConfig != null && currentConfig.getBoolean("isAdmin")) {
                parent = currentUser;
            }
        }

        UserManager.createUser(username, password, parent);
    }

    private static void handleLogin(Scanner scanner) {
        System.out.print(">> Username: ");
        String username = scanner.nextLine().trim();
        System.out.print(">> Password: ");
        String password = scanner.nextLine().trim();

//        System.out.println("DEBUG: Current user before login: " + NJSQL.getCurrentUser());
        boolean authenticated = Authenticator.authenticate(username, password);
        if (authenticated) {
            currentUser = username;
            currentPassword = password;
            NsonObject userConfig = UserManager.getUserConfig(username);
            boolean isAdmin = userConfig != null && userConfig.getBoolean("isAdmin");
            System.out.println("-\u001B[32m Login successful\u001B[0m. Role: \u001B[33m" + (isAdmin ? "Admin" : "User") + "\u001B[0m Welcome \u001B[36m" + username + " \u001B[0m!!");
            displayNotifications();
        } else {
            System.out.println("\u001B[31m>> Login failed. Wrong credentials.\u001B[0m");
        }
    }
    private static void handleLogout() {
        if (currentUser == null) {
            System.out.println("\u001B[31m>> You are not logged in.\u001B[0m");
        } else {
            String loggedOutUser = currentUser;
            currentUser = null;
            currentPassword = null;
            System.out.println("\u001B[32m>> Logout successful.!\u001B[0m Goodbye " + loggedOutUser + "");
        }
    }

    private static void handleConfigUser(Scanner scanner) {
        System.out.println("\u001B[36m>> User Configuration\u001B[0m");
        System.out.println(">> Leave blank to keep current value");

        NsonObject userConfig = UserManager.getUserConfig(currentUser);
        if (userConfig == null) {
            System.out.println("\u001B[31m>> Error: Could not load user configuration.\u001B[0m");
            return;
        }

        System.out.print(">> Enter new username [" + currentUser + "]: ");
        String newUsername = scanner.nextLine().trim();

        System.out.print(">> Enter new password [hidden]: ");
        String newPassword = scanner.nextLine().trim();

        if (newUsername.isEmpty() && newPassword.isEmpty()) {
            System.out.println("\u001B[33m>> No changes made.\u001B[0m");
            return;
        }

        System.out.print("\u001B[33m<!> Confirm changes? (Y/N): \u001B[0m");
        String confirm = scanner.nextLine().trim();

        if (confirm.equalsIgnoreCase("Y")) {
            NsonObject updateData = new NsonObject();

            if (!newUsername.isEmpty()) {
                if (UserManager.userExists(newUsername)) {
                    System.out.println("\u001B[31m>> Username already exists.\u001B[0m");
                    return;
                }
                updateData.put("username", newUsername);
            }

            if (!newPassword.isEmpty()) {
                updateData.put("password", UserManager.hashPassword(newPassword));
                currentPassword = newPassword;
            }

            UserManager.updateUserConfig(currentUser, updateData);

            if (!newUsername.isEmpty()) {
                currentUser = newUsername;
            }

            System.out.println(">>\u001B[32m User configuration updated successfully!\u001B[0m");
        } else {
            System.out.println("\u001B[33m>> Changes discarded.\u001B[0m");
        }
    }

    private static void handleShowUsers() {
        NsonObject usersData = UserManager.readUsersData();
        if (usersData == null) {
            System.out.println("\u001B[31m>> No users data found.\u001B[0m");
            return;
        }

        NsonArray users = usersData.getArray("users");
        if (users == null || users.size() == 0) {
            System.out.println("\u001B[33m>> No users exist in the system.\u001B[0m");
            return;
        }

        System.out.println("\n>>\u001B[36m LIST OF ALL USERS \u001B[0m<<");
        System.out.println("==========================================");

        for (int i = 0; i < users.size(); i++) {
            NsonObject user = users.getObject(i);
            String username = user.getString("username");
            boolean isAdmin = user.getBoolean("isAdmin");
            String parent = user.getString("parent") != null ? user.getString("parent") : "None";
            NsonArray children = user.getArray("children");
            NsonArray permissions = user.getArray("permissions");

            System.out.println("\u001B[33mUsername:\u001B[0m " + username);
            System.out.println("  \u001B[33mType:\u001B[0m " + (isAdmin ? "\u001B[32mADMIN\u001B[0m" : "\u001B[34mUSER\u001B[0m"));
            System.out.println("  \u001B[33mParent:\u001B[0m " + parent);

            if (children != null && children.size() > 0) {
                String childrenStr = String.join(", ", children.stream().map(Object::toString).toArray(String[]::new));
                System.out.println("  \u001B[33mChildren:\u001B[0m " + childrenStr);
            }

            if (permissions != null && permissions.size() > 0) {
                String permsStr = String.join(", ", permissions.stream().map(Object::toString).toArray(String[]::new));
                System.out.println("  \u001B[33mPermissions:\u001B[0m " + permsStr);
            } else if (!isAdmin) {
                System.out.println("  \u001B[33mPermissions:\u001B[0m None");
            }

            System.out.println("------------------------------------------");
        }
    }

    private static void displayNotifications() {
        if (currentUser == null) {
            System.out.println("\u001B[31m>> Please login to view notifications.\u001B[0m");
            return;
        }

        String notificationFile = "njsql_data/" + currentUser + "/notifications.nson";
        try {
            if (!FileUtils.exists(notificationFile)) {
                System.out.println(">> No notifications available.");
                return;
            }

            NsonObject notifications = NsonObject.parse(FileUtils.readFileUtf8(notificationFile));
            NsonArray notificationList = notifications.getArray("notifications");
            if (notificationList == null || notificationList.isEmpty()) {
                System.out.println(">> No notifications available.");
                return;
            }

            System.out.println("\u001B[36m>> Notifications:\u001B[0m");
            for (int i = 0; i < notificationList.size(); i++) {
                NsonObject notification = notificationList.getObject(i);
                System.out.println("- " + notification.getString("message") + " [" + notification.getString("timestamp") + "]");
            }

            // Xóa thông báo sau khi hiển thị
            notifications.put("notifications", new NsonArray());
            FileUtils.writeFileUtf8(notificationFile, notifications.toString(2));
        } catch (Exception e) {
            System.out.println("\u001B[31m>> Error reading notifications: \u001B[0m" + e.getMessage());
        }
    }

    private static void showHelp() {
        System.out.println("\n======================= \u001B[36mNJSQL COMMANDS\u001B[0m =======================");
        System.out.println("  \u001B[33m/cre user\u001B[0m            > Create a new user");
        System.out.println("  \u001B[33m/login\u001B[0m               > Connect / login to NJSQL");
        System.out.println("  \u001B[33m/logout\u001B[0m              > Logout from current session");
        System.out.println("  \u001B[33m/show user\u001B[0m           > Show all users and their attributes");
        System.out.println("  \u001B[33m/config\u001B[0m              > Change your user information (username/password)");
        System.out.println("  \u001B[33m/sql\u001B[0m                 > Enter SQL Mode to start writing queries");
        System.out.println("  \u001B[33m/s\u001B[0m                   > Start the NJSQL API Server (port 2801)");
        System.out.println("  \u001B[33m/share\u001B[0m               > Start the sharing server for cloning and pushing (port 1201)");
        System.out.println("  \u001B[33m/connect\u001B[0m             > Connect to a server to retrieve users.nson");
        System.out.println("  \u001B[33m/exit\u001B[0m                > Exit NJSQL application");
        System.out.println("  \u001B[33m/zipdb\u001B[0m               > Export database to .njsql file");
        System.out.println("  \u001B[33m/unzipdb\u001B[0m             > Import .njsql file to current user");
        System.out.println("  \u001B[33m/grant\u001B[0m               > Grant permission to a user");
        System.out.println("  \u001B[33m/revoke\u001B[0m              > Revoke permission from a user");
        System.out.println("  \u001B[33m/listperm\u001B[0m            > List permissions of a user");
        System.out.println("  \u001B[33m/myperms\u001B[0m             > Show your own permissions");
        System.out.println("  \u001B[33m/clone\u001B[0m               > Clone a database from a server to njsql_data/<user>/<db_name>");
        System.out.println("  \u001B[33m/push\u001B[0m                > Push local changes to a server for approval");
        System.out.println("  \u001B[33m/approve <username>\u001B[0m  > Approve changes from a user (admin only)");
        System.out.println("  \u001B[33m/reject <username>\u001B[0m   > Reject changes from a user (admin only)");
        System.out.println("  \u001B[33m/notifications\u001B[0m       > Show all notifications from server");
        System.out.println("  \u001B[33m/rt\u001B[0m                  > Enter Realtime Mode");
        System.out.println("  \u001B[33m/flush\u001B[0m               > Flush pending data to disk");

        System.out.println("\n=========== \u001B[36mSQL MODE COMMANDS\u001B[0m ====================");
        System.out.println("  \u001B[32m/end\u001B[0m            > Exit SQL Mode");
        System.out.println("  \u001B[32m/r\u001B[0m              > Run the SQL queries you typed");
        System.out.println("  \u001B[32m/c\u001B[0m              > Clear current SQL buffer (if you made mistakes)");

        System.out.println("\n>>> In SQL Mode, you can run queries like:");
        System.out.println("    - create database mydb;");
        System.out.println("    - use mydb;");
        System.out.println("    - create table users (id INT, name TEXT);");
        System.out.println("    - insert into users values (1, 'NJQSL');");
        System.out.println("    - select * from users;");
        System.out.println("    - update users set name = 'NJSQL' where id = 1;");
        System.out.println("    - delete from users where id = 1;");
        System.out.println("==============================================================\n");
    }
}