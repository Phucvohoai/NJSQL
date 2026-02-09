package njsql.core;

import java.util.*;
import java.util.concurrent.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import njsql.nson.NsonObject;

public class BackgroundFlusher {
    private static final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2);
    // Map lưu các bảng đang bị "bẩn" (có dữ liệu mới chưa lưu)
    private static final Map<String, NsonObject> dirtyTables = new ConcurrentHashMap<>();

    static {
        // Tự động Flush table mỗi 100ms (Cơ chế tự động)
        scheduler.scheduleAtFixedRate(() -> {
            flushDirtyTables();
        }, 100, 100, TimeUnit.MILLISECONDS); // Hardcode 100ms nếu chưa có config

        // Tự động Flush index mỗi 500ms
        scheduler.scheduleAtFixedRate(() -> {
            // Gọi hàm static bên IndexManager (như đã fix ở bước trước)
            try {
                IndexManager.flushAllScheduled();
            } catch (Exception e) {
                // Kệ nó nếu chưa implement xong, không crash app
            }
        }, 500, 500, TimeUnit.MILLISECONDS);
    }

    // --- HÀM MỚI: CÁI NÍ ĐANG THIẾU NÈ ---
    // Hàm này cho phép main gọi trực tiếp khi gõ /flush
    public static synchronized void forceFlushAll() {
        if (dirtyTables.isEmpty()) {
            System.out.println(">> [Flush] No dirty tables to save.");
            return;
        }
        System.out.println(">> [Flush] Saving " + dirtyTables.size() + " tables to disk...");
        flushDirtyTables();
        
        // Flush luôn cả Index nếu cần
        try {
            IndexManager.flushAllScheduled();
        } catch (Exception e) {
            System.out.println(">> [Flush] Warning: Index flush failed (ignoring).");
        }
    }

    // Logic cốt lõi: Lưu dữ liệu xuống đĩa
    private static void flushDirtyTables() {
        if (!dirtyTables.isEmpty()) {
            // Duyệt qua map và lưu từng bảng
            dirtyTables.forEach((key, data) -> {
                String path = getTablePath(key);
                writeQuietly(new File(path), data);
            });
            // Lưu xong thì xóa danh sách bẩn
            int count = dirtyTables.size();
            dirtyTables.clear();
            
            // Chỉ in log khi chạy tự động để debug (hoặc comment lại cho đỡ rối mắt)
            // System.out.println("DEBUG: Auto-flushed " + count + " tables.");
        }
    }

    public static void markDirty(String tableKey, NsonObject data) {
        if (data != null) {
            // If NsonObject has a copy constructor or clone method, use it. Otherwise, store the reference.
            // dirtyTables.put(tableKey, new NsonObject(data)); // Uncomment if copy constructor exists
            // dirtyTables.put(tableKey, data.clone()); // Uncomment if clone() exists
            dirtyTables.put(tableKey, data); // Store reference if deep copy is not available
        }
    }

    private static void writeQuietly(File file, NsonObject data) {
        try {
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            Files.writeString(file.toPath(), data.toString(2), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("Flush error for " + file.getName() + ": " + e.getMessage());
        }
    }

    private static String getTablePath(String tableKey) {
        // Quy ước key: database.table (Ví dụ: farmdb.animals)
        String[] parts = tableKey.split("\\.");
        if (parts.length < 2) return "njsql_data/root/unknown.nson";
        
        // Lưu vào thư mục của root (hoặc user hiện tại nếu ní muốn sửa logic sau này)
        return "njsql_data/root/" + parts[0] + "/" + parts[1] + ".nson";
    }
}