package io.github.kaulinmindiola.warden.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/**
 * No worker of the application ticks in the shared test context (plan 12.2): every test that
 * needs a worker calls it directly. A tick at an unpredictable moment would dispatch the outbox,
 * strip secrets other tests read or reconcile revocations in the middle of a test.
 *
 * <p>Scheduling itself may be on: a library can enable it even though {@link
 * SchedulingConfiguration} is skipped. What must be absent are the application's scheduled
 * methods, whose beans are all {@link ConditionalOnSchedulingEnabled}.
 */
class WorkerSchedulesIT extends IntegrationTest {

    private static final String BASE_PACKAGE = "io.github.kaulinmindiola.warden";

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void noWorkerOfTheApplicationIsScheduledInTests() {
        System.out.println("Scheduling processor registered by: " + processorOrigin());
        List<String> applicationTasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(String::valueOf)
                .filter(task -> task.contains(BASE_PACKAGE))
                .toList();

        assertThat(applicationTasks)
                .as("scheduled tasks of the application; scheduling processor registered by: %s", processorOrigin())
                .isEmpty();
    }

    /** Where the {@code @Scheduled} processor came from, if anything registered it. */
    private String processorOrigin() {
        ConfigurableListableBeanFactory beans = context.getBeanFactory();
        String name = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
        if (!(beans instanceof BeanDefinitionRegistry registry) || !registry.containsBeanDefinition(name)) {
            return "nothing";
        }
        return String.valueOf(registry.getBeanDefinition(name).getResourceDescription());
    }
}
