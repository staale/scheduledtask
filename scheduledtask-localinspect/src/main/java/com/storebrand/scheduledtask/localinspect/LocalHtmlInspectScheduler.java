/*
 * Copyright 2022 Storebrand ASA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.storebrand.scheduledtask.localinspect;

import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.io.Writer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.storebrand.scheduledtask.ScheduledTask;
import com.storebrand.scheduledtask.ScheduledTaskRegistry;
import com.storebrand.scheduledtask.ScheduledTaskRegistry.LogEntry;
import com.storebrand.scheduledtask.ScheduledTaskRegistry.MasterLock;
import com.storebrand.scheduledtask.ScheduledTaskRegistry.Schedule;
import com.storebrand.scheduledtask.ScheduledTaskRegistry.ScheduleRunContext;
import com.storebrand.scheduledtask.ScheduledTaskRegistry.State;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;


/**
 * Will produce an "embeddable" HTML interface.
 * To use this you need to include the {@link #getJavascript(Writer)}, {@link #getStyleSheet(Writer)} and
 * {@link #createSchedulesOverview(Writer)}. The last part is to render the show runs sections that displays
 * historic runs for a schedule by using {@link #createScheduleRunsTable(Writer, LocalDateTime, LocalDateTime, String, String)}
 * <p>
 * For now, this has been developed with a dependency on Bootstrap 3.4.1 and JQuery 1.12.4. This will be improved, so
 * the entire HTML interface is self-contained.
 *
 * @author Dag Bertelsen
 * @author Kristian Hiim
 */
public class LocalHtmlInspectScheduler {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final String MONITOR_CHANGE_ACTIVE_PARAM = "toggleActive.local";
    public static final String MONITOR_EXECUTE_SCHEDULER = "executeScheduler.local";
    public static final String MONITOR_CHANGE_CRON = "changeCron.local";
    public static final String MONITOR_SHOW_RUNS = "showRuns.local";
    public static final String MONITOR_SHOW_LOGS = "showLogs.local";
    public static final String MONITOR_DATE_FROM = "date-from";
    public static final String MONITOR_TIME_FROM = "time-from";
    public static final String MONITOR_DATE_TO = "date-to";
    public static final String MONITOR_TIME_TO = "time-to";
    public static final String MONITOR_CHANGE_CRON_SCHEDULER_NAME = "changeCronSchedulerName.local";

    private final ScheduledTaskRegistry _scheduledTaskRegistry;
    private final Clock _clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "This is standard dependency injection.")
    public LocalHtmlInspectScheduler(ScheduledTaskRegistry scheduledTaskRegistry, Clock clock) {
        _scheduledTaskRegistry = scheduledTaskRegistry;
        _clock = clock;
    }

    /**
     * Retrieve the javascript, should only be included once.
     */
    public void getJavascript(Writer out) throws IOException {
        // The function that closes the modalbox
        out.write("function closeLogModalBox() {"
                + "    let openModals = $('.log-modal-box.show-modal');"
                + "    openModals.each(function () {"
                + "        let modal = $(this);"
                + "        modal.removeClass(\"show-modal\");"
                + "        modal.addClass(\"hide-modal\");"
                + "    });"
                + "}; ");

        // Click listener that opens the modalbox.
        out.write("$('.log-modal-header').click(function () {"
                + "    let modalboxHeader = $(this);"
                //getting all the sibling with the slide-content tag.
                + "    let modalboxContents = modalboxHeader.siblings('.log-modal-box');"
                // get the first child element of the header that has the clas log-modal-box (there should only be one).
                + "    let modalBoxToShow = modalboxContents.first();"
                // First remove all classes that is used to show and hide the modal.
                + "    modalBoxToShow.removeClass(\"show-modal hide-modal\");"
                // Then set the show modal for this one.
                + "    modalBoxToShow.addClass(\"show-modal\");"
                + "}); ");
        // Listens for close mobalbox events (by click on the x inside the modalbox
        out.write("$('.log-modal-content .log-modal-content-close').click(closeLogModalBox);");
    }

    /**
     * Retrieve the stylesheet, should only be included once.
     */
    public void getStyleSheet(Writer out) throws IOException {
        // General styling for the schedules-table
        out.write(".schedules-table tbody > tr > td {"
                + " vertical-align: inherit;"
                + "}");
        // General styling for the log-content table
        out.write(".error-content .error {"
                + "    background-repeat: no-repeat;"
                + "    padding-left: 30px;"
                + "    background-image: url(data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIGhlaWdodD0iMjQiIHZpZXdCb3g9IjAgMCAyNCAyNCIgd2lkdGg9IjI0Ij48cGF0aCBkPSJNMCAwaDI0djI0SDBWMHoiIGZpbGw9Im5vbmUiLz48cGF0aCBkPSJNMTUuNzMgM0g4LjI3TDMgOC4yN3Y3LjQ2TDguMjcgMjFoNy40NkwyMSAxNS43M1Y4LjI3TDE1LjczIDN6TTE5IDE0LjlMMTQuOSAxOUg5LjFMNSAxNC45VjkuMUw5LjEgNWg1LjhMMTkgOS4xdjUuOHoiLz48Y2lyY2xlIGN4PSIxMiIgY3k9IjE2IiByPSIxIi8+PHBhdGggZD0iTTExIDdoMnY3aC0yeiIvPjwvc3ZnPg==);"
                + "    margin-top: 5px;"
                + "    color: red;"
                + "}"
                + ".log-content,"
                + ".error-content {"
                + "    max-width: 700px;"
                + "}"
                + ".log-content ul {"
                + "    list-style-type: none;"
                + "    padding: 0px;"
                + "    margin: 0px;"
                + "}"
                + ".log-content ul li {"
                + "    margin-bottom: 10px;"
                + "    border-bottom: 1px lightgray dotted;"
                + "}"
                + ".log-content ul li:last-child {"
                + "    margin-bottom: 0px;"
                + "}"
                + ".log-content .log-message {"
                + "    display:inline-block;"
                + "}"
                + ".log-content .log-message-and-time {"
                + "    min-width: 400px;"
                + "}"

                + ".log-content .log-time {"
                + "    font-weight: bold;"
                + "    display: block;"
                + "}"
                + ".input-group-btn.show-logs button {"
                + "    float: right;"
                + "}");
        // Styling for the dateTime picker
        out.write(".historic-runs-search .datetimepicker {"
                + "    display: inline-flex;"
                + "    align-items: center;"
                + "    background-color: #fff;"
                + "    border: 1px solid black;"
                + "    border-radius: 8px;"
                + "} "
                + ".historic-runs-search .datetimepicker:focus-within {"
                + "     border-color: teal;"
                + "} "
                + ".historic-runs-search .datetimepicker input {"
                + "     font: inherit;"
                + "     color: inherit;"
                + "     appearance: none;"
                + "     outline: none;"
                + "     border: 0;"
                + "     background-color: transparent;"
                + "} "
                + ".historic-runs-search .datetimepicker input[type=date] {"
                + "     width: 13rem;"
                + "     padding: .25rem 0 .25rem .5rem;"
                + "     border-right-width: 0;"
                + "} "
                + ".historic-runs-search .datetimepicker input[type=time] {"
                + "     width: 7.5rem;"
                + "     padding: .25rem .5rem .25rem 0;"
                + "     border-left-width: 0;"
                + "} "
                + ".historic-runs-search .datetimepicker span {"
                + "     height: 1rem;"
                + "     margin-right: .25rem;"
                + "     margin-left: .25rem;"
                + "     border-right: 1px solid #ddd;"
                + "} "
                + ".historic-runs-search form {"
                + "    display: inline-block;"
                + "    margin-bottom: 0px;"
                + "}"
                + ".historic-runs-search .input-group {"
                + "    display: inline-block;"
                + "}"
                + ".historic-runs-search .input-group-btn {"
                + "    display: inline-block;"
                + "    margin-left: 1em;"
                + "}"

        );
        // Styling for the modal box:
        out.write(".log-modal-header ~ .log-modal-box {"
                + "    position: fixed;"
                + "    z-index: 3;"
                + "    left: 0;"
                + "    top: 0;"
                + "    width: 100%;"
                + "    height: 100%;"
                + "    overflow: auto;"
                + "    background-color: rgb(0, 0, 0);"
                + "    background-color: rgba(0, 0, 0, 0.4);"
                + "}"
                + ".log-modal-header ~ .log-modal-box.show-modal {"
                + "    display: block;"
                + "}"

                + ".log-modal-header ~ .log-modal-box.hide-modal {"
                + "    display: none;"
                + "}"

                + ".log-modal-header ~ .log-modal-box .log-modal-content {"
                + "    background-color: white;"
                + "    margin: 15% auto;"
                + "    padding: 20px;"
                + "    border: 1px solid gray;"
                + "    width: 80%;"
                + "    position: relative;"
                + "}"

                + ".log-modal-content-close {"
                + "    color: black;"
                + "    float: right;"
                + "    font-size: 38px;"
                + "    font-weight: bold;"
                + "    position: absolute;"
                + "    top: -11px;"
                + "    right: 10px;"
                + "}"

                + ".log-modal-header:hover,"
                + ".log-modal-header:focus,"
                + ".log-modal-content-close:hover,"
                + ".log-modal-content-close:focus {"
                + "    color: black;"
                + "    text-decoration: none;"
                + "    cursor: pointer;"
                + "}");

    }

    /**
     * Renders the main overview table where all the registered schedules are shown.
     * Here the schedule can be deactivated, triggered to run now, change schedule and button to show the
     * {@link #createScheduleRunsTable(Writer, LocalDateTime, LocalDateTime, String, String)}.
     * <p>
     * The <b>Show runs</b> button will return a parameter {@link #MONITOR_SHOW_RUNS}, this is then used with
     * {@link #createScheduleRunsTable(Writer, LocalDateTime, LocalDateTime, String, String)} to renter that
     * historic runs table.
     * <p>
     * The following parameters are returned by this tables click events:
     * <ul>
     *     <li>{@link #MONITOR_SHOW_RUNS} - Schedule name to be used with
     *     {@link #createScheduleRunsTable(Writer, LocalDateTime, LocalDateTime, String, String)}. This will render
     *     the historic runs for this schedule.</li>
     *     <li>{@link #MONITOR_SHOW_LOGS} - RunId to show the logs for, note the scheduleName must also be set.
     *     is used with {@link #createScheduleRunsTable(Writer, LocalDateTime, LocalDateTime, String, String)}</li>
     *     <li>{@link #MONITOR_CHANGE_ACTIVE_PARAM} - If set is used to toggle the active state with
     *     {@link #toggleActive(String)}</li>
     *     <li>{@link #MONITOR_EXECUTE_SCHEDULER} - If set is used to trigger the schedule to run now. Used with
     *     {@link #triggerSchedule(String)}</li>
     *     <li>{@link #MONITOR_CHANGE_CRON} and {@link #MONITOR_CHANGE_CRON_SCHEDULER_NAME} - Both will be set
     *     when a cron schedule is to be changed. Used with {@link #changeChronSchedule(String, String)}</li>
     * </ul>
     */
    public void createSchedulesOverview(Writer out) throws IOException {
        // Get all schedules from database:
        Map<String, Schedule> allSchedulesFromDb = _scheduledTaskRegistry.getSchedulesFromRepository();
        // Get all the schedules from memory
        List<MonitorScheduleDto> bindingsDtoMap = new ArrayList<>();
        for (ScheduledTask scheduledTask : _scheduledTaskRegistry.getScheduledTasks().values()) {
            MonitorScheduleDto scheduleDto = new MonitorScheduleDto(scheduledTask);
            // Since the in memory can be missing some of the previous runs we need to supplement the in-memory values
            // with data from the tables stb_schedule and stb_schedule_run
            Schedule scheduleFromDb = allSchedulesFromDb.get(scheduledTask.getName());
            scheduleDto.active = scheduleFromDb.isActive();
            scheduleDto.activeCronExpression = scheduleFromDb.getOverriddenCronExpression()
                    // Schedule is not overridden so use the default one
                    .orElse(scheduleDto.getDefaultCronExpression());
            scheduleDto.nextExpectedRun = scheduleFromDb.getNextRun();

            // For the stats of the previous run we need to retrieve the lastRun for this schedule, so we can update
            // the monitor status.
            ScheduledTask schedule = _scheduledTaskRegistry.getScheduledTask(scheduledTask.getName());
            Optional<ScheduleRunContext> lastRun = schedule.getLastScheduleRun();
            // ?: Did we have a last run?
            if (lastRun.isPresent()) {
                scheduleDto.lastRunStatus = lastRun.get().getStatus();
                // -> Yes, we have a previous run
                scheduleDto.lastRunStarted = lastRun.get().getRunStarted().atZone(ZoneId.systemDefault()).toInstant();
                // If last run where set to DONE we can use the statusTime to set this as "completed time"
                if (lastRun.get().getStatus().equals(State.DONE)) {
                    // this will also set lastRunComplete = null when a job is newly started on all nodes (even Active node)
                    scheduleDto.lastRunComplete = lastRun.get().getStatusTime().atZone(ZoneId.systemDefault()).toInstant();
                }
            }

            // :? If this is a slave node we should set Running and Overdue to empty string
            if (!_scheduledTaskRegistry.hasMasterLock()) {
                // -> No, we do not have the master lock so set these to null to inform this is not
                // available.
                scheduleDto.overdue = null;
                scheduleDto.running = null;
            }

            bindingsDtoMap.add(scheduleDto);
        }

        // Create a description informing of node that has the master lock.
        Optional<MasterLock> masterLock = _scheduledTaskRegistry.getMasterLock();
        // Top header informing on what node is currently master
        out.write("<h1>Active Schedules</h1>");
        // ?: did we find any lock?
        if (masterLock.isEmpty()) {
            // -> No, nobody has the lock
            out.write("<div class=\"alert alert-danger\">");
            out.write("Unclaimed lock");
            out.write("</div>");
        }
        // ?: We have a lock, but it may be old
        else if (masterLock.get().getLockLastUpdatedTime().isBefore(
                _clock.instant().minus(5, ChronoUnit.MINUTES))) {
            // Yes-> it is an old lock
            out.write("<div class=\"alert alert-danger\">");
            out.write("No-one currently has the lock, last node to have it where "
                    + "[" + masterLock.get().getNodeName() + "]");
            out.write("</div>");
        }
        else {
            // -----  Someone has the lock, and it's under 5 min old and still valid.
            // ?: Is this running node the active one?
            if (_scheduledTaskRegistry.hasMasterLock()) {
                // -> Yes, this running node is the active one.
                out.write("<div class=\"alert alert-success\">");
                out.write("This node is the active node (<b>" + masterLock.get().getNodeName() + "</b>)");
                out.write("</div>");
            }
            else {
                // E-> No, this running node is not the active one
                out.write("<div class=\"alert alert-danger\">");
                out.write("This node is <b>NOT</b> the active node  (<b>" + masterLock.get().getNodeName() + "</b> is active)");
                out.write("</div>");
            }
        }

        // General information about the schedules and this page
        out.write("<ul>"
                + "    <li>When a scheduled method is inactive, the content is not executed on this host. (and server-status will warn)</li>"
                + "    <li>The execution will be reset to active on application boot.</li>"
                + "    <li>Execute to call the scheduled task directly on all servers.</li>"
                + "    <li>Change when the scheduler should run by adjusting the CRON."
                + "        Use <a href=\"http://www.cronmaker.com/\" target=_blank\">Cron Maker</a> to generate cron expression."
                + "    </li>"
                + "    <li>Any schedule updates done on the host that has the masterLock will be<br>"
                + "    executed immediately, if the changes are done on any of the other nodes then the changes can take up to 2 minutes</li>"
                + "</ul>");

        // Table holding information on all the schedules in the system. Also contains buttons to toggle active, run now,
        // change schedule and show runs

        // :: Table header.
        out.write("<table class=\"schedules-table table\">"
                + "    <thead>"
                + "    <td><b>Runner Name</b></td>"
                + "    <td><b>Active</b></td>"
                + "    <td><b>Toggle active</b></td>"
                + "    <td><b>Execute</b></td>"
                + "    <td><b>Running</b></td>"
                + "    <td><b>ExpectedToRun</b></td>"
                + "    <td><b>Last run start</b></td>"
                + "    <td><b>Last run stop</b></td>"
                + "    <td><b>Last run (HH:MM:SS)</b></td>"
                + "    <td><b>Default CRON</b></td>"
                + "    <td>"
                + "        <b>Current <a href=\"https://docs.oracle.com/cd/E12058_01/doc/doc.1014/e12030/cron_expressions.htm\" target=\"_blank\">CRON</a></b>"
                + "        <i>Submit empty value to reset</i>"
                + "    </td>"
                + "    <td><b>Next scheduled run</b></td>"
                + "    <td><b>Run logs</b></td>"
                + "    </thead>");
        // :: Table - Render all schedules, one in each row.
        for (MonitorScheduleDto monitorScheduleDto : bindingsDtoMap) {
            renderScheduleTableRow(out, monitorScheduleDto);
        }
        out.write("</table>");
    }

    /**
     * Render one row in the Scheduler table.
     */
    public void renderScheduleTableRow(Writer out, MonitorScheduleDto schedule) throws IOException {
        out.write("<tr class=\"" + schedule.getRowStyle() + "\">");
        out.write("    <td>" + schedule.getSchedulerName() + "    </td>"
                + "    <td><b>" + schedule.isActive() + "</b></td>"
                + "    <td>"
                + "        <form method=\"post\">"
                + "            <div class=\"input-group\">"
                + "                <input type=\"hidden\" name=\"toggleActive.local\" class=\"form-control\" value=\"" + schedule.getSchedulerName() + "\">"
                + "                <span class=\"input-group-btn\">"
                + "        <button class=\"btn btn-default\" type=\"submit\">Toggle active</button>"
                + "      </span>"
                + "            </div>"
                + "        </form>"
                + "    </td>"
                + "    <td>"
                + "        <form method=\"post\">"
                + "            <div class=\"input-group\">"
                + "                <input type=\"hidden\" name=\"executeScheduler.local\" class=\"form-control\" value=\"" + schedule.getSchedulerName() + "\">"
                + "                <span class=\"input-group-btn\">"
                + "        <button class=\"btn btn-primary\" type=\"submit\">Execute Scheduler</button>"
                + "                 </span>"
                + "            </div>"
                + "        </form>"
                + "    </td>"
                + "    <td>" + schedule.getRunningAndOverdue() + "</td>"
                + "    <td>" + schedule.getMaxExpectedMinutes() + "</td>"
                + "    <td>" + schedule.getLastRunStarted() + "</td>"
                + "    <td>" + schedule.getLastRunComplete() + "</td>"
                + "    <td>" + schedule.getLastRunInHHMMSS() + "</td>"
                + "    <td>" + schedule.getDefaultCronExpression() + "</td>"
                + "    <td>"
                + "        <form method=\"post\">"
                + "            <div class=\"input-group\">"
                + "                <input type=\"text\" name=\"changeCron.local\" class=\"form-control\" value=\"" + schedule.getActiveCronExpression() + "\">"
                + "                <input type=\"hidden\" name=\"changeCronSchedulerName.local\" class=\"form-control\" value=\"" + schedule.getSchedulerName() + "\">"
                + "                <span class=\"input-group-btn\">"
                + "                    <button class=\"btn btn-danger\" type=\"submit\">Submit</button>"
                + "                </span>"
                + "            </div>"
                + "        </form>"
                + "    </td>"
                + "    <td>" + schedule.getNextExpectedRun() + "</td>"
                + "    <td>"
                + "        <form method=\"get\">"
                + "            <div class=\"input-group\">"
                + "                <input type=\"hidden\" name=\"showRuns.local\" class=\"form-control\""
                + "                       value=\"" + schedule.getSchedulerName() + "\">"
                + "                <span class=\"input-group-btn\">"
                + "                    <button class=\"btn btn-primary\" type=\"submit\">show runs</button>"
                + "                </span>"
                + "            </div>"
                + "        </form>"
                + "    </td>"
                + "</tr>");
    }

    // ===== Render the logs (historic runs) for one given schedule ================================

    /**
     * Constructs the table where historic runs are shown.
     *
     * @param out
     *          - Where the html for this table are written back.
     * @param fromDate
     *          - A {@link LocalDateTime} to filter by from date on when to show the historic runs.
     * @param toDate
     *          - A {@link LocalDateTime} to filter to date on when to show the historic runs.
     * @param scheduleName
     *          - A schedule name to show the runs for. usually retrieved from {@link #MONITOR_SHOW_RUNS} parameter.
     * @param includeLogsForRunId
     *          - If set also retrieves the runs logs for a schedule. Usually retrieved from {@link #MONITOR_SHOW_LOGS}
     *          parameter.
     * @throws IOException
     */
    public void createScheduleRunsTable(Writer out, LocalDateTime fromDate, LocalDateTime toDate,
            String scheduleName, String includeLogsForRunId) throws IOException {
        ScheduledTask schedule = _scheduledTaskRegistry.getScheduledTask(scheduleName);

        // ?: Did we get a schedule?
        if (schedule == null) {
            // -> No name where set so render nothing
            out.write("");
            return;
        }

        // Get the historic runs for this schedule:
        List<MonitorHistoricRunDto> scheduleRuns = schedule.getAllScheduleRunsBetween(fromDate, toDate).stream()
                .map(MonitorHistoricRunDto::fromContext)
                .collect(toList());

        // We got a schedule name, check to see if we found it.
        out.write("<h2>Runs for schedule <b>" + scheduleName + "</b></h2>");

        // Show the timespan on when we are retrieving the historic runs
        out.write("<div class=\"historic-runs-search\">Run start from "
                + "<form method=\"get\">");
        // Inspired from https://codepen.io/herteleo/pen/LraqoZ, this uses date and time html tags that should be
        // supported by all major browsers. This is then styled to "look" as two buttons connected to one and creating
        // a single dateTime field. Ideally this can be replaced with datetime-local when safari and firefox supports it
        // (if they ever do).
        out.write("<div class=\"datetimepicker\">"
                + "     <input type=\"date\" name=\"" + MONITOR_DATE_FROM + "\" id=\"date-from\" value=\""
                + toIsoLocalDate(fromDate) + "\">"
                + "     <span></span>"
                + "     <input type=\"time\" name=\"" + MONITOR_TIME_FROM + "\" id=\"time-from\" value=\""
                + toLocalTime(fromDate) + "\">"
                + "</div>");
        out.write(" to ");

        out.write("<div class=\"datetimepicker\">"
                + "     <input type=\"date\" name=\"" + MONITOR_DATE_TO + "\" id=\"date-to\" value=\"" + toIsoLocalDate(
                toDate) + "\">"
                + "     <span></span>"
                + "     <input type=\"time\" name=\"" + MONITOR_TIME_TO + "\" id=\"time-to\" value=\"" + toLocalTime(
                toDate) + "\">"
                + "</div>");
        out.write("<div class=\"input-group\">"
                + "<input type=\"hidden\" name=\"showRuns.local\" class=\"form-control\" value=\"" + scheduleName
                + "\">"
                + "    <span class=\"input-group-btn\">"
                + "        <button class=\"btn btn-primary\" type=\"submit\">Search</button>"
                + "    </span>"
                + "</div>"
                + "</form>");
        out.write("</div>");

        // ?: Is there any schedule runs to show?
        if (scheduleRuns.isEmpty()) {
            // -> No, there is no schedule runs to show
            out.write("<p>No runs found!</p>");
            return;
        }

        // ?: Should we also retrieve the detailed logs for a specific runId?
        if (includeLogsForRunId != null) {
            // -> Yes, we should get full logs for this schedule
            final long runId = Long.parseLong(includeLogsForRunId);
            ScheduleRunContext instance = schedule.getInstance(runId);
            // ?: Did we get logs for this instance
            if (instance != null) {
                // -> Yes we did find an instance, check if it has some logEntries
                List<MonitorHistoricRunLogEntryDto> logs = instance.getLogEntries()
                        .stream().map(MonitorHistoricRunLogEntryDto::fromDto)
                        .collect(toList());
                    scheduleRuns.stream()
                            .filter(run -> run.getRunId() == runId)
                            .findFirst()
                            .ifPresent(dto -> dto.setLogEntries(logs));
            }
        }

        // ------ We have a schedule that we should show the runs for and we have some historic runs in the list.
        // Render the table header

        // render the log entries.. note a schedule only have the log entries if we have requested it
        // (IE pressed the show logs button). We do this due to the schedules may contain huge number of logs

        // :: Table header
        out.write("<table class=\"historic-runs-table table\">"
                + "    <thead>"
                + "    <td><b>RunId</b></td>"
                + "    <td><b>Scheduler name</b></td>"
                + "    <td><b>Hostname</b></td>"
                + "    <td><b>Status</b></td>"
                + "    <td><b>Status msg</b></td>"
                + "    <td><b>Status throwable</b></td>"
                + "    <td><b>Run start</b></td>"
                + "    <td><b>Status time</b></td>"
                + "    <td><b>Log</b></td>"
                + "    </thead>");

        // Render each table row.
        for (MonitorHistoricRunDto runDto : scheduleRuns) {
            renderScheduleRunsRow(out, runDto);
        }
        out.write("</table>");
    }

    private String toLocalTime(LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private String toIsoLocalDate(LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public void renderScheduleRunsRow(Writer out, MonitorHistoricRunDto runDto) throws IOException {
        out.write("<tr>"
                + "    <td>" + runDto.getRunId() + "</td>"
                + "    <td>" + runDto.getScheduleName() + "</td>"
                + "    <td>" + runDto.getHostname() + "</td>"
                + "    <td>" + runDto.getStatus() + "</td>"
                + "    <td>" + runDto.getStatusMsg() + "</td>"
                + "    <td>"
                + "        <div class=\"error-content log-modal-header\">"
                + "            <div class=\"content-message error\">" + runDto.getStatusStackTraceFirstLine() + "</div>"
                + "        </div>"
                + "        <div class=\"log-modal-box hide-modal\">"
                + "            <div class=\"log-modal-content\">"
                + "                <span class=\"log-modal-content-close\">&times;</span>"
                + "                <p>" + runDto.getStatusThrowableAsHtml() + "</p>"
                + "            </div>"
                + "        </div>"
                + "    </td>"
                + "    <td>" + runDto.getRunStart() + "</td>"
                + "    <td>" + runDto.getStatusTime() + "</td>");

        // Have we any logs here to show?
        if (runDto.getLogEntries().isEmpty()) {
            // -> No, we have no logentries loaded for this run. So render the show logs button to the user can request
            // to load the logs for this run.
            out.write("<td>"
                    + "    <form method=\"get\">"
                    + "        <div class=\"input-group\">"
                    + "            <input type=\"hidden\" name=\"showRuns.local\" class=\"form-control\""
                    + "                   value=\"" + runDto.getScheduleName() + "\">"
                    + "            <input type=\"hidden\" name=\"showLogs.local\" class=\"form-control\""
                    + "                   value=\"" + runDto.getRunId() + "\">"
                    + "            <span class=\"input-group-btn show-logs\">"
                    + "                <button class=\"btn btn-primary\" type=\"submit\">logs</button>"
                    + "            </span>"
                    + "        </div>"
                    + "    </form>"
                    + "</td>");
        }
        else {
            // E-> We have some run logs to render, so we should render these instead of the show logs button
            out.write("<td>"
                    + "<div class=\"log-content\">"
                    + "<ul>");
            for (MonitorHistoricRunLogEntryDto logEntry : runDto.getLogEntries()) {
                    out.write("<li>"
                    + "    <div class=\"log-message-and-time\">"
                    + "        <span class=\"log-time\">" + logEntry.getLogTime() + "</span>"
                    + "        <span class=\"log-message\">" + logEntry.getMessage() + "</span>"
                    + "    </div>"
                    + "    <div class=\"error-content log-modal-header\">"
                    + "        <div class=\"content-message error\">" + logEntry.getStackTraceFirstLine() + "</div>"
                    + "    </div>"
                    + "    <div class=\"log-modal-box hide-modal\">"
                    + "        <div class=\"log-modal-content\">"
                    + "            <span class=\"log-modal-content-close\">&times;</span>"
                    + "            <p>" + logEntry.getStackTraceAsHtml() + "</p>"
                    + "        </div>"
                    + "    </div>"
                    + "</li>");
            }
                    out.write("</ul>"
                    + "</div>"
                    + "</td>");
        }
        out.write("</tr>");
    }

    // ===== Actions ===================================================================================

    /**
     * Toggles the {@link ScheduledTask#isActive()} flag for one schedule. Setting this to false will temporarily
     * disable the execution of the supplied runnable for the schedule.
     */
    public void toggleActive(String scheduleName) {
        _scheduledTaskRegistry.getScheduledTasks().computeIfPresent(scheduleName, (ignored, scheduled) -> {
            // Toggle the state
            if (scheduled.isActive()) {
                scheduled.stop();
            }
            else {
                scheduled.start();
            }
            return scheduled;
        });
    }

    /**
     * Will trigger a schedule run for a schedule. Note if this is triggered on a node that is not Active
     * it can take up to 2 min before it will trigger due to it has to notify the Active node to run it now
     * via the db and the Active node check the db every 2 min.
     */
    public void triggerSchedule(String scheduleName) {
        _scheduledTaskRegistry.getScheduledTasks().computeIfPresent(scheduleName, (ignored, scheduled) -> {
            scheduled.runNow();
            return scheduled;
        });
    }

    /**
     * Sets a new cron expression for a schedule.
     * To reset it to the default schedule set this to <b>null</b> or an empty string.
     */
    public void changeChronSchedule(String scheduleName, String cronExpression) {
        String newCron;
        // ?: Is the cronExpression set and does it have a value? If it does it mean we should expect it to be
        // a valid cronExpression.
        if (cronExpression != null && !cronExpression.trim().isEmpty()) {
            // -> Yes, this should be a valid cronExpression, and we should use this as an override.
            newCron = cronExpression.trim();
        }
        else {
            // E-> No, the cronExpression is either null or empty, meaning we should remove any override
            // cronExpressions and use the default one.
            newCron = null;
        }
        _scheduledTaskRegistry.getScheduledTasks().computeIfPresent(scheduleName, (ignored, scheduled) -> {
            scheduled.setOverrideExpression(newCron);
            return scheduled;
        });
    }

    // ===== DTOs ===================================================================================
    public static class MonitorScheduleDto {
        private final String schedulerName;
        private boolean active;
        private Instant lastRunStarted;
        private Instant lastRunComplete;
        private String activeCronExpression;
        private final String defaultCronExpression;
        private Instant nextExpectedRun;
        private final int maxExpectedMinutes;
        private Boolean overdue;
        private Boolean running;
        public State lastRunStatus;


        MonitorScheduleDto(ScheduledTask scheduled) {
            this.schedulerName = scheduled.getName();
            this.active = scheduled.isActive();
            this.lastRunStarted = scheduled.getLastRunStarted();
            this.lastRunComplete = scheduled.getLastRunCompleted();
            this.activeCronExpression = scheduled.getActiveCronExpression();
            this.defaultCronExpression = scheduled.getDefaultCronExpression();
            this.nextExpectedRun = scheduled.getNextRun();
            this.maxExpectedMinutes = scheduled.getMaxExpectedMinutesToRun();
            this.overdue = scheduled.isOverdue();
            this.running = scheduled.isRunning();
        }

        public String getSchedulerName() {
            return escapeHtml(schedulerName);
        }

        public String isActive() {
            return active ? "✅" : "❌";
        }

        public String getLastRunStarted() {
            if (lastRunStarted == null) {
                return "";
            }

            LocalDateTime dateTime = LocalDateTime.ofInstant(lastRunStarted, ZoneId.systemDefault());
            return dateTime.format(DATE_TIME_FORMATTER);
        }

        public String getLastRunComplete() {
            // ?: do we have lastRunComplete and LastRunStarted values set and are the lastRunStart before the lastRunComplete?
            if (lastRunComplete == null || lastRunStarted == null || lastRunStarted.isAfter(lastRunComplete)) {
                // -> Yes. lastRunComplete and/or lastRunStarted is empty or the lastRunStarted is after lastRunComplete.
                // regardless we have not a valid lastRunComplete time yet.
                return "";
            }

            // Both lastRunCompleted and lastRunStarted has a valid instant, and the lastRunStarted is
            // before lastRunComplete
            LocalDateTime dateTime = LocalDateTime.ofInstant(lastRunComplete, ZoneId.systemDefault());
            return dateTime.format(DATE_TIME_FORMATTER);
        }

        public String getActiveCronExpression() {
            return activeCronExpression;
        }

        public String getDefaultCronExpression() {
            return defaultCronExpression;
        }

        public String getLastRunInHHMMSS() {
            // ?: Did the last run complete after current run started?
            if (lastRunStarted != null && lastRunComplete != null
                    && lastRunStarted.isBefore(lastRunComplete)) {
                // -> Yes, we got a valid duration
                long runDurationInSeconds = Duration.between(lastRunStarted, lastRunComplete).getSeconds();
                long hours = runDurationInSeconds / 3600;
                long minutes = (runDurationInSeconds % 3600) / 60;
                long seconds = runDurationInSeconds % 60;
                return String.format("%02d:%02d:%02d", hours, minutes, seconds);
            }

            // E-> No, the current run started after the last run, so we can't give a valid duration
            return "";
        }

        public String getNextExpectedRun() {
            if (nextExpectedRun == null) {
                return "";
            }

            LocalDateTime dateTime = LocalDateTime.ofInstant(nextExpectedRun, ZoneId.systemDefault());
            return dateTime.format(DATE_TIME_FORMATTER);
        }

        public String getMaxExpectedMinutes() {
            return maxExpectedMinutes + " min";
        }

        public String getRunningAndOverdue() {
            // ?: If we have a value here we are running on an active node
            if (running == null || overdue == null) {
                // -> No, we are not running on an active node, noting to return here.
                return "";
            }

            // ?: Priority, check if we are overdue, IE the schedules are taking logger time than expected
            if (overdue) {
                return "⚠️";
            }

            if (running) {
                return "✅";
            }

            // ----- We are on the active node, but we are not running.
            return "";
        }

        public String getRowStyle() {
            // :: Set the row color, blue if the schedule is inactive, yellow if it is overdue and red if the
            // previous run failed. The inactive has priority.
            if (!active) {
                return "alert alert-info";
            }

            // ?: Where the lastRunStatus = failed? if so we should warn about this
            if (State.FAILED.equals(lastRunStatus)) {
                return "alert alert-danger";
            }

            // ?: should we react on the overdue and running?
            if (overdue != null &&  running != null) {
                // -> Yes, we should react to these flags, this means we are running on the Active
                // node.

                // ?: Is this schedule overdue, ie it is active, is running and is overdue?
                if (active && overdue && running) {
                    // -> Yes, this schedule is overdue, active and running.
                    return "alert alert-warning";
                }
            }

            // All is good.
            return "alert alert-light";

        }
    }

    public static final class MonitorHistoricRunDto {
        private final long runId;
        private final String scheduleName;
        private final String hostname;
        private final State status;
        private final String statusMsg;
        private final String statusStackTrace;
        private final LocalDateTime runStart;
        private final LocalDateTime statusTime;
        private List<MonitorHistoricRunLogEntryDto> logEntries = new ArrayList<>();

        public MonitorHistoricRunDto(long runId, String scheduleName, String hostname, State status, String statusMsg,
                String statusStackTrace, LocalDateTime runStart, LocalDateTime statusTime) {
            this.runId = runId;
            this.scheduleName = scheduleName;
            this.hostname = hostname;
            this.status = status;
            this.statusMsg = statusMsg;
            this.statusStackTrace = statusStackTrace;
            this.runStart = runStart;
            this.statusTime = statusTime;
        }

        public static MonitorHistoricRunDto fromContext(ScheduleRunContext context) {
            return new MonitorHistoricRunDto(context.getRunId(),
                    context.getScheduledName(),
                    context.getHostname(),
                    context.getStatus(),
                    context.getStatusMsg(),
                    context.getStatusStackTrace(),
                    context.getRunStarted(),
                    context.getStatusTime());
        }

        public long getRunId() {
            return runId;
        }

        public String getScheduleName() {
            return scheduleName;
        }

        public String getHostname() {
            return hostname;
        }

        public State getStatus() {
            return status;
        }

        public String getStatusMsg() {
            return statusMsg;
        }

        public boolean hasStatusStackTrace() {
            return statusStackTrace != null;
        }

        public List<String> getStatusStackTraceLines() {
            if (statusStackTrace == null) {
                return new ArrayList<>();
            }

            return Arrays.asList(statusStackTrace.split("\\n\\t|\\n|\\t"));
        }

        public String getStatusStackTraceFirstLine() {
            List<String> lines = getStatusStackTraceLines();
            if (lines.isEmpty()) {
                return "";
            }

            return getStatusStackTraceLines().get(0);
        }

        public String getStatusThrowableAsHtml() {
            return getStatusStackTraceLines().stream()
                    .map(line -> line + "<br>")
                    .collect(Collectors.joining());
        }

        public String getStatusStackTrace() {
            return statusStackTrace != null ? statusStackTrace : "";
        }

        public LocalDateTime getRunStart() {
            return runStart;
        }

        public LocalDateTime getStatusTime() {
            return statusTime;
        }

        @SuppressFBWarnings(value = "EI_EXPOSE_REP",
                justification = "This is a DTO, not critical if we expose it.")
        public List<MonitorHistoricRunLogEntryDto> getLogEntries() {
            return logEntries;
        }

        void setLogEntries(List<MonitorHistoricRunLogEntryDto> dtos) {
            logEntries = dtos;
        }

        public boolean hasLogsThrowable() {
            return logEntries.stream()
                    .anyMatch(MonitorHistoricRunLogEntryDto::hasStackTrace);
        }

        public String getLastLogMessage() {
            if (logEntries.isEmpty()) {
                return "";
            }

            return logEntries.get(logEntries.size() - 1).getMessage();
        }
    }

    public static final class MonitorHistoricRunLogEntryDto {
        private final String _msg;
        private final String _stackTrace;
        private final LocalDateTime _logTime;

        MonitorHistoricRunLogEntryDto(String msg, String stackTrace, LocalDateTime logTime) {
            _msg = msg;
            _stackTrace = stackTrace;
            _logTime = logTime;
        }

        public static MonitorHistoricRunLogEntryDto fromDto(LogEntry dto) {
            return new MonitorHistoricRunLogEntryDto(dto.getMessage(), dto.getStackTrace().orElse(null),
                    dto.getLogTime());
        }

        public String getMessage() {
            return _msg;
        }

        public boolean hasStackTrace() {
            return _stackTrace != null;
        }

        public String getStackTrace() {
            if (_stackTrace == null) {
                return "";
            }
            return _stackTrace;
        }

        public List<String> getStackTraceLines() {
            if (_stackTrace == null) {
                return new ArrayList<>();
            }

            return Arrays.asList(_stackTrace.split("\\n\\t|\\n|\\t"));
        }

        public String getStackTraceFirstLine() {
            List<String> lines = getStackTraceLines();
            if (lines.isEmpty()) {
                return "";
            }

            return getStackTraceLines().get(0);
        }

        public String getStackTraceAsHtml() {
            return getStackTraceLines().stream()
                    .map(line -> escapeHtml(line) + "<br>")
                    .collect(Collectors.joining());
        }

        public LocalDateTime getLogTime() {
            return _logTime;
        }
    }

    /**
     * Utility method for escaping HTML.
     *
     * <p>Copied from https://stackoverflow.com/questions/1265282/what-is-the-recommended-way-to-escape-html-symbols-in-plain-java
     */
    private static String escapeHtml(String s) {
        StringBuilder out = new StringBuilder(Math.max(16, s.length()));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 127 || c == '"' || c == '\'' || c == '<' || c == '>' || c == '&') {
                out.append("&#");
                out.append((int) c);
                out.append(';');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}

