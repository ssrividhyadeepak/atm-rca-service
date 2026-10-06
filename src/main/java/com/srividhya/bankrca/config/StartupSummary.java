package com.srividhya.bankrca.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import com.srividhya.bankrca.assistant.RcaAssistant;
import com.srividhya.bankrca.knowledge.KnowledgeService;
import com.srividhya.bankrca.monitor.MonitoringService;
import com.srividhya.bankrca.source.SourceRepository;
import com.srividhya.bankrca.splunk.SplunkClient;
import com.srividhya.bankrca.storage.RunStore;

/** Says at startup which connections are real and which are local stand-ins. */
@Component
public class StartupSummary implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSummary.class);

    private final Environment env;
    private final RcaProperties props;
    private final RunStore store;
    private final SplunkClient splunk;
    private final MonitoringService monitoring;
    private final SourceRepository source;
    private final KnowledgeService knowledge;
    private final RcaAssistant assistant;

    public StartupSummary(Environment env, RcaProperties props, RunStore store, SplunkClient splunk,
            MonitoringService monitoring, SourceRepository source, KnowledgeService knowledge,
            RcaAssistant assistant) {
        this.assistant = assistant;
        this.source = source;
        this.knowledge = knowledge;
        this.env = env;
        this.props = props;
        this.store = store;
        this.splunk = splunk;
        this.monitoring = monitoring;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));
        String base = "http://" + env.getProperty("server.address") + ":" + env.getProperty("local.server.port",
                env.getProperty("server.port"));
        log.info("Mode:     {}", prod ? "PROD - real connections" : "LOCAL - nothing outside this machine is used");
        log.info("Storage:  {}", store.description());
        log.info("Splunk:   {}", splunk.description());
        log.info("Source:   {}", source.description());
        log.info("Knowledge: {}", knowledge.description());
        log.info("Monitor:  namespace {}, components {}, last {}h", props.monitor().namespace(),
                props.monitor().components().stream().map(c -> c.pattern()).toList(),
                props.monitor().window().toHours());
        log.info("Schedule: {}", props.monitor().enabled() ? "monitoring run on cron '" + props.monitor().cron()
                + "' (UTC)" : "off");
        log.info("Health:   {}/actuator/health", base);
        log.info("Runs:     {}/api/runs", base);
        log.info("Failures: {}/api/failures", base);
        log.info("Report:   {}/api/rca/latest.md", base);
        log.info("Tools:    {}/api/tools  (assistant model: {})", base, assistant.modelName());
        if (props.monitor().runOnStartup()) {
            monitoring.run("STARTUP");
        }
    }
}
