package njsql.models;

public class User {
    private String username;
    private String password;
    private String host;
    private int port;
    private boolean isAdmin; // Thay role bằng isAdmin
    private String currentDatabase;

    // Constructor đầy đủ
    public User(String username, String password, String host, int port, boolean isAdmin) {
        this.username = username;
        this.password = password;
        this.host = host;
        this.port = port;
        this.isAdmin = isAdmin;
        this.currentDatabase = null; // Ban đầu chưa chọn DB
    }

    // Constructor tương thích ngược
    public User(String username, String password, String host, int port) {
        this(username, password, host, port, false); // Mặc định không phải admin
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getCurrentDatabase() {
        return currentDatabase;
    }

    public void setCurrentDatabase(String currentDatabase) {
        this.currentDatabase = currentDatabase;
    }

    public boolean isAdmin() {
        return isAdmin;
    }
}