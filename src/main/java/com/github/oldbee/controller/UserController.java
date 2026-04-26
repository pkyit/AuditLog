package com.github.oldbee.controller;

import com.github.oldbee.auditlog.annotation.Log;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * 用户控制器 - 用于测试日志审计功能
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    /**
     * 获取所有用户 - 使用全局配置记录日志
     */
    @GetMapping
    public ResponseEntity<List<String>> getAllUsers() {
        List<String> users = Arrays.asList("Alice", "Bob", "Charlie");
        return ResponseEntity.ok(users);
    }

    /**
     * 根据ID获取用户 - 使用方法级@Log注解配置
     */
    @Log(
        enable = "true",
        logResponse = "true",
        logHeaders = "true",
        sensitiveHeaders = {"Authorization", "Admin-Token"}
    )
    @GetMapping("/{id}")
    public ResponseEntity<String> getUserById(@PathVariable("id") Long id, @RequestHeader(value = "Authorization", required = false) String auth) {
        return ResponseEntity.ok("User with ID: " + id);
    }

    /**
     * 创建用户 - 使用方法级@Log注解配置
     */
    @Log(
        enable = "true",
        logRequest = "true",
        logResponse = "true"
    )
    @PostMapping
    public ResponseEntity<String> createUser(@RequestBody String user) {
        return ResponseEntity.ok("User created: " + user);
    }

    /**
     * 更新用户 - 不记录日志
     */
    @Log(enable = "false")
    @PutMapping("/{id}")
    public ResponseEntity<String> updateUser(@PathVariable("id") Long id, @RequestBody String user) {
        return ResponseEntity.ok("User updated: " + id);
    }

    /**
     * 删除用户 - 使用全局配置记录日志
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteUser(@PathVariable("id") Long id) {
        return ResponseEntity.ok("User deleted: " + id);
    }
}