package jump.email.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configuration for async processing of emails.
 * Enables async execution with a dedicated thread pool.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
    
    @Bean(name = "emailProcessingExecutor")
    public Executor emailProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5); // Core threads
        executor.setMaxPoolSize(10); // Maximum threads
        executor.setQueueCapacity(100); // Queue capacity
        executor.setThreadNamePrefix("email-processor-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
