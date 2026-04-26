package com.github.oldbee;

import com.github.oldbee.auditlog.annotation.EnableAuditLog;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 主应用程序入口
 */
@SpringBootApplication
@EnableAsync // 添加此注解
@EnableAuditLog(
        basePackages = {"com.github.oldbee"},
        enable = "true",
        logHeaders = "true",
        logRequest = "true",
        logResponse = "true",
        sensitiveHeaders = {"Authorization", "cookie"})
public class MainApplication {

    public static void main(String[] args) {
        SpringApplication.run(MainApplication.class, args);
    }
}
