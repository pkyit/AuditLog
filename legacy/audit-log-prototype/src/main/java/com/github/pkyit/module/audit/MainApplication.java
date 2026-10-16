package com.github.pkyit.module.audit;

import com.github.pkyit.module.audit.annotation.EnableAuditLog;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAuditLog(enable = true)
@EnableAsync
public class MainApplication {
	public static void main(String[] args) {
		SpringApplication.run(MainApplication.class, args);
	}
}
