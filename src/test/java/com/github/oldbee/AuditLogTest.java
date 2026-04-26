package com.github.oldbee;

import com.github.oldbee.auditlog.model.AuditLogConfig;
import com.github.oldbee.auditlog.model.MethodLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.Map;

@SpringBootTest
public class AuditLogTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    public void testContextLoads() {
        // 测试Spring上下文是否能正常加载
    }

    @Test
    public void testAuditLogConfig() {
        // 测试审计日志配置是否正确加载
        AuditLogConfig auditLogConfig = applicationContext.getBean(AuditLogConfig.class);
        System.out.println("AuditLog enabled: " + auditLogConfig.isEnable());
        System.out.println("Log headers: " + auditLogConfig.isLogHeaders());
        System.out.println("Log request: " + auditLogConfig.isLogRequest());
        System.out.println("Log response: " + auditLogConfig.isLogResponse());

        Map<Method, MethodLog> methodLogs = auditLogConfig.getMethodLogs();
        System.out.println("Method logs count: " + methodLogs.size());
        for (Map.Entry<Method, MethodLog> entry : methodLogs.entrySet()) {
            System.out.println("Method: " + entry.getKey().getName() +
                             ", Config: " + entry.getValue().isEnable());
        }
    }
}
