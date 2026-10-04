package com.srividhya.atmrca.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import com.srividhya.atmrca.storage.RunStore;

/** Says at startup which connections are real and which are local stand-ins. */
@Component
public class StartupSummary implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSummary.class);

    private final Environment env;
    private final RcaProperties props;
    private final RunStore store;

    public StartupSummary(Environment env, RcaProperties props, RunStore store) {
        this.env = env;
        this.props = props;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));
        String base = "http://" + env.getProperty("server.address") + ":" + env.getProperty("local.server.port",
                env.getProperty("server.port"));
        log.info("Mode:     {}", prod ? "PROD - real connections" : "LOCAL - nothing outside this machine is used");
        log.info("Storage:  {}", store.description());
        log.info("Schedule: {}", props.monitor().enabled() ? "monitoring run on cron '" + props.monitor().cron()
                + "' (UTC)" : "off");
        log.info("Health:   {}/actuator/health", base);
        log.info("Runs:     {}/api/runs", base);
    }
}
