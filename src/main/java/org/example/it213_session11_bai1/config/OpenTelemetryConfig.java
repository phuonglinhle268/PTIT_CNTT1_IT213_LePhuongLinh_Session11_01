package org.example.it213_session11_bai1.config;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class OpenTelemetryConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryConfig.class);

    @Value("${management.otlp.tracing.endpoint:http://localhost:3000/api/public/otel/v1/traces}")
    private String endpoint;

    @Value("${management.otlp.tracing.headers.Authorization:Basic cGstbGYtMTIzNDU2NzgtOTBhYi1jZGVmLTEyMzQtNTY3ODkwYWJjZGVmOnNrLWxmLTEyMzQ1Njc4LTkwYWItY2RlZi0xMjM0LTU2Nzg5MGFiY2RlZg==}")
    private String authHeader;

    @Value("${management.otlp.tracing.max-queue-size:2048}")
    private int maxQueueSize;

    @Value("${management.otlp.tracing.schedule-delay:5000ms}")
    private Duration scheduleDelay;

    @Value("${management.otlp.tracing.max-export-batch-size:512}")
    private int maxExportBatchSize;

    @Value("${management.otlp.tracing.export-timeout:10000ms}")
    private Duration exportTimeout;

    @Bean
    public OtlpHttpSpanExporter otlpHttpSpanExporter() {
        log.info("[OTLP Exporter] Initializing OTLP HTTP Span Exporter connecting to endpoint: {}", endpoint);
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .addHeader("Authorization", authHeader)
                .setTimeout(exportTimeout)
                .build();
    }

    @Bean
    public SpanProcessor batchSpanProcessor(OtlpHttpSpanExporter exporter) {
        log.info("[OTel Defense] Initializing Asynchronous BatchSpanProcessor with Drop-Span Mechanism:");
        log.info("  -> max-queue-size: {}", maxQueueSize);
        log.info("  -> max-export-batch-size: {}", maxExportBatchSize);
        log.info("  -> schedule-delay: {}", scheduleDelay);
        log.info("  -> export-timeout: {}", exportTimeout);

        return BatchSpanProcessor.builder(exporter)
                .setMaxQueueSize(maxQueueSize)
                .setMaxExportBatchSize(maxExportBatchSize)
                .setScheduleDelay(scheduleDelay)
                .setExporterTimeout(exportTimeout)
                .build();
    }
}
