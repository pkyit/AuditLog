package com.github.pkyit.module.audit.controller;

import com.github.pkyit.module.audit.annotation.AuditLog;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/user")
public class User {

	@AuditLog
	@GetMapping("/queryUserInfo")
	public String getUserInfoByUserId(@RequestParam String userId) {


		return userId;
	}

	@AuditLog
	@GetMapping("/queryUserInfo2")
	public String getUserInfoByUserId(@RequestParam String userId,String deptId) {
		return userId+deptId;
	}


	//	@AuditLog(name = "查询用户信息3", logHeaders = true, logRequestParams = true, logResponseParams = true)
	@GetMapping("/queryUserInfoNoAudit")
	public List<String> getUserInfo(@RequestParam String userName) {
		ArrayList<String> objects = new ArrayList<>();
		objects.add("张三");
		objects.add("李斯");
		objects.add(userName);
		objects.add("wangwu");
		return objects;
	}

	@AuditLog(name = "查询用户信息3", logHeaders = false, logRequestParams = false, logResponseParams = false)
	@GetMapping("/queryUserInfo3")
	public List<String> getUserInfoByUserId2(@RequestParam String userName) {
		// 模拟10%的可能性会报错
	/*	if (Math.random() < 0.1) {
			int x=1/0;
		}*/
		ArrayList<String> objects = new ArrayList<>();
		objects.add("张三");
		objects.add("李斯");
		objects.add(userName);
		objects.add("wangwu");
		return objects;
	}
}
