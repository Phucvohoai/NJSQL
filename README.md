![MIT License](https://img.shields.io/badge/license-MIT-green)
![Java](https://img.shields.io/badge/language-Java-blue)
![High Performance](https://img.shields.io/badge/performance-3100%20QPS-orange)
![Contributions Welcome](https://img.shields.io/badge/contributions-welcome-brightgreen)

# NJSQL (Not Just SQL)

NJSQL is a **high-performance, standalone DBMS** written entirely in **Java**.  
It combines the **flexibility of NoSQL** (JSON-like storage) with **SQL-level data integrity**.

> NJSQL is **not an embedded library** like SQLite; it runs as a **standalone server**, ideal for modern development, testing, and prototyping.

---

## 🌟 Key Features

### 1. Hybrid Data Storage
- Stores data in `.nson` format (**Not Just JSON**).  
- Supports relational constraints: `PRIMARY_KEY`, `UNIQUE`, `NOT_NULL`, `FOREIGN_KEY` (with `ON_DELETE: CASCADE`).  
- SQL-like querying: `SELECT`, `INSERT`, `UPDATE`, `DELETE` with `WHERE`.

### 2. Git-inspired Versioning
- Clone, push, and manage data versions across multiple machines.  
- Supports **parallel development** and **conflict resolution**.  
- Merge updates seamlessly from concurrent users.

### 3. Performance & Scalability
- Optimized indexing: **B-Tree** / **Hash**.  
- Core engine benchmark: **~3100 QPS** on a 16GB RAM laptop.  
- Minimal HTTP API overhead; engine designed for max efficiency.

### 4. Security & Management
- User authentication and fine-grained permissions.  
- Command-line interface (CLI) for managing DBs, users, and queries.  

---

## 🚀 Roadmap

- Replace HTTP API with **gRPC** for full engine performance.  
- Optimize JSON parsing using **Jackson/Gson streaming**.  
- Implement caching for authentication and hot data.  
- Separate query processing from HTTP listener for stability.  
- Develop **BQL (Behavior Query Language)** for advanced queries.

---

## ⚡ Performance Benchmark

- **Environment:** MSI Laptop, AMD Ryzen 5, 16GB RAM  
- **HTTP API Test:** `wrk` on port 2801  
- **Core Engine Test:** `NJSQLBench.java` direct calls  

> Core engine is extremely fast; primary bottleneck is network & JSON parsing.

---

## 🛠️ Installation & Usage

### Requirements
- Java JDK 17+

### Installation
```bash
git clone https://github.com/Phucvohoai/NJSQL.git
```

Running the Server
Run the startup script (default: localhost:2801)

Use CLI to connect, manage, and query databases

### Author
- Võ Hoài Phúc (Phucvohoai) – Passionate about building core system technology and solving complex system challenges.

Your contributions make NJSQL even better!
