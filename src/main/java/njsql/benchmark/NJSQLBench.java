package njsql.benchmark;

import njsql.core.SelectHandler;
import njsql.models.User;
import njsql.nson.NsonObject;

import java.nio.file.Files;
import java.nio.file.Paths;

public class NJSQLBench {
    public static void main(String[] args) throws Exception {
        String dbName = "ecommerce_website";
        int runs = 10000;

        User user = new User("root", "admin123", "localhost", 2801, true);
        user.setCurrentDatabase(dbName);

        // Đường dẫn file nson đúng
        String tablePath = "njsql_data\\root\\" + dbName + "\\Users.nson";

        // Load dữ liệu (chỉ để chắc chắn file có tồn tại)
        String content = new String(Files.readAllBytes(Paths.get(tablePath)));
        NsonObject usersTable = NsonObject.parse(content);

        String sql = "SELECT * FROM Users WHERE points > 50";

        long totalTime = 0;
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;

        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            try {
                NsonObject result = SelectHandler.handleForAPI(sql, user);
            } catch (Exception e) {
                System.err.println("Error at run " + i + ": " + e.getMessage());
            }
            long end = System.nanoTime();
            long duration = end - start;

            totalTime += duration;
            minTime = Math.min(minTime, duration);
            maxTime = Math.max(maxTime, duration);

            if ((i + 1) % 1000 == 0) System.out.println("→ Run " + (i + 1));
        }

        double avgMs = totalTime / 1_000_000.0 / runs;
        double minMs = minTime / 1_000_000.0;
        double maxMs = maxTime / 1_000_000.0;
        double throughput = runs / (totalTime / 1_000_000_000.0);

        System.out.println("\n🏁 Benchmark finished");
        System.out.println("Total queries: " + runs);
        System.out.printf("Avg per query: %.4f ms\n", avgMs);
        System.out.printf("Min query time: %.4f ms\n", minMs);
        System.out.printf("Max query time: %.4f ms\n", maxMs);
        System.out.printf("Throughput: %.2f queries/sec\n", throughput);
    }
}
