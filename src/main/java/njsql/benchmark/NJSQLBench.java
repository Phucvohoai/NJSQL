package njsql.benchmark;

import njsql.core.SelectHandler;
import njsql.models.User;

public class NJSQLBench {
    public static void main(String[] args) {
        String sql = "SELECT * FROM Users WHERE points > 50";
        String username = "root";
        String password = "admin123";
        int runs = 10000;

        // ✅ Tạo User (5 tham số, tham số cuối là boolean)
        User user = new User(username, password, "localhost", 2801, true);

        // 👇 Gắn database hiện tại (context DB)
        user.setCurrentDatabase("ecommerce_website");

        long start = System.nanoTime();

        for (int i = 0; i < runs; i++) {
            try {
                // ✅ Gọi SelectHandler như bình thường
                String result = SelectHandler.handle(sql, user);

                if (i % 1000 == 0) System.out.println("→ Run " + i);
            } catch (Exception e) {
                System.err.println("Error at run " + i + ": " + e.getMessage());
            }
        }

        long end = System.nanoTime();
        double totalMs = (end - start) / 1_000_000.0;

        System.out.println("🏁 Benchmark finished");
        System.out.println("Total queries: " + runs);
        System.out.println(String.format("Total time: %.2f ms", totalMs));
        System.out.println(String.format("Avg per query: %.4f ms", totalMs / runs));
        System.out.println(String.format("Throughput: %.2f queries/sec", runs / (totalMs / 1000)));
    }
}
