package com.example.workbench.config;

import java.util.Map;
import org.apache.logging.log4j.ThreadContext;

public final class LoggingContext {

    public static final String REQUEST_ID = "requestId";
    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";
    public static final String WORKSPACE_ID = "workspaceId";
    public static final String INSTANCE_ID = "instanceId";
    public static final String ENVIRONMENT = "environment";

    private static volatile String instanceId = "unknown";
    private static volatile String environment = "development";

    private LoggingContext() {
    }

    public static void put(String key, Object value) {
        if (value == null || value.toString().isBlank()) {
            ThreadContext.remove(key);
        } else {
            String text = value.toString();
            int maxLength = switch (key) {
                case REQUEST_ID, TRACE_ID -> 128;
                case INSTANCE_ID -> 128;
                case USER_ID -> 64;
                case WORKSPACE_ID -> 120;
                case ENVIRONMENT -> 32;
                default -> 256;
            };
            ThreadContext.put(key, text.substring(0, Math.min(text.length(), maxLength)));
        }
    }

    public static Map<String, String> snapshot() {
        return ThreadContext.getImmutableContext();
    }

    public static void initializeDeployment(String nextInstanceId, String nextEnvironment) {
        instanceId = nextInstanceId == null || nextInstanceId.isBlank() ? "unknown" : nextInstanceId;
        environment = nextEnvironment == null || nextEnvironment.isBlank() ? "development" : nextEnvironment;
        putDeploymentContext();
    }

    public static void putDeploymentContext() {
        put(INSTANCE_ID, instanceId);
        put(ENVIRONMENT, environment);
    }

    public static String deploymentValue(String key) {
        if (INSTANCE_ID.equals(key)) return instanceId;
        if (ENVIRONMENT.equals(key)) return environment;
        return null;
    }

    public static void restore(Map<String, String> context) {
        ThreadContext.clearMap();
        if (context != null && !context.isEmpty()) {
            ThreadContext.putAll(context);
        }
    }
}
