package njsql.benchmark;

import njsql.core.*;
import njsql.models.User;
import njsql.indexing.BTreeIndexManager;
import njsql.nson.NsonObject; // Giả sử bạn có class này

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class NJSQLBench {
    private static final String G = "\u001B[32m";
    private static final String R = "\u001B[31m";
    private static final String Y = "\u001B[33m";
    private static final String P = "\u001B[35m";
    private static final String C = "\u001B[36m";
    private static final String W = "\u001B[97m";
    private static final String BOLD = "\u001B[1m";
    private static final String RESET = "\u001B[0m";

    private static final AtomicInteger uid = new AtomicInteger(40000);

    public static void main(String[] args) throws Exception {
        clear();
        banner();

        String db = "ecommerce_website";
        User user = new User("root", "admin123", "localhost", 2801, true);
        user.setCurrentDatabase(db);
        String dbPath = "njsql_data/root/" + db; // Đường dẫn thực tế của bạn

        // 1. Tạo Index nếu chưa có
        try {
            // Tạo index rỗng (sẽ tự load vào RAM)
            BTreeIndexManager.createBTreeIndex(dbPath, "Users", "points", "idx_users_points");
        } catch (Exception ignored) {
            // Nếu có rồi thì LOAD vào RAM thủ công
            BTreeIndexManager.loadIndexToMemory(dbPath, "Users");
        }

        // 2. Tắt log Realtime để dồn sức cho Insert
        RealtimeTableManager.addListener(db + ".Users", change -> {
            // Silent mode: Không làm gì cả để đo Max Speed
        });

        status("NJSQL ENGINE READY - RAM CACHE ACTIVATED", "G");
        Thread.sleep(1000);

        int target = 5000; // Test 5000 records
        attackStart(target);

        long start = System.nanoTime();

        // 3. Vòng lặp Insert
        for (int i = 1; i <= target; i++) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int currentId = uid.incrementAndGet();
            int points = random.nextInt(100000);

            String username = "user_" + currentId;
            String email = username + "@test.com";

            String sql = "INSERT INTO Users (username, email, points, role, country) VALUES ('"
                    + username + "', '" + email + "', " + points + ", 'user', 'VN')";

            // Gọi lệnh Insert API
            InsertHandler.handleForAPI(sql, user);

            // Log tiến độ mỗi 10%
            if (i % (target/10) == 0 || i == target) {
                long now = System.nanoTime();
                double ms = (now - start) / 1_000_000.0;
                double ops = (i * 1000.0) / ms;
                System.out.print("\r" + P + "[BENCH] Inserted: " + G + i + "/" + target + W +
                        " | Speed: " + Y + String.format("%,.0f", ops) + " ops/s" + RESET);
            }
        }
        System.out.println();

        long end = System.nanoTime();

        // 4. QUAN TRỌNG: Lưu Index từ RAM xuống đĩa sau khi xong việc
        System.out.println(Y + ">>> Flushing indexes to disk... <<<" + RESET);
        BTreeIndexManager.flushIndexesToDisk(dbPath, "Users");

        double sec = (end - start) / 1_000_000_000.0;
        victory(target, sec, (sec*1000)/target, (int)(target/sec));
    }

    // --- Các hàm UI (Giữ nguyên như cũ) ---
    private static void clear() { System.out.print("\033[H\033[2J"); System.out.flush(); }

    private static void banner() {
        System.out.println(P + BOLD + "╔════════════════════════════════════════════════════╗\n" +
                "║             NJSQL - HIGH PERFORMANCE               ║\n" +
                "╚════════════════════════════════════════════════════╝\n" + RESET);
    }

    private static void status(String msg, String col) {
        System.out.println((col.equals("G")?G:Y) + ">>> " + msg + " <<<" + RESET);
    }

    private static void attackStart(int t) {
        System.out.println(R + "Starting Injection of " + t + " records..." + RESET);
    }

    private static void victory(int total, double sec, double avg, int tps) {
        System.out.println(G + BOLD + "\nDONE! Total: " + total +
                " | Time: " + String.format("%.3f", sec) + "s" +
                " | TPS: " + tps + " ops/s" + RESET);
    }
}