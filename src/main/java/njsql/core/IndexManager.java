package njsql.core;

import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap; // Import thêm cái này để dùng nếu cần

public class IndexManager {

    // --- PHẦN INSTANCE (Cũ của ní) ---
    // Lưu chỉ mục: cột -> giá trị -> danh sách vị trí hàng
    private Map<String, Map<Object, List<Integer>>> indexes;

    public IndexManager() {
        // Sắp xếp các cột theo tên (String natural order)
        indexes = new TreeMap<>();
    }

    // Tải chỉ mục từ dữ liệu .nson
    public void loadIndexes(String tablePath, NsonArray data, NsonArray indexCols) throws Exception {
        indexes.clear();
        for (Object colObj : indexCols) {
            String column = colObj.toString();
            Map<Object, List<Integer>> valueToRows = new TreeMap<>();

            for (int i = 0; i < data.size(); i++) {
                NsonObject row = data.getObject(i);
                Object value = row.get(column);
                if (value != null) {
                    valueToRows.computeIfAbsent(value, k -> new ArrayList<>()).add(i);
                }
            }
            indexes.put(column, valueToRows);
        }
    }

    // Lấy danh sách vị trí hàng cho một giá trị của cột
    public List<Integer> getRowIndexes(String column, Object value) {
        Map<Object, List<Integer>> valueToRows = indexes.get(column);
        if (valueToRows == null) return null;
        return valueToRows.getOrDefault(value, new ArrayList<>());
    }

    // Cập nhật chỉ mục khi thêm hàng mới
    public void updateIndex(String column, Object value, int rowIndex) {
        if (indexes.containsKey(column)) {
            indexes.get(column).computeIfAbsent(value, k -> new ArrayList<>()).add(rowIndex);
        }
    }

    // Xóa chỉ mục cho một hàng
    public void removeIndex(String column, Object value, int rowIndex) {
        if (indexes.containsKey(column)) {
            List<Integer> rows = indexes.get(column).get(value);
            if (rows != null) {
                rows.remove(Integer.valueOf(rowIndex));
                if (rows.isEmpty()) {
                    indexes.get(column).remove(value);
                }
            }
        }
    }

    public Map<String, Map<Object, List<Integer>>> getIndexes() {
        return indexes;
    }

    // --- [NEW] PHẦN STATIC ĐỂ CỨU BACKGROUND FLUSHER ---
    // Đây là cái hàm mà BackgroundFlusher đang tìm kiếm trong tuyệt vọng nè ní!
    
    public static void flushAllScheduled() {
        // Tạm thời in log để chứng minh là nó chạy
        // Sau này ní có thể gọi sang BTreeIndexManager.flushIndexesToDisk() ở đây nếu muốn.
        // System.out.println("DEBUG: [IndexManager] Flushing all scheduled indexes...");
        
        // Ví dụ logic tương lai:
        // njsql.indexing.BTreeIndexManager.flushAllFromMemory(); 
    }
}