package njsql.core;

import njsql.NJSQL;
import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import java.util.*;

public class PermissionManager {
    private static final Set<String> VALID_PERMISSIONS = Set.of(
            "CREATE_DB", "DROP_DB", "BACKUP", "RESTORE", "ZIP_DB", "UNZIP_DB", "GRANT", "REVOKE",
            "SELECT", "INSERT", "UPDATE", "DELETE", "CREATE_TABLE", "DROP_TABLE", "ALTER_TABLE", "ALL"
    );

    public static boolean isValidPermission(String permission) {
        return VALID_PERMISSIONS.contains(permission.toUpperCase());
    }

    public static void grant(String targetUser, String permission) {
        // Kiểm tra đăng nhập
        String currentUser = NJSQL.getCurrentUser();
        if (currentUser == null) {
            System.out.println("\u001B[31m>> Please login first.\u001B[0m");
            return;
        }

        // Nếu permission là "list" hoặc empty thì hiển thị danh sách
        if (permission == null || permission.isEmpty() || permission.equalsIgnoreCase("list")) {
            System.out.println("\u001B[36m>> Available permissions:\u001B[0m");
            System.out.println("  ALL - Grant all permissions below");
            for (String perm : VALID_PERMISSIONS) {
                if (!perm.equals("ALL")) {
                    System.out.println("  " + perm);
                }
            }
            System.out.println("\u001B[36m>> Usage: /grant <permission> <username>\u001B[0m");
            return;
        }

        // Lấy thông tin user hiện tại
        NsonObject currentUserConfig = UserManager.getUserConfig(currentUser);
        if (currentUserConfig == null || !currentUserConfig.getBoolean("isAdmin")) {
            System.out.println("\u001B[31m>> Only admins can grant permissions.\u001B[0m");
            return;
        }

        // Kiểm tra target user
        NsonObject targetUserConfig = UserManager.getUserConfig(targetUser);
        if (targetUserConfig == null) {
            System.out.println("\u001B[31m>> User '" + targetUser + "' does not exist.\u001B[0m");
            return;
        }

        // Không cho phép cấp quyền cho admin
        if (targetUserConfig.getBoolean("isAdmin")) {
            System.out.println("\u001B[31m>> Cannot grant permissions to ADMIN user.\u001B[0m");
            return;
        }

        // Kiểm tra target user phải là con của current user
        String parent = targetUserConfig.getString("parent");
        if (parent == null || !parent.equals(currentUser)) {
            System.out.println("\u001B[31m>> You can only grant permissions to your child users.\u001B[0m");
            return;
        }

        // Xử lý quyền ALL
        if (permission.equalsIgnoreCase("ALL")) {
            NsonArray allPermissions = new NsonArray();
            for (String perm : VALID_PERMISSIONS) {
                if (!perm.equals("ALL")) {
                    allPermissions.add(perm);
                }
            }
            targetUserConfig.put("permissions", allPermissions);
            UserManager.updateUserConfig(targetUser, targetUserConfig);
            System.out.println(">>\u001B[32m Granted ALL permissions to \u001B[0m" + targetUser);
            return;
        }

        // Kiểm tra quyền hợp lệ
        if (!isValidPermission(permission)) {
            System.out.println("\u001B[31m>> Invalid permission: " + permission + ". Use: " + VALID_PERMISSIONS + "\u001B[0m");
            return;
        }

        // Thêm quyền
        NsonArray permissions = targetUserConfig.getArray("permissions");
        if (permissions == null) {
            permissions = new NsonArray();
            targetUserConfig.put("permissions", permissions);
        }

        // Kiểm tra quyền đã tồn tại chưa
        for (int i = 0; i < permissions.size(); i++) {
            if (permissions.get(i).toString().equalsIgnoreCase(permission)) {
                System.out.println("\u001B[33m>> User already has permission: " + permission.toUpperCase() + "\u001B[0m");
                return;
            }
        }

        permissions.add(permission.toUpperCase());
        UserManager.updateUserConfig(targetUser, targetUserConfig);
        System.out.println(">>\u001B[32m Granted " + permission.toUpperCase() + " to \u001B[0m" + targetUser);
    }

    public static void revoke(String targetUser, String permission) {
        // Kiểm tra đăng nhập
        String currentUser = NJSQL.getCurrentUser();
        if (currentUser == null) {
            System.out.println("\u001B[31m>> Please login first.\u001B[0m");
            return;
        }

        // Kiểm tra admin
        NsonObject currentUserConfig = UserManager.getUserConfig(currentUser);
        if (currentUserConfig == null || !currentUserConfig.getBoolean("isAdmin")) {
            System.out.println("\u001B[31m>> Only admins can revoke permissions.\u001B[0m");
            return;
        }

        // Kiểm tra target user
        NsonObject targetUserConfig = UserManager.getUserConfig(targetUser);
        if (targetUserConfig == null) {
            System.out.println("\u001B[31m>> User '" + targetUser + "' does not exist.\u001B[0m");
            return;
        }

        // Không cho phép thu hồi quyền admin
        if (targetUserConfig.getBoolean("isAdmin")) {
            System.out.println("\u001B[31m>> Cannot revoke permissions from ADMIN user.\u001B[0m");
            return;
        }

        // Kiểm tra target user phải là con của current user
        String parent = targetUserConfig.getString("parent");
        if (parent == null || !parent.equals(currentUser)) {
            System.out.println("\u001B[31m>> You can only revoke permissions from your child users.\u001B[0m");
            return;
        }

        // Xử lý quyền ALL
        if (permission.equalsIgnoreCase("ALL")) {
            targetUserConfig.put("permissions", new NsonArray());
            UserManager.updateUserConfig(targetUser, targetUserConfig);
            System.out.println(">>\u001B[33m Revoked ALL permissions from " + targetUser + "\u001B[0m");
            return;
        }

        // Kiểm tra quyền hợp lệ
        if (!isValidPermission(permission)) {
            System.out.println("\u001B[31m>> Invalid permission: " + permission + ". Use: " + VALID_PERMISSIONS + "\u001B[0m");
            return;
        }

        // Xóa quyền
        NsonArray permissions = targetUserConfig.getArray("permissions");
        if (permissions != null) {
            for (int i = 0; i < permissions.size(); i++) {
                if (permissions.get(i).toString().equalsIgnoreCase(permission)) {
                    permissions.remove(i);
                    UserManager.updateUserConfig(targetUser, targetUserConfig);
                    System.out.println(">>\u001B[33m Revoked " + permission.toUpperCase() + " from " + targetUser + "\u001B[0m");
                    return;
                }
            }
        }
        System.out.println("\u001B[33m>> User does not have permission: " + permission.toUpperCase() + "\u001B[0m");
    }

    public static boolean hasPermission(String username, String permission) {
        NsonObject userConfig = UserManager.getUserConfig(username);
        if (userConfig == null) return false;

        // Admin có tất cả quyền
        if (userConfig.getBoolean("isAdmin")) {
            return true;
        }

        NsonArray permissions = userConfig.getArray("permissions");
        if (permissions == null) return false;

        // Kiểm tra quyền ALL
        for (int i = 0; i < permissions.size(); i++) {
            if (permissions.get(i).toString().equals("ALL")) {
                return true;
            }
        }

        // Kiểm tra quyền cụ thể
        for (int i = 0; i < permissions.size(); i++) {
            if (permissions.get(i).toString().equalsIgnoreCase(permission)) {
                return true;
            }
        }
        return false;
    }

    public static void listPermissions(String username) {
        if (!UserManager.userExists(username)) {
            System.out.println("\u001B[31m>> User '" + username + "' does not exist.\u001B[0m");
            return;
        }

        NsonObject userConfig = UserManager.getUserConfig(username);
        if (userConfig.getBoolean("isAdmin")) {
            System.out.println(">>\u001B[36m User " + username + " is ADMIN and has ALL permissions\u001B[0m");
            return;
        }

        NsonArray permissions = userConfig.getArray("permissions");
        Set<String> perms = new HashSet<>();

        if (permissions != null) {
            for (int i = 0; i < permissions.size(); i++) {
                perms.add(permissions.get(i).toString());
            }
        }

        System.out.println(">>\u001B[36m Permissions for " + username + ": " + perms + "\u001B[0m");
    }

    public static Set<String> getPermissions(String username) {
        NsonObject userConfig = UserManager.getUserConfig(username);
        NsonArray permissions = userConfig.getArray("permissions");
        Set<String> perms = new HashSet<>();

        if (permissions != null) {
            for (int i = 0; i < permissions.size(); i++) {
                perms.add(permissions.get(i).toString());
            }
        }

        return perms;
    }

    public static Set<String> getValidPermissions() {
        return VALID_PERMISSIONS;
    }
}