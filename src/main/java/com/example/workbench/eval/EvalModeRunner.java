package com.example.workbench.eval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class EvalModeRunner implements CommandLineRunner {

    private final String appMode;
    private final EvalRunner evalRunner;
    private final ApplicationContext applicationContext;

    public EvalModeRunner(
            @Value("${app.mode:server}") String appMode,
            EvalRunner evalRunner,
            ApplicationContext applicationContext
    ) {
        this.appMode = appMode;
        this.evalRunner = evalRunner;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!"eval".equalsIgnoreCase(appMode)) {
            return;
        }

        EvalSummary summary = evalRunner.run();
        int exitCode = SpringApplication.exit(applicationContext, () -> summary.gatePassed() ? 0 : 2);
        System.exit(exitCode);
    }
}
