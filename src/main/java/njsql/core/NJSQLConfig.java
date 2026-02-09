package njsql.core;

public class NJSQLConfig {
    // CHÌA KHÓA VÀNG - BẬT LÊN ĐỂ TĂNG TỐC 15 LẦN!
    public static final boolean WAL_ENABLED = true;
    public static final String FLUSH_MODE = "BATCH";        // ASYNC | BATCH | SYNC
    public static final long BATCH_FLUSH_INTERVAL_MS = 100; // 100ms = tối ưu nhất
    public static final int BATCH_MAX_SIZE = 500;           // hoặc dùng thời gian
    public static final boolean INDEX_DELAYED_FLUSH = true;
    public static final long INDEX_FLUSH_INTERVAL_MS = 500;
}