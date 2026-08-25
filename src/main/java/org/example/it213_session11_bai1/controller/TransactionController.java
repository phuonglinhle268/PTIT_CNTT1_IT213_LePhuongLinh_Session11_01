package org.example.it213_session11_bai1.controller;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);
    private final Tracer tracer;

    public TransactionController(Tracer tracer) {
        this.tracer = tracer;
    }

    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processTransaction(@RequestBody Map<String, Object> request) {
        Span newSpan = this.tracer.nextSpan().name("RikkeiPay.ProcessTransaction").start();
        try (Tracer.SpanInScope ws = this.tracer.withSpan(newSpan.start())) {
            String transactionId = UUID.randomUUID().toString();
            Double amount = request.containsKey("amount") ? Double.parseDouble(request.get("amount").toString()) : 500000.0;
            String sender = request.getOrDefault("sender", "ACC_123456").toString();
            String receiver = request.getOrDefault("receiver", "ACC_789012").toString();

            log.info("Processing transaction ID: {}, amount: {}, sender: {}, receiver: {}", transactionId, amount, sender, receiver);

            newSpan.tag("transaction.id", transactionId);
            newSpan.tag("transaction.amount", String.valueOf(amount));
            newSpan.tag("banking.system", "RikkeiPay Assistant");

            // Simulate banking operation business logic
            Thread.sleep(50);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "transactionId", transactionId,
                    "amount", amount,
                    "message", "Giao dịch thanh toán RikkeiPay thành công!"
            ));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            newSpan.error(e);
            return ResponseEntity.internalServerError().body(Map.of("status", "FAILED", "error", e.getMessage()));
        } finally {
            newSpan.end();
        }
    }

    @GetMapping("/health-check")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "service", "RikkeiPay Assistant Tracing Core",
                "status", "UP"
        ));
    }
}
