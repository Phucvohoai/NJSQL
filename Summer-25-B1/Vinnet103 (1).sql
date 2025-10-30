-- Tạo cơ sở dữ liệu
CREATE DATABASE Vinnet103;
GO
USE Vinnet103;
GO

-- USERS: Lưu thông tin người dùng
CREATE TABLE Users (
    UserID INT PRIMARY KEY IDENTITY,
    FullName NVARCHAR(100) NULL, -- Đã sửa thành NULL
    Email NVARCHAR(100) UNIQUE NOT NULL,
    Password NVARCHAR(255) NOT NULL,
    Phone NVARCHAR(20),
    Address NVARCHAR(255),
    ProfilePicture NVARCHAR(255),
    Role NVARCHAR(20) DEFAULT 'User' CHECK (Role IN ('User', 'Admin')),
    CreatedAt DATETIME DEFAULT GETDATE(),
    Status BIT DEFAULT 1,
    IsSeller BIT DEFAULT 0
);
CREATE INDEX IX_Users_Email ON Users(Email); -- Chỉ mục để tìm kiếm email nhanh hơn

-- CATEGORIES: Lưu danh mục sản phẩm
CREATE TABLE Categories (
    CategoryID INT PRIMARY KEY IDENTITY,
    Name NVARCHAR(100) NOT NULL UNIQUE
);

-- PRODUCTS: Lưu chi tiết sản phẩm
CREATE TABLE Products (
    ProductID INT PRIMARY KEY IDENTITY,
    UserID INT NOT NULL,
    CategoryID INT,
    Title NVARCHAR(255) NOT NULL,
    Description NVARCHAR(MAX),
    Price DECIMAL(18,2) NOT NULL CHECK (Price >= 0),
    Quantity INT NOT NULL DEFAULT 1 CHECK (Quantity >= 0),
    IsAvailable BIT DEFAULT 1,
    ImageUrl NVARCHAR(255),
    CreatedAt DATETIME DEFAULT GETDATE(),
    FOREIGN KEY (UserID) REFERENCES Users(UserID), -- Không dùng ON DELETE
    FOREIGN KEY (CategoryID) REFERENCES Categories(CategoryID) -- Không dùng ON DELETE
);
CREATE INDEX IX_Products_CategoryID ON Products(CategoryID); -- Chỉ mục để lọc theo danh mục

-- CARTS: Lưu các mục trong giỏ hàng
CREATE TABLE Carts (
    CartID INT PRIMARY KEY IDENTITY,
    UserID INT NOT NULL,
    ProductID INT NOT NULL, -- Giữ NOT NULL để đảm bảo tính toàn vẹn
    Quantity INT NOT NULL CHECK (Quantity > 0),
    FOREIGN KEY (UserID) REFERENCES Users(UserID), -- Không dùng ON DELETE
    FOREIGN KEY (ProductID) REFERENCES Products(ProductID) -- Không dùng ON DELETE
);
CREATE INDEX IX_Carts_UserID ON Carts(UserID); -- Chỉ mục để truy vấn giỏ hàng theo người dùng

-- ORDERS: Lưu thông tin đơn hàng
CREATE TABLE Orders (
    OrderID INT PRIMARY KEY IDENTITY,
    UserID INT NOT NULL,
    TotalAmount DECIMAL(18,2) NOT NULL CHECK (TotalAmount >= 0),
    OrderDate DATETIME DEFAULT GETDATE(),
    Status NVARCHAR(50) DEFAULT 'Pending' CHECK (Status IN ('Pending', 'Shipping', 'Completed', 'Cancelled')),
    FOREIGN KEY (UserID) REFERENCES Users(UserID) -- Không dùng ON DELETE
);
CREATE INDEX IX_Orders_UserID ON Orders(UserID); -- Chỉ mục để truy vấn lịch sử đơn hàng

-- ORDER DETAILS: Lưu chi tiết đơn hàng
CREATE TABLE OrderDetails (
    OrderDetailID INT PRIMARY KEY IDENTITY,
    OrderID INT NOT NULL,
    ProductID INT NOT NULL, -- Giữ NOT NULL
    Quantity INT NOT NULL CHECK (Quantity > 0),
    Price DECIMAL(18,2) NOT NULL CHECK (Price >= 0),
    FOREIGN KEY (OrderID) REFERENCES Orders(OrderID), -- Không dùng ON DELETE
    FOREIGN KEY (ProductID) REFERENCES Products(ProductID) -- Không dùng ON DELETE
);

-- REVIEWS: Lưu đánh giá sản phẩm
CREATE TABLE Reviews (
    ReviewID INT PRIMARY KEY IDENTITY,
    ProductID INT NOT NULL,
    UserID INT NOT NULL, -- Giữ NOT NULL
    Rating INT NOT NULL CHECK (Rating BETWEEN 1 AND 5),
    Comment NVARCHAR(MAX),
    CreatedAt DATETIME DEFAULT GETDATE(),
    FOREIGN KEY (ProductID) REFERENCES Products(ProductID), -- Không dùng ON DELETE
    FOREIGN KEY (UserID) REFERENCES Users(UserID) -- Không dùng ON DELETE
);
CREATE INDEX IX_Reviews_ProductID ON Reviews(ProductID); -- Chỉ mục để truy vấn đánh giá theo sản phẩm

-- MESSAGES: Lưu tin nhắn người dùng
CREATE TABLE Messages (
    MessageID INT PRIMARY KEY IDENTITY,
    SenderID INT NOT NULL,
    ReceiverID INT NOT NULL,
    Content NVARCHAR(MAX) NOT NULL,
    SentAt DATETIME DEFAULT GETDATE(),
    FOREIGN KEY (SenderID) REFERENCES Users(UserID), -- Không dùng ON DELETE
    FOREIGN KEY (ReceiverID) REFERENCES Users(UserID) -- Không dùng ON DELETE
);

-- REPORTS: Lưu báo cáo sản phẩm
CREATE TABLE Reports (
    ReportID INT PRIMARY KEY IDENTITY,
    ReporterID INT NOT NULL,
    ProductID INT NOT NULL,
    Reason NVARCHAR(255) NOT NULL,
    ReportDate DATETIME DEFAULT GETDATE(),
    FOREIGN KEY (ReporterID) REFERENCES Users(UserID), -- Không dùng ON DELETE
    FOREIGN KEY (ProductID) REFERENCES Products(ProductID) -- Không dùng ON DELETE
);

-- FAVORITES: Lưu sản phẩm yêu thích
CREATE TABLE Favorites (
    FavoriteID INT PRIMARY KEY IDENTITY,
    UserID INT NOT NULL,
    ProductID INT NOT NULL,
    FOREIGN KEY (UserID) REFERENCES Users(UserID), -- Không dùng ON DELETE
    FOREIGN KEY (ProductID) REFERENCES Products(ProductID), -- Không dùng ON DELETE
    CONSTRAINT UQ_User_Product_Favorite UNIQUE (UserID, ProductID)
);
CREATE INDEX IX_Favorites_UserID ON Favorites(UserID); -- Chỉ mục để truy vấn sản phẩm yêu thích

-- NOTIFICATIONS: Lưu thông báo người dùng
CREATE TABLE Notifications (
    NotificationID INT PRIMARY KEY IDENTITY,
    UserID INT NOT NULL,
    Message NVARCHAR(255) NOT NULL,
    IsRead BIT DEFAULT 0,
    CreatedAt DATETIME DEFAULT GETDATE(),
    FOREIGN KEY (UserID) REFERENCES Users(UserID) -- Không dùng ON DELETE
);

-- USER BEHAVIOR: Theo dõi hành vi người dùng
CREATE TABLE UserBehavior (
    BehaviorID INT PRIMARY KEY IDENTITY,
    UserID INT NOT NULL,
    ProductID INT, -- Cho phép NULL vì hành vi có thể không liên quan đến sản phẩm
    BehaviorType NVARCHAR(50) NOT NULL CHECK (BehaviorType IN ('View', 'Click', 'Search', 'Like', 'Share')),
    BehaviorData NVARCHAR(255),
    BehaviorDate DATETIME DEFAULT GETDATE(),
    FOREIGN KEY (UserID) REFERENCES Users(UserID), -- Không dùng ON DELETE
    FOREIGN KEY (ProductID) REFERENCES Products(ProductID) -- Không dùng ON DELETE
);
CREATE INDEX IX_UserBehavior_UserID ON UserBehavior(UserID); -- Chỉ mục để theo dõi hành vi người dùng

-- Chèn dữ liệu vào Users
INSERT INTO Users (FullName, Email, Password, Phone, Address, ProfilePicture, Role)
VALUES 
(N'Nguyễn Văn A', 'a@example.com', 'pass123', '0123456789', N'Hà Nội', 'a.jpg', 'User'),
(N'Trần Thị B', 'b@example.com', 'pass456', '0987654321', N'Đà Nẵng', 'b.jpg', 'User'),
(N'Lê Văn C', 'c@example.com', 'pass789', '0911223344', N'Hồ Chí Minh', 'c.jpg', 'User'),
(N'Phạm Thị D', 'd@example.com', 'passabc', '0933445566', N'Bình Dương', 'd.jpg', 'User'),
(N'Hoàng Văn E', 'e@example.com', 'passdef', '0944556677', N'Cần Thơ', 'e.jpg', 'Admin');

-- Chèn dữ liệu vào Categories
INSERT INTO Categories (Name)
VALUES 
(N'Điện thoại'),
(N'Máy tính'),
(N'Thiết bị gia dụng'),
(N'Quần áo'),
(N'Sách');

-- Chèn dữ liệu vào Products
INSERT INTO Products (UserID, CategoryID, Title, Description, Price, Quantity, IsAvailable, ImageUrl)
VALUES 
(1, 1, N'iPhone 13', N'Điện thoại Apple chính hãng', 20000000.00, 10, 1, 'iphone13.jpg'),
(2, 2, N'Laptop Dell', N'Máy tính văn phòng cấu hình cao', 15000000.00, 5, 1, 'dell.jpg'),
(3, 3, N'Nồi cơm điện', N'Nồi cơm điện Toshiba dung tích lớn', 1200000.00, 7, 1, 'noicom.jpg'),
(4, 4, N'Áo thun nam', N'Áo thun cotton thoáng mát', 250000.00, 20, 1, 'aothun.jpg'),
(5, 5, N'Sách học lập trình', N'Sách tự học lập trình từ cơ bản đến nâng cao', 180000.00, 30, 1, 'sachlaptrinh.jpg');

-- Chèn dữ liệu vào Carts
INSERT INTO Carts (UserID, ProductID, Quantity)
VALUES 
(1, 1, 2),
(2, 2, 1),
(3, 3, 3),
(4, 4, 1),
(5, 5, 4);

-- Chèn dữ liệu vào Orders
INSERT INTO Orders (UserID, TotalAmount, Status)
VALUES 
(1, 40000000.00, 'Completed'),
(2, 15000000.00, 'Pending'),
(3, 3600000.00, 'Shipping'),
(4, 250000.00, 'Completed'),
(5, 720000.00, 'Pending');

-- Chèn dữ liệu vào OrderDetails
INSERT INTO OrderDetails (OrderID, ProductID, Quantity, Price)
VALUES 
(1, 1, 2, 20000000.00),
(2, 2, 1, 15000000.00),
(3, 3, 3, 1200000.00),
(4, 4, 1, 250000.00),
(5, 5, 4, 180000.00);

-- Chèn dữ liệu vào Reviews
INSERT INTO Reviews (ProductID, UserID, Rating, Comment)
VALUES 
(1, 1, 5, N'Rất tốt, hài lòng'),
(2, 2, 4, N'Ổn trong tầm giá'),
(3, 3, 3, N'Sản phẩm bình thường'),
(4, 4, 5, N'Chất lượng vải tốt'),
(5, 5, 4, N'Nội dung sách hữu ích');

-- Chèn dữ liệu vào Messages
INSERT INTO Messages (SenderID, ReceiverID, Content)
VALUES 
(1, 2, N'Chào bạn, sản phẩm còn không?'),
(2, 1, N'Vẫn còn bạn nhé!'),
(3, 4, N'Mình muốn hỏi về thông tin sản phẩm.'),
(4, 3, N'Mình đã trả lời rồi nhé!'),
(5, 1, N'Giao hàng bao lâu vậy?');

-- Chèn dữ liệu vào Reports
INSERT INTO Reports (ReporterID, ProductID, Reason)
VALUES 
(2, 1, N'Sản phẩm không đúng mô tả'),
(3, 2, N'Giá cao bất thường'),
(4, 3, N'Sản phẩm lỗi'),
(5, 4, N'Hình ảnh không rõ ràng'),
(1, 5, N'Nội dung quảng cáo sai lệch');

-- Chèn dữ liệu vào Favorites
INSERT INTO Favorites (UserID, ProductID)
VALUES 
(1, 2),
(2, 3),
(3, 4),
(4, 5),
(5, 1);

-- Chèn dữ liệu vào Notifications
INSERT INTO Notifications (UserID, Message)
VALUES 
(1, N'Đơn hàng của bạn đã được giao'),
(2, N'Bạn vừa thêm sản phẩm mới'),
(3, N'Sản phẩm yêu thích có thay đổi giá'),
(4, N'Bạn có 1 tin nhắn mới'),
(5, N'Tài khoản của bạn đã được xác minh');

-- Chèn dữ liệu vào UserBehavior
INSERT INTO UserBehavior (UserID, ProductID, BehaviorType, BehaviorData)
VALUES 
(1, 1, 'View', N'Trên mobile'),
(2, 2, 'Click', N'Từ trang chủ'),
(3, 3, 'Search', N'Từ thanh tìm kiếm'),
(4, 4, 'Like', NULL),
(5, 5, 'Share', N'Facebook');

 select * from Users

 UPDATE Products
SET 
    ImageUrl = '/images/iphone13.jpg'
WHERE ProductID = 1;
 UPDATE Products
SET 
    ImageUrl = '/images/laptop-dell.jpg'
WHERE ProductID = 2;
 UPDATE Products
SET 
    ImageUrl = '/images/Noi-Com.png'
WHERE ProductID = 3;
 UPDATE Products
SET 
    ImageUrl = '/images/aothun.jpg'
WHERE ProductID = 4;
 UPDATE Products
SET 
    ImageUrl = '/images/SachLapTrinh.jpeg'
WHERE ProductID = 5;

ALTER TABLE Users
ADD BankAccount NVARCHAR(50);

-- Cập nhật tài khoản ngân hàng cho người bán (giả định UserID 1 là người bán)
UPDATE Users
SET BankAccount = '8888908478015'
WHERE UserID = 5;

-- Kiểm tra và thêm khóa ngoại ProductID trong OrderDetails nếu chưa có
IF NOT EXISTS (SELECT * FROM sys.foreign_keys WHERE name = 'FK_OrderDetails_Products')
BEGIN
    ALTER TABLE OrderDetails
    ADD CONSTRAINT FK_OrderDetails_Products FOREIGN KEY (ProductID) REFERENCES Products(ProductID);
END;

SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'Products';

ALTER TABLE Products
ADD UpdatedAt DATETIME;

-- Gán giá trị mặc định (tùy chọn, ví dụ: thời gian hiện tại cho các bản ghi hiện có)
UPDATE Products
SET UpdatedAt = GETDATE()
WHERE UpdatedAt IS NULL;

UPDATE Users SET BankAccount = '8888908478015' WHERE Email = 'luantqps39694@gmail.com';
SELECT UserID, Email, BankAccount FROM Users WHERE Email = 'luantqps39694@gmail.com';

ALTER TABLE Products ADD BankAccount VARCHAR(50);
ALTER TABLE Products ADD BankCode VARCHAR(10);