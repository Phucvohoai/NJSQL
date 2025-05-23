
# 🛠️ Khắc phục sự cố NJSQL

Tài liệu này giúp bạn giải quyết các lỗi thường gặp khi sử dụng NJSQL.

---

## ❌ Không thể đăng nhập

**Triệu chứng:**  
Khi đăng nhập, bạn thấy thông báo `Invalid username or password`.

**Nguyên nhân khả dĩ:**
- Tài khoản chưa được tạo.
- Sai tên đăng nhập hoặc mật khẩu.

**Cách khắc phục:**
- Kiểm tra kỹ thông tin đăng nhập.
- Dùng `/cre user` để tạo người dùng mới.
- Đảm bảo bạn đang sử dụng đúng tài khoản admin hoặc user đã được phân quyền.

---

## 🔐 Không có quyền thực thi lệnh

**Triệu chứng:**  
Thông báo lỗi: `Permission denied for this operation`.

**Nguyên nhân khả dĩ:**
- Tài khoản hiện tại không có quyền cần thiết.

**Cách khắc phục:**
- Đăng nhập lại bằng tài khoản admin.
- Dùng `/grant` để cấp quyền cho user.
- Kiểm tra quyền với `/listperm` hoặc `/myperms`.

---

## 💾 Lỗi khi nén hoặc giải nén database

**Triệu chứng:**  
Lỗi khi dùng `/zipdb` hoặc `/unzipdb`.

**Nguyên nhân khả dĩ:**
- File đích không tồn tại.
- Đường dẫn sai hoặc không có quyền ghi.

**Cách khắc phục:**
- Đảm bảo thư mục database tồn tại.
- Kiểm tra quyền ghi/chạy file.
- Thử với một database nhỏ trước.

---

## 📂 Dữ liệu không hiển thị đúng sau khi chèn

**Triệu chứng:**  
INSERT thành công nhưng SELECT không thấy dữ liệu.

**Nguyên nhân khả dĩ:**
- Sai định dạng dữ liệu.
- Lỗi khi ghi JSON.

**Cách khắc phục:**
- Kiểm tra lại câu lệnh `INSERT`.
- Mở file `.json` kiểm tra nội dung.
- Dùng `/r` trong SQL mode để chạy lại câu lệnh.

---

## 🧠 SQL không chạy được trong SQL Mode

**Triệu chứng:**  
Câu lệnh không thực thi, không có phản hồi.

**Nguyên nhân khả dĩ:**
- Chưa nhập `/r` để thực thi.
- Câu lệnh SQL bị lỗi cú pháp.

**Cách khắc phục:**
- Dùng `/r` để chạy.
- Dùng `/c` để xóa câu lệnh cũ.
- Kiểm tra cú pháp SQL đã nhập.

---

## 🔄 Lỗi khôi phục database (`/unzipdb`)

**Triệu chứng:**  
Không khôi phục được database từ file `.njsql`.

**Nguyên nhân khả dĩ:**
- File `.njsql` bị hỏng.
- Không có quyền ghi.

**Cách khắc phục:**
- Thử file `.njsql` khác.
- Đảm bảo có quyền ghi vào thư mục `njsql_data`.

---

## 🧪 Mẹo debug

- Luôn bắt đầu với `/login` trước khi thực thi các lệnh SQL.
- Dùng `/myperms` để xem quyền hiện tại của bạn.
- Đặt breakpoint hoặc in log khi debug Java nếu bạn phát triển thêm.

---

## 📫 Cần hỗ trợ thêm?

Liên hệ với tác giả qua GitHub hoặc mở issue nếu bạn gặp lỗi không nằm trong danh sách trên.
