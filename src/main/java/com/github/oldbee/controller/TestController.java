package com.github.oldbee.controller;

import com.github.oldbee.auditlog.annotation.Log;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试控制器
 */
@RestController
@RequestMapping("/api")
public class TestController {

    @Log(
            enable = "true",
            logResponse = "true",
            logHeaders = "true",
            sensitiveHeaders = {"Admin-Token", "password", "cookie"})
    @GetMapping("/query")
    public ResponseEntity<String> query() {
        return ResponseEntity.ok("Query result");
    }

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("Test result");
    }
}