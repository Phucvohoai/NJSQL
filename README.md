![MIT License](https://img.shields.io/badge/license-MIT-green)
![Contributions welcome](https://img.shields.io/badge/contributions-welcome-brightgreen)
![Java](https://img.shields.io/badge/language-Java-blue)

# NJSQL (Not Just SQL)

NJSQL is a **high-performance, standalone database management system (DBMS)** written **from scratch in Java**.  
It features a **hybrid architecture**, combining the flexibility of NoSQL (JSON storage) with the relational integrity of SQL.

Unlike embedded libraries like SQLite, NJSQL runs as a **standalone server**, ideal for modern development, testing, and prototyping environments.

---

## 🌟 Highlights

- **Not Just SQL Architecture**: Flexible `.nson` (Not Just JSON) data storage with strict relational constraints and SQL-like querying.  
- **Git-inspired Data Management**: Supports clone, push, merge, and conflict resolution for parallel development across multiple machines.  
- **High-performance Core**: Engine benchmarks reach ~3100 QPS on a 16GB RAM laptop.

---

## 🚀 Performance Benchmark

- **Environment:** MSI Laptop, AMD Ryzen 5, 16GB RAM  
- **Test 1:** HTTP API via port 2801 (`wrk`)  
- **Test 2:** Core Engine direct calls (`NJSQLBench.java`)  

**Result:** Core engine is extremely fast. HTTP API latency is the main bottleneck due to network overhead and JSON parsing.

---

## ✨ Key Features

### 1. Git-like Data Versioning
- Version control with **clone and push** commands.  
- Parallel development with multiple machines.  
- Automatic **merge** and **conflict handling**.

### 2. SQL Core & Engine
- **Indexing:** B-Tree / Hash indexes for ~3100 QPS query performance.  
- **Constraints:** PRIMARY_KEY, UNIQUE, NOT_NULL, FOREIGN_KEY (with ON_DELETE: CASCADE).  
- **CRUD Queries:** Full support for SELECT, INSERT, UPDATE, DELETE with WHERE clauses.

### 3. Security & Management
- **User & Permission Management:** Fine-grained grant/revoke.  
- **Command-line Interface (CLI):** Intuitive and easy-to-use.

---

## 🛣️ Roadmap

- Optimize HTTP API with **gRPC** to unlock full Core Engine performance.  
- Accelerate JSON parsing using streaming libraries (Jackson/Gson).  
- Implement caching for user authentication to reduce latency.  
- Separate query thread pool from HTTP listener for stability.  
- Develop **BQL (Behavior Query Language)** for advanced querying.

---

## 🛠️ Installation & Usage

### Requirements
- Java JDK 17+

### Installation
```bash
git clone https://github.com/Phucvohoai/NJSQL.git
