package com.github.pkyit.module.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.WebApplicationContext;

import java.util.LinkedHashMap;

@SpringBootTest
class Test1 {

	@Autowired
	WebApplicationContext context;

	@Test
	void contextLoads() {

		LinkedHashMap auditLogMethods = (LinkedHashMap) context.getBean("auditLogMethods");
		auditLogMethods.forEach((k, v) -> {
			System.out.println(k);
			System.out.println(v);
		});
	}
}
