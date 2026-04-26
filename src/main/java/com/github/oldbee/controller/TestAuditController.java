package com.github.oldbee.controller;

import com.github.oldbee.auditlog.annotation.Log;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/test")
public class TestAuditController {

    @Log
    @GetMapping("/success")
    public ResponseEntity<String> successfulOperation() {
        return ResponseEntity.ok("操作成功");
    }

    @Log
    @GetMapping("/error")
    public ResponseEntity<String> errorOperation() {
        throw new RuntimeException("模拟业务异常");
    }

    @GetMapping("/no-audit")
    public ResponseEntity<String> noAuditOperation() {
        return ResponseEntity.ok("无审计日志");
    }
}