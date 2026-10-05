package com.srividhya.bankrca.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.srividhya.bankrca.monitor.MonitoringRun;
import com.srividhya.bankrca.monitor.MonitoringService;

/** Monitoring runs: list the recent ones, or start one now without waiting for the schedule. */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private static final int MAX_LIMIT = 100;

    private final MonitoringService monitoring;

    public RunController(MonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    @GetMapping
    public List<MonitoringRun> latest(@RequestParam(defaultValue = "20") int limit) {
        return monitoring.latest(Math.max(1, Math.min(limit, MAX_LIMIT)));
    }

    @PostMapping
    public MonitoringRun runNow() {
        return monitoring.run("MANUAL");
    }
}
