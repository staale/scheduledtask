package com.storebrand.scheduledtask.healthcheck;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.CheckSpecification;
import com.storebrand.healthcheck.HealthCheckMetadata;
import com.storebrand.healthcheck.HealthCheckRegistry;
import com.storebrand.healthcheck.Responsible;
import com.storebrand.scheduledtask.ScheduledTaskConfig.Criticality;
import com.storebrand.scheduledtask.ScheduledTaskConfig.Recovery;
import com.storebrand.scheduledtask.ScheduledTaskService;
import com.storebrand.scheduledtask.ScheduledTaskService.MasterLockDto;
import com.storebrand.scheduledtask.ScheduledTaskService.ScheduleRunContext;
import com.storebrand.scheduledtask.ScheduledTaskService.ScheduledTask;
import com.storebrand.scheduledtask.ScheduledTaskService.State;
import com.storebrand.scheduledtask.ScheduledTaskService.ScheduleDto;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Health checks for scheduled tasks, provided by the Storebrand HealthCheck library.
 */
public class ScheduledTaskHealthCheck {

    private static final Map<Criticality, Set<Axis>> CRITICALITY_AXES;
    static {
        Map<Criticality, Set<Axis>> criticalityAxes = new HashMap<>();
        criticalityAxes.put(Criticality.MISSION_CRITICAL,
                Stream.of(Axis.CRITICAL_WAKE_PEOPLE_UP, Axis.DEGRADED_COMPLETE).collect(toSet()));
        criticalityAxes.put(Criticality.VITAL,
                Stream.of(Axis.DEGRADED_COMPLETE).collect(toSet()));
        criticalityAxes.put(Criticality.IMPORTANT,
                Stream.of(Axis.DEGRADED_PARTIAL).collect(toSet()));
        criticalityAxes.put(Criticality.MINOR,
                Stream.of(Axis.DEGRADED_MINOR).collect(toSet()));
        CRITICALITY_AXES = Collections.unmodifiableMap(criticalityAxes);
    }

    private final ScheduledTaskService _scheduledTaskService;
    private final Clock _clock;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ScheduledTaskHealthCheck(ScheduledTaskService scheduledTaskService, Clock clock) {
        _scheduledTaskService = scheduledTaskService;
        _clock = clock;
    }

    /**
     * Method for initializing health checks for scheduled tasks.
     */
    public void initialize(HealthCheckRegistry registry) {
        registry.registerHealthCheck(HealthCheckMetadata.create("Scheduled tasks master lock"),
                this::masterLockHealthCheck);

        registry.registerHealthCheck(HealthCheckMetadata.create("Scheduled tasks"),
                this::scheduledTaskHealthCheck);
    }

    /**
     * Health check specification for a check that checks what service has the lock, or if any has it at all.
     */
    public void masterLockHealthCheck(CheckSpecification spec) {
        spec.dynamicText(context -> "Running node has the master lock: " + (_scheduledTaskService.hasMasterLock() ? "yes" : "no"));
        // :: Get the status from the db on who has the lock
        spec.check(Responsible.DEVELOPERS, Axis.DEGRADED_PARTIAL, context -> {
            Optional<MasterLockDto> masterLock = _scheduledTaskService.getMasterLock();
            // ?: Is the lock yet unclaimed?
            if (!masterLock.isPresent()) {
                // -> Yes, nobody has the lock. This means this node is just starting and have not managed
                // to claim the lock on the first run yet.
                return context.ok("Unclaimed lock");
            }

            // ?: We have a lock but it may be old
            if (!masterLock.get().isValid(_clock.instant())) {
                // Yes-> it is an old lock
                return context.fault("No-one currently has the lock, last node to have it was "
                        + "[" + masterLock.get().getNodeName() + "]. It was last updated "
                        + "[" + LocalDateTime.ofInstant(masterLock.get().getLockLastUpdatedTime(), ZoneId.systemDefault()) + "]");
            }

            // ----- Lock flag in db is valid.
            return context.ok("The node [" + masterLock.get().getNodeName() + "] has the lock! "
                    + "It was acquired "
                    + "[" + LocalDateTime.ofInstant(masterLock.get().getLockTakenTime(), ZoneId.systemDefault()) + "] "
                    + "and it was last updated ["
                    + LocalDateTime.ofInstant(masterLock.get().getLockLastUpdatedTime(), ZoneId.systemDefault()) + "]");
        });
    }

    /**
     * Health checks for verifying that scheduled tasks run as expected.
     */
    public void scheduledTaskHealthCheck(CheckSpecification spec) {
        spec.check(Responsible.DEVELOPERS, Axis.DEGRADED_MINOR, context -> {
            Map<String, ScheduleDto> allSchedulesFromDb = _scheduledTaskService.getSchedulesFromRepository().stream()
                    .collect(toMap(ScheduledTaskService.ScheduleDto::getScheduleName, scheduleDto -> scheduleDto));
            TableBuilder tableBuilder = new TableBuilder(
                    "Schedule",
                    "Active",
                    "Default cron",
                    "Running",
                    "Overdue",
                    "Status");
            boolean failed = false;
            for (Entry<String, ScheduledTask> entry : _scheduledTaskService.getSchedules().entrySet()) {
                boolean scheduleFailed;
                // Is this schedule active?
                boolean isActive = allSchedulesFromDb.get(entry.getKey()).isActive();
                scheduleFailed = !isActive;

                // has overridden CronExpression / default cron
                boolean defaultCron = !allSchedulesFromDb.get(entry.getKey())
                        .getOverriddenCronExpression().isPresent();
                scheduleFailed = scheduleFailed || !defaultCron;

                // Is this schedule overdue. This is stored in the memory version of the current run.
                // It is only overdue if it is active, is running and marked as overdue.
                boolean isOverdue = entry.getValue().isOverdue()
                        && entry.getValue().isActive()
                        && entry.getValue().isRunning();
                scheduleFailed = scheduleFailed || isOverdue;

                // Add a table row
                tableBuilder.addRow(entry.getKey(),
                        isActive,
                        defaultCron,
                        entry.getValue().isRunning(),
                        isOverdue,
                        scheduleFailed ? "WARN" : "OK");
                failed = failed || scheduleFailed;
            }
            context.text(tableBuilder.toString());
            return context.faultConditionally(failed, "Schedules status");
        });

        // Add a blank line to add som "air" between the two section
        spec.staticText("");
        // Render out based on the set Healthlevel. We check the lastRun to see if this failed or if the schedule is
        // overdue times 10. If the schedule is overdue or last run failed we render ONE line for each error and
        // set the defined getHealthCheckLevel() for this schedule.
        for (Entry<String, ScheduledTask> entry : _scheduledTaskService.getSchedules().entrySet()) {
            Set<Axis> axes = new TreeSet<>(CRITICALITY_AXES.get(entry.getValue().getConfig().getCriticality()));
            if (entry.getValue().getConfig().getRecovery() == Recovery.MANUAL_INTERVENTION) {
                axes.add(Axis.MANUAL_INTERVENTION_REQUIRED);
            }
            spec.check(Responsible.DEVELOPERS, axes.toArray(new Axis[]{}), context -> {
                Optional<ScheduleRunContext> lastRunForSchedule =
                        entry.getValue().getLastScheduleRun();
                boolean runFailed = false;
                // :? Did the last run fail?
                if (lastRunForSchedule.isPresent() && State.FAILED.equals(lastRunForSchedule.get().getStatus())) {
                    // -> Yes this status failed
                    runFailed = true;
                    context.text(entry.getKey() + " last run failed!");
                }

                Long runTime = entry.getValue().runTimeInMinutes().orElse(0L);
                int wayOverdueTime = 10 * entry.getValue().getMaxExpectedMinutesToRun();
                // :? Is this schedule way overdue?
                if (runTime > 10 * wayOverdueTime) {
                    // -> Yes this run has taken 10 times the estimated amount so we should display the HealthCheckLevel
                    runFailed = true;
                    context.text(entry.getKey() + " is taking more than 10x the estimated runtime!");
                }

                return context.faultConditionally(runFailed, entry.getKey() + " last run status");

            });
        }
    }
}
