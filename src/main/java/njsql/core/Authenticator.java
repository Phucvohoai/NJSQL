package njsql.core;

import org.json.JSONObject;
import njsql.models.User;
import njsql.nson.NsonObject;

public class Authenticator {

    public static boolean authenticate(String username, String password) {
        // Gọi trực tiếp UserManager.checkLogin để kiểm tra đăng nhập
        NsonObject result = UserManager.checkLogin(username, password);
        return result.getBoolean("success");
    }

    // Không cần hashPassword ở đây nữa vì UserManager đã xử lý
    // Nếu cần giữ hàm này cho mục đích khác, có thể để lại và đồng bộ với UserManager
    private static String hashPassword(String password) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return password; // Nếu lỗi, trả về nguyên bản (không nên xảy ra)
        }
    }

    // Giữ lại loadUser nếu cần, nhưng hiện tại không liên quan đến đăng nhập
    public static User loadUser(String username) {
        try {
            String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("users/" + username + ".json")));
            JSONObject obj = new JSONObject(content);
            return new User(
                    obj.getString("user"),
                    obj.getString("password"),
                    obj.getString("host"),
                    obj.getInt("port")
            );
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}