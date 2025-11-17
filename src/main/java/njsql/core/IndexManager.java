package njsql.core;

import njsql.nson.NsonObject;
import njsql.nson.NsonArray;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import java.util.ArrayList;

public class IndexManager {

    // Lưu chỉ mục: cột -> giá trị -> danh sách vị trí hàng
    // Dùng TreeMap để sắp xếp key tự nhiên, tránh lỗi ép kiểu
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

            // Dùng TreeMap để sắp xếp giá trị theo natural order
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
}
