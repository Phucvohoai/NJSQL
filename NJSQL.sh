#!/bin/bash

# Đặt tiêu đề và làm sạch màn hình
clear
echo "Starting NJSQL..."

# Xác định thư mục hiện tại
CURRENT_DIR="$(dirname "$(readlink -f "$0")")"

# Kiểm tra các file jar trong thư mục libs
if [ ! -f "$CURRENT_DIR/libs/json-20230227.jar" ]; then
    echo "ERROR: json-20230227.jar not found in libs!"
    exit 1
fi

if [ ! -f "$CURRENT_DIR/libs/jackson-core-2.18.3.jar" ]; then
    echo "ERROR: jackson-core-2.18.3.jar not found in libs!"
    exit 1
fi

if [ ! -f "$CURRENT_DIR/libs/jackson-annotations-2.18.3.jar" ]; then
    echo "ERROR: jackson-annotations-2.18.3.jar not found in libs!"
    exit 1
fi

if [ ! -f "$CURRENT_DIR/libs/jackson-databind-2.18.3.jar" ]; then
    echo "ERROR: jackson-databind-2.18.3.jar not found in libs!"
    exit 1
fi

# Khai báo classpath với thư viện trong libs + class trong bin
CLASSPATH="$CURRENT_DIR/bin:$CURRENT_DIR/libs/json-20230227.jar:$CURRENT_DIR/libs/jackson-core-2.18.3.jar:$CURRENT_DIR/libs/jackson-annotations-2.18.3.jar:$CURRENT_DIR/libs/jackson-databind-2.18.3.jar"

# Chạy chương trình từ thư mục bin
java -cp "$CLASSPATH" njsql.NJSQL

# Kiểm tra lỗi khi chạy chương trình
if [ $? -ne 0 ]; then
    echo "Failed to start NJSQL! Please check your setup."
    exit 1
fi
