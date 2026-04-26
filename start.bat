@echo off
REM 启动 AuditLog 应用程序

echo 正在编译项目...
mvn clean compile

echo 正在启动应用程序...
mvn spring-boot:run