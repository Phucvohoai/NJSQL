# 🗂️ NJSQL - Not Just SQL

**NJSQL** là một hệ cơ sở dữ liệu nhẹ, được xây dựng thủ công bằng **Java**, sử dụng **file JSON** để lưu trữ dữ liệu, đồng thời cho phép lập trình viên tương tác với dữ liệu bằng cú pháp **giống SQL**.

Phù hợp cho các developer muốn mô phỏng hoạt động SQL mà không cần cài đặt những hệ thống nặng như MySQL hay SQLite.

---

## 🚀 Tính năng

- Hỗ trợ truy vấn theo phong cách SQL (SELECT, INSERT, UPDATE, DELETE, v.v.)
- Dữ liệu được lưu dưới dạng file JSON dễ đọc
- Hệ thống người dùng có phân quyền
- Giao diện dòng lệnh (CLI) có lịch sử lệnh
- Hoạt động hoàn toàn offline tại `localhost:2801`

---

## 📦 Cài đặt

### Yêu cầu
- Java JDK 8 trở lên
- Windows / Linux / macOS

### Cách chạy
```bash
# Trên Windows
run.bat

# Trên Linux / macOS
chmod +x run.sh
./run.sh
```

> Khi khởi chạy, bạn sẽ vào giao diện **NJSQL CLI**:
```
>> Welcome to NJSQL (Network JSON SQL)
>> Type /help for commands. Type /exit to quit.
NJSQL>
```

---

## 🔐 Hệ thống người dùng

### 👤 Tạo người dùng
```bash
/cre user
```
- Người dùng đầu tiên được tạo là **admin**
- Các người dùng tiếp theo là **user con** trực thuộc admin

### 🔑 Đăng nhập
```bash
/login
```

### 🚪 Đăng xuất
```bash
/logout
```

---

## 🛡️ Phân quyền

Chỉ người dùng admin mới có quyền cấp / thu hồi quyền.

### Cấp quyền
```bash
/grant
```

### Thu hồi quyền
```bash
/revoke
```

### Danh sách quyền hỗ trợ

| Quyền         | Mô tả                          |
|---------------|--------------------------------|
| CREATE_DB     | Tạo cơ sở dữ liệu              |
| CREATE_TABLE  | Tạo bảng                       |
| INSERT        | Thêm dữ liệu                   |
| DELETE        | Xoá dữ liệu                    |
| UPDATE        | Cập nhật dữ liệu               |
| SELECT        | Truy vấn dữ liệu               |
| ALTER_TABLE   | Thay đổi cấu trúc bảng         |

---

## 🧠 Chế độ SQL

Vào chế độ SQL:
```bash
/sql
```

Các lệnh trong SQL Mode:

| Lệnh   | Mô tả                            |
|--------|----------------------------------|
| `/r`   | Chạy truy vấn SQL đã nhập        |
| `/c`   | Xoá truy vấn hiện tại            |

---

## 📘 Cú pháp SQL được hỗ trợ

| Lệnh SQL                            | Quyền yêu cầu       |
|------------------------------------|---------------------|
| CREATE DATABASE <tên>              | CREATE_DB           |
| USE <tên_database>                 | -                   |
| CREATE TABLE ...                   | CREATE_TABLE        |
| INSERT INTO ... VALUES (...)       | INSERT              |
| DELETE FROM ... WHERE ...          | DELETE              |
| UPDATE ... SET ... WHERE ...       | UPDATE              |
| SELECT * FROM ...                  | SELECT              |
| DESCRIBE <tên_bảng>                | SELECT              |
| ALTER TABLE ... ADD COLUMN ...     | ALTER_TABLE         |

---

## 📂 Cấu trúc lưu trữ dữ liệu

NJSQL lưu dữ liệu trong thư mục `njsql_data/<tên_user>/`:

```
njsql_data/
└── <tên_user>/
    ├── users.json
    └── <database>/
        ├── <tên_bảng>.json
        └── <tên_bảng>_schema.json
```

- `.json`: Chứa dữ liệu thực tế của bảng
- `_schema.json`: Mô tả cấu trúc bảng và ràng buộc

---

## 🧪 Ví dụ sử dụng

```bash
/cre user
# → Username: admin, Password: admin123

/login
# → Username: admin, Password: admin123

/cre user
# → Username: dev, Password: dev123

/grant
# → Permission: CREATE_DB
# → User: dev

/logout
/login
# → Username: dev, Password: dev123

/sql
CREATE DATABASE test;
USE test;
CREATE TABLE users (id INT PRIMARY KEY, name TEXT);
INSERT INTO users VALUES (1, "Alice");
SELECT * FROM users;
```

---

## 📝 Ghi chú

- Người dùng **không thể thực hiện thao tác** nếu không có quyền phù hợp
- **Không nên chỉnh sửa trực tiếp** các file JSON
- Hiện tại chưa hỗ trợ JOIN, GROUP BY và các điều kiện WHERE phức tạp

---

## 🛠️ Phát triển trong tương lai

- Giao diện quản trị (GUI) trên nền web
- Tăng tốc truy vấn bằng chỉ mục (indexing)
- Hỗ trợ JOIN và subqueries
- Tích hợp RESTful API để kết nối web app

---

## 💡 Vì sao chọn NJSQL?

NJSQL được tạo ra để **đơn giản hóa việc tương tác với dữ liệu** trong các dự án học thuật, prototype nhanh, hoặc hệ thống cần lưu trữ nhẹ, không phụ thuộc nền tảng ngoài.

---

## 👨‍💻 Tác giả

Được viết bằng ❤️ bởi **Sheep so sweet (a.k.a. Cừu)**

---

## 📜 Giấy phép

Giấy phép MIT (MIT License)