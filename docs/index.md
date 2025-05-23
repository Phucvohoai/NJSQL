# 📂 NJSQL - Not Just SQL

**NJSQL** is a lightweight, custom-built database engine written in **Java** that uses **JSON files** to store data, while allowing developers to interact with the database using **SQL-like commands**.

Ideal for developers who want to simulate SQL operations without setting up heavy systems like MySQL or SQLite.

---

## 🚀 Features

* SQL-style query support (SELECT, INSERT, UPDATE, DELETE, etc.)
* Stores all data in human-readable JSON files
* User management system with **permissions**
* CLI interface with command history
* Fully offline and runs on `localhost:2801`

---

## 📦 Installation

### Requirements

* Java JDK 8 or higher
* Windows / Linux / macOS

### How to Run

```bash
# On Windows
run.bat

# On Linux / macOS
chmod +x run.sh
./run.sh
```

> Once started, you'll enter the **NJSQL CLI**:

```
>> Welcome to NJSQL (Network JSON SQL)
>> Type /help for commands. Type /exit to quit.
NJSQL>
```

---

## 🔐 User System

### 👤 Creating a User

```bash
/cre user
```

* The first user created is an **admin**
* Subsequent users are **child users** under the admin

### 🔑 Logging In

```bash
/login
```

### 🚪 Logging Out

```bash
/logout
```

---

## 🛡️ Permissions

Only admin users can assign/revoke permissions.

### Grant Permission

```bash
/grant
```

### Revoke Permission

```bash
/revoke
```

### Supported Permissions

| Permission    | Description            |
| ------------- | ---------------------- |
| CREATE\_DB    | Create databases       |
| CREATE\_TABLE | Create tables          |
| INSERT        | Insert data            |
| DELETE        | Delete data            |
| UPDATE        | Update data            |
| SELECT        | Query data             |
| ALTER\_TABLE  | Modify table structure |

---

## 🧐 SQL Mode

Enter SQL mode:

```bash
/sql
```

SQL Mode Commands:

| Command | Description               |
| ------- | ------------------------- |
| `/r`    | Run the entered SQL query |
| `/c`    | Clear the current query   |

---

## 📜 Supported SQL Syntax

| SQL Command                    | Required Permission |
| ------------------------------ | ------------------- |
| CREATE DATABASE <name>         | CREATE\_DB          |
| USE \<database\_name>          | -                   |
| CREATE TABLE ...               | CREATE\_TABLE       |
| INSERT INTO ... VALUES (...)   | INSERT              |
| DELETE FROM ... WHERE ...      | DELETE              |
| UPDATE ... SET ... WHERE ...   | UPDATE              |
| SELECT \* FROM ...             | SELECT              |
| DESCRIBE \<table\_name>        | SELECT              |
| ALTER TABLE ... ADD COLUMN ... | ALTER\_TABLE        |
...
---

## 📂 Data Storage Structure

NJSQL stores data under `njsql_data/<user_name>/`:

```
njsql_data/
└── <user_name>/
    ├── users.nson
    └── <database>/
       └──  <table_name>.nson
```

* `.json`: Contains actual table data
* `_schema.json`: Defines the table's structure and constraints

---

## 🧪 Usage Example

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

## 📝 Notes

* Users **cannot perform actions** they don't have permission for.
* Direct modification of JSON files is **not recommended**.
* JOIN, GROUP BY, and complex WHERE conditions are not yet supported.

---

## 🛠️ Future Development

* Web-based GUI Admin Panel
* Indexing and performance improvements
* Support for JOINs and subqueries
* RESTful API layer for web integration

---

## 💡 Why NJSQL?

NJSQL is designed to **simplify database interactions** in educational projects, prototyping, or systems that need lightweight storage without external dependencies.

---

## 👨‍💻 Author

Built by **PhucVoHoai**

---

## 📜 License

MIT License
