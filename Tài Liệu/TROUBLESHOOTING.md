# 🛠️ NJSQL - Troubleshooting Guide

This document helps you diagnose and fix common issues when working with NJSQL.

---

## 🔐 Authentication Issues

### ❌ Problem: Cannot log in
**Cause:**
- Incorrect username/password
- User not created

**Solution:**
- Check if user exists in `njsql_data/<your_username>/users.json`
- If not, create user via:
  ```
  /cre user
  ```

---

## 🚫 Permission Errors

### ❌ Problem: "Permission denied" or command doesn’t work
**Cause:**
- User lacks required permission for that operation

**Solution:**
- Use `/myperms` to view your permissions
- Ask an admin to run:
  ```
  /grant
  ```
  to assign missing permissions

---

## 🧠 SQL Mode Issues

### ❌ Problem: SQL query not running
**Cause:**
- Did not enter SQL mode
- SQL syntax error

**Solution:**
- Make sure to enter SQL mode:
  ```
  /sql
  ```
- After writing SQL, run:
  ```
  /r
  ```
- Use `/c` to clear buffer if syntax is wrong

---

## 📦 ZIP/UNZIP Errors

### ❌ Problem: Cannot import/export database
**Cause:**
- File path incorrect or corrupted
- Lack of ZIP_DB / UNZIP_DB permission

**Solution:**
- Check the `.njsql` file exists
- Ensure the user has permission:
  ```
  /grant
  ```
  with `ZIP_DB` or `UNZIP_DB`

---

## 📂 File or Data Errors

### ❌ Problem: Data not showing or table missing
**Cause:**
- Manual changes to JSON files
- Schema mismatch

**Solution:**
- Do **not** edit `.json` files directly
- Use `DESCRIBE <table>` to check structure

---

## 🔄 Database Creation/Usage Issues

### ❌ Problem: Cannot use or create database
**Cause:**
- No permission `CREATE_DB`
- Wrong command syntax

**Solution:**
- Ensure permission granted
- Use proper syntax:
  ```sql
  CREATE DATABASE mydb;
  USE mydb;
  ```

---

## 🚫 Unsupported SQL Features

### ⚠️ Limitation: JOIN, GROUP BY, etc. not working
**Explanation:**
- NJSQL currently does **not** support advanced SQL features like JOIN, GROUP BY, nested WHERE, subqueries...

**Workaround:**
- Manually merge data via multiple SELECTs

---

## 🔐 Security Considerations

### ⚠️ Issue: Password not encrypted
**Current State:**
- Passwords are stored in plain text (planned improvement)

**Advice:**
- Avoid sharing your `njsql_data` folder
- Use on local, trusted machines

---

## 💥 Crashes or Exceptions

### ❌ Problem: App crashes or shows stack trace
**Cause:**
- Missing file, malformed JSON, or unhandled exception

**Solution:**
- Check terminal logs
- Recreate the affected database/table if needed
- Submit issue with full log (coming soon GitHub repo!)

---

## 🧪 Debug Tips

- Use `/listperm <user>` to check other users' access
- Use `/myperms` to see your own permissions
- Always back up data using:
  ```
  /zipdb
  ```
- Restore using:
  ```
  /unzipdb
  ```

---

## 📌 Need Help?

Still stuck? Don’t worry!
- Talk to your friendly NJSQL developer (aka 🐑 Cừu)
- File coming soon at: `https://github.com/phucvo28/njsql`

---
