/*
 * This file is part of NavalPlan
 *
 * Copyright (C) 2009-2010 Fundación para o Fomento da Calidade Industrial e
 *                         Desenvolvemento Tecnolóxico de Galicia
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.navalplanner.business.planner.entities;

import static org.navalplanner.business.workingday.EffortDuration.hours;
import static org.navalplanner.business.workingday.EffortDuration.zero;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang.Validate;
import org.hibernate.validator.Min;
import org.hibernate.validator.NotNull;
import org.joda.time.LocalDate;
import org.navalplanner.business.calendars.entities.AvailabilityTimeLine;
import org.navalplanner.business.calendars.entities.BaseCalendar;
import org.navalplanner.business.calendars.entities.CombinedWorkHours;
import org.navalplanner.business.calendars.entities.IWorkHours;
import org.navalplanner.business.calendars.entities.SameWorkHoursEveryDay;
import org.navalplanner.business.calendars.entities.ThereAreHoursOnWorkHoursCalculator;
import org.navalplanner.business.common.BaseEntity;
import org.navalplanner.business.common.Registry;
import org.navalplanner.business.planner.entities.DerivedAllocationGenerator.IWorkerFinder;
import org.navalplanner.business.planner.entities.allocationalgorithms.AllocatorForSpecifiedResourcesPerDayAndHours;
import org.navalplanner.business.planner.entities.allocationalgorithms.AllocatorForTaskDurationAndSpecifiedResourcesPerDay;
import org.navalplanner.business.planner.entities.allocationalgorithms.HoursModification;
import org.navalplanner.business.planner.entities.allocationalgorithms.ResourcesPerDayModification;
import org.navalplanner.business.planner.limiting.entities.LimitingResourceQueueElement;
import org.navalplanner.business.resources.daos.IResourceDAO;
import org.navalplanner.business.resources.entities.Machine;
import org.navalplanner.business.resources.entities.MachineWorkersConfigurationUnit;
import org.navalplanner.business.resources.entities.Resource;
import org.navalplanner.business.scenarios.IScenarioManager;
import org.navalplanner.business.scenarios.entities.Scenario;
import org.navalplanner.business.util.deepcopy.OnCopy;
import org.navalplanner.business.util.deepcopy.Strategy;
import org.navalplanner.business.workingday.EffortDuration;
import org.navalplanner.business.workingday.ResourcesPerDay;

/**
 * Resources are allocated to planner tasks.
 * @author Manuel Rego Casasnovas <mrego@igalia.com>
 */
public abstract class ResourceAllocation<T extends DayAssignment> extends
        BaseEntity {

    public static <T extends ResourceAllocation<?>> List<T> getSatisfied(
            Collection<T> resourceAllocations) {
        List<T> result = new ArrayList<T>();
        for (T each : resourceAllocations) {
            if (each.isSatisfied()) {
                result.add(each);
            }
        }
        return result;
    }

    public static <T extends ResourceAllocation<?>> List<T> getOfType(
            Class<T> type,
            Collection<? extends ResourceAllocation<?>> resourceAllocations) {
        List<T> result = new ArrayList<T>();
        for (ResourceAllocation<?> allocation : resourceAllocations) {
            if (type.isInstance(allocation)) {
                result.add(type.cast(allocation));
            }
        }
        return result;
    }

    public static <R extends ResourceAllocation<?>> List<R> sortedByStartDate(
            Collection<R> allocations) {
        List<R> result = new ArrayList<R>(allocations);
        Collections.sort(result, byStartDateComparator());
        return result;
    }

    public static Map<Task, List<ResourceAllocation<?>>> byTask(
            List<ResourceAllocation<?>> allocations) {
        Map<Task, List<ResourceAllocation<?>>> result = new HashMap<Task, List<ResourceAllocation<?>>>();
        for (ResourceAllocation<?> resourceAllocation : allocations) {
            if (resourceAllocation.getTask() != null) {
                Task task = resourceAllocation.getTask();
                initializeIfNeeded(result, task);
                result.get(task).add(resourceAllocation);
            }
        }
        return result;
    }

    private static <E extends ResourceAllocation<?>> void initializeIfNeeded(
            Map<Task, List<E>> result, Task task) {
        if (!result.containsKey(task)) {
            result.put(task, new ArrayList<E>());
        }
    }

    private static Comparator<ResourceAllocation<?>> byStartDateComparator() {
        return new Comparator<ResourceAllocation<?>>() {

            @Override
            public int compare(ResourceAllocation<?> o1,
                    ResourceAllocation<?> o2) {
                if (o1.getStartDate() == null) {
                    return -1;
                }
                if (o2.getStartDate() == null) {
                    return 1;
                }
                return o1.getStartDate().compareTo(o2.getStartDate());
            }
        };
    }

    public static AllocationsCurried allocating(
            List<ResourcesPerDayModification> resourceAllocations) {
        return new AllocationsCurried(resourceAllocations);
    }

    public static class AllocationsCurried {

        private final List<ResourcesPerDayModification> allocations;

        private final Task task;

        public AllocationsCurried(
                List<ResourcesPerDayModification> resourceAllocations) {
            Validate.notNull(resourceAllocations);
            Validate.notEmpty(resourceAllocations);
            Validate.noNullElements(resourceAllocations);
            checkNoOneHasNullTask(resourceAllocations);
            checkAllHaveSameTask(resourceAllocations);
            checkNoAllocationWithZeroResourcesPerDay(resourceAllocations);
            this.allocations = resourceAllocations;
            this.task = resourceAllocations.get(0).getBeingModified()
                    .getTask();
        }

        private static void checkNoAllocationWithZeroResourcesPerDay(
                List<ResourcesPerDayModification> allocations) {
            for (ResourcesPerDayModification r : allocations) {
                if (isZero(r.getGoal().getAmount())) {
                    throw new IllegalArgumentException(
                            "all resources per day must be no zero");
                }
            }
        }

        private static boolean isZero(BigDecimal amount) {
            return amount.movePointRight(amount.scale()).intValue() == 0;
        }

        private static void checkNoOneHasNullTask(
                List<ResourcesPerDayModification> allocations) {
            for (ResourcesPerDayModification resourcesPerDayModification : allocations) {
                if (resourcesPerDayModification
                        .getBeingModified().getTask() == null) {
                    throw new IllegalArgumentException(
                            "all allocations must have task");
                }
            }
        }

        private static void checkAllHaveSameTask(
                List<ResourcesPerDayModification> resourceAllocations) {
            Task task = null;
            for (ResourcesPerDayModification r : resourceAllocations) {
                if (task == null) {
                    task = r.getBeingModified().getTask();
                }
                if (!task.equals(r.getBeingModified().getTask())) {
                    throw new IllegalArgumentException(
                            "all allocations must belong to the same task");
                }
            }
        }

        public LocalDate untilAllocating(int hoursToAllocate) {
            AllocatorForSpecifiedResourcesPerDayAndHours allocator = new AllocatorForSpecifiedResourcesPerDayAndHours(
                    task, allocations) {

                @Override
                protected List<DayAssignment> createAssignmentsAtDay(
                        ResourcesPerDayModification allocation, LocalDate day,
                        Integer limit) {
                    return allocation.createAssignmentsAtDay(day, limit);
                }

                @Override
                protected void setNewDataForAllocation(
                        ResourceAllocation<?> allocation,
                        ResourcesPerDay resourcesPerDay,
                        List<DayAssignment> dayAssignments) {
                    allocation.resetGenericAssignmentsTo(dayAssignments);
                    allocation.updateResourcesPerDay();
                }

                @Override
                protected boolean thereAreAvailableHoursFrom(
                        LocalDate start,
                        ResourcesPerDayModification resourcesPerDayModification,
                        int hoursToAllocate) {
                    IWorkHours workHoursPerDay = getWorkHoursPerDay(resourcesPerDayModification);
                    ResourcesPerDay resourcesPerDay = resourcesPerDayModification
                            .getGoal();
                    AvailabilityTimeLine availability = resourcesPerDayModification
                            .getAvailability();
                    availability.invalidUntil(start);
                    return workHoursPerDay.thereAreHoursOn(availability,
                            resourcesPerDay, hoursToAllocate);
                }

                private CombinedWorkHours getWorkHoursPerDay(
                        ResourcesPerDayModification resourcesPerDayModification) {
                    return CombinedWorkHours.minOf(resourcesPerDayModification
                            .getBeingModified().getTaskWorkHours(),
                            resourcesPerDayModification
                                    .getResourcesWorkHoursPerDay());
                }

                @Override
                protected void markUnsatisfied(ResourceAllocation<?> allocation) {
                    allocation.markAsUnsatisfied();
                }
            };
            return allocator.untilAllocating(hoursToAllocate);
        }

        public void allocateOnTaskLength() {
            AllocatorForTaskDurationAndSpecifiedResourcesPerDay allocator = new AllocatorForTaskDurationAndSpecifiedResourcesPerDay(
                    allocations);
            allocator.allocateOnTaskLength();
        }

        public void allocateUntil(LocalDate endExclusive) {
            AllocatorForTaskDurationAndSpecifiedResourcesPerDay allocator = new AllocatorForTaskDurationAndSpecifiedResourcesPerDay(
                    allocations);
            allocator.allocateUntil(endExclusive);
        }
    }

    public static HoursAllocationCurried allocatingHours(
            List<HoursModification> hoursModifications) {
        return new HoursAllocationCurried(hoursModifications);
    }

    public static class HoursAllocationCurried {

        private final List<HoursModification> hoursModifications;

        private Task task;

        public HoursAllocationCurried(List<HoursModification> hoursModifications) {
            Validate.noNullElements(hoursModifications);
            Validate.isTrue(!hoursModifications.isEmpty());
            this.hoursModifications = hoursModifications;
            this.task = hoursModifications.get(0).getBeingModified().getTask();
            Validate.notNull(task);
        }

        public void allocate() {
            allocateUntil(new LocalDate(task.getEndDate()));
        }

        public void allocateUntil(LocalDate end) {
            Validate.notNull(end);
            Validate.isTrue(!end.isBefore(new LocalDate(task.getStartDate())));
            for (HoursModification each : hoursModifications) {
                each.allocateUntil(end);
            }
        }

    }

    private Task task;

    private AssignmentFunction assignmentFunction;

    @OnCopy(Strategy.SHARE)
    private ResourcesPerDay resourcesPerDay;

    private Integer intendedTotalHours;

    private Set<DerivedAllocation> derivedAllocations = new HashSet<DerivedAllocation>();

    @OnCopy(Strategy.SHARE_COLLECTION_ELEMENTS)
    private Set<LimitingResourceQueueElement> limitingResourceQueueElements = new HashSet<LimitingResourceQueueElement>();

    private int originalTotalAssignment = 0;

    private IOnDayAssignmentRemoval dayAssignmenteRemoval = new DoNothing();

    public interface IOnDayAssignmentRemoval {

        public void onRemoval(ResourceAllocation<?> allocation,
                DayAssignment assignment);
    }

    public static class DoNothing implements IOnDayAssignmentRemoval {

        @Override
        public void onRemoval(
                ResourceAllocation<?> allocation, DayAssignment assignment) {
        }
    }

    public static class DetachDayAssignmentOnRemoval implements
            IOnDayAssignmentRemoval {

        @Override
        public void onRemoval(ResourceAllocation<?> allocation,
                DayAssignment assignment) {
            assignment.detach();
        }
    }

    public void setOnDayAssignmentRemoval(
            IOnDayAssignmentRemoval dayAssignmentRemoval) {
        Validate.notNull(dayAssignmentRemoval);
        this.dayAssignmenteRemoval = dayAssignmentRemoval;
    }

    /**
     * Constructor for hibernate. Do not use!
     */
    public ResourceAllocation() {

    }

    /**
     * Returns the associated resources from the day assignments of this
     * {@link ResourceAllocation}.
     * @return the associated resources with no repeated elements
     */
    public abstract List<Resource> getAssociatedResources();

    @OnCopy(Strategy.IGNORE)
    private Scenario currentScenario;

    public void switchToScenario(Scenario scenario) {
        Validate.notNull(scenario);
        currentScenario = scenario;
        scenarioChangedTo(currentScenario);
        switchDerivedAllocationsTo(scenario);
    }

    protected abstract void scenarioChangedTo(Scenario scenario);

    private void switchDerivedAllocationsTo(Scenario scenario) {
        for (DerivedAllocation each : derivedAllocations) {
            each.useScenario(scenario);
        }
    }

    protected void updateResourcesPerDay() {
        ResourcesPerDay resourcesPerDay = calculateResourcesPerDayFromAssignments(getAssignments());
        if (resourcesPerDay == null) {
            this.resourcesPerDay = ResourcesPerDay.amount(0);
        } else {
            this.resourcesPerDay = resourcesPerDay;
        }
    }

    protected void setResourcesPerDayToAmount(int amount) {
        this.resourcesPerDay = ResourcesPerDay.amount(amount);
    }

    public ResourceAllocation(Task task) {
        this(task, null);
    }

    public ResourceAllocation(Task task, AssignmentFunction assignmentFunction) {
        Validate.notNull(task);
        this.task = task;
        this.assignmentFunction = assignmentFunction;
    }

    protected ResourceAllocation(ResourcesPerDay resourcesPerDay, Task task) {
        this(task);
        Validate.notNull(resourcesPerDay);
        this.resourcesPerDay = resourcesPerDay;
    }

    @NotNull
    public Task getTask() {
        return task;
    }

    private void updateOriginalTotalAssigment() {
        if ((task.getConsolidation() == null)
                || (task.getConsolidation().getConsolidatedValues().isEmpty())) {
            originalTotalAssignment = getNonConsolidatedHours();
        } else {
            BigDecimal lastConslidation = task.getConsolidation()
                    .getConsolidatedValues().last().getValue();
            BigDecimal unconsolitedPercentage = BigDecimal.ONE
                    .subtract(lastConslidation.setScale(2).divide(
                            new BigDecimal(100), RoundingMode.DOWN));
            if (unconsolitedPercentage.setScale(2).equals(
                    BigDecimal.ZERO.setScale(2))) {
                originalTotalAssignment = getConsolidatedHours();
            } else {
                originalTotalAssignment = new BigDecimal(
                        getNonConsolidatedHours()).divide(
                        unconsolitedPercentage, RoundingMode.DOWN).intValue();
            }
        }
    }

    @Min(0)
    public int getOriginalTotalAssigment() {
        return originalTotalAssignment;
    }

    public abstract ResourcesPerDayModification withDesiredResourcesPerDay(
            ResourcesPerDay resourcesPerDay);

    public abstract ResourcesPerDayModification asResourcesPerDayModification();

    public abstract HoursModification asHoursModification();

    public abstract IAllocatable withPreviousAssociatedResources();

    protected abstract class AssignmentsAllocation implements IAllocatable {

        @Override
        public final void allocate(ResourcesPerDay resourcesPerDay) {
            Task currentTask = getTask();
            LocalDate startInclusive = new LocalDate(currentTask.getStartDate());
            LocalDate endExclusive = new LocalDate(currentTask.getEndDate());
            List<T> assignmentsCreated = createAssignments(resourcesPerDay,
                    startInclusive, endExclusive);
            resetAssignmentsTo(assignmentsCreated);
            updateResourcesPerDay();
        }

        private List<T> createAssignments(ResourcesPerDay resourcesPerDay,
                LocalDate startInclusive, LocalDate endExclusive) {
            List<T> assignmentsCreated = new ArrayList<T>();
            for (LocalDate day : getDays(startInclusive, endExclusive)) {
                int totalForDay = calculateTotalToDistribute(day,
                        resourcesPerDay);
                assignmentsCreated.addAll(distributeForDay(day, totalForDay));
            }
            return assignmentsCreated;
        }

        @Override
        public IAllocateResourcesPerDay until(final LocalDate endExclusive) {
            return new IAllocateResourcesPerDay() {

                @Override
                public void allocate(ResourcesPerDay resourcesPerDay) {
                    Task currentTask = getTask();
                    LocalDate taskStart = LocalDate.fromDateFields(currentTask
                            .getStartDate());
                    LocalDate startInclusive = (currentTask
                            .getFirstDayNotConsolidated().compareTo(taskStart) >= 0) ? currentTask
                            .getFirstDayNotConsolidated()
                            : taskStart;
                    List<T> assignmentsCreated = createAssignments(
                            resourcesPerDay, startInclusive, endExclusive);
                    resetAssignmentsTo(assignmentsCreated);
                    updateResourcesPerDay();
                }

            };
        }

        private List<LocalDate> getDays(LocalDate startInclusive,
                LocalDate endExclusive) {
            Validate.notNull(startInclusive);
            Validate.notNull(endExclusive);
            Validate.isTrue(startInclusive.compareTo(endExclusive) <= 0,
                    "the end must be equal or posterior than start");
            List<LocalDate> result = new ArrayList<LocalDate>();
            LocalDate current = startInclusive;
            while (current.compareTo(endExclusive) < 0) {
                result.add(current);
                current = current.plusDays(1);
            }
            return result;
        }

        private class AllocateHoursOnInterval implements
                IAllocateHoursOnInterval {

            private final LocalDate start;
            private final LocalDate end;

            AllocateHoursOnInterval(LocalDate start, LocalDate end) {
                Validate.isTrue(start.compareTo(end) <= 0,
                        "the end must be equal or posterior than start");
                this.start = start;
                this.end = end;
            }

            public void allocateHours(int hours) {
                allocate(start, end, hours);
            }
        }

        @Override
        public IAllocateHoursOnInterval onInterval(LocalDate start,
                LocalDate end) {
            return new AllocateHoursOnInterval(start, end);
        }

        @Override
        public IAllocateHoursOnInterval fromStartUntil(final LocalDate end) {
            return new IAllocateHoursOnInterval() {

                @Override
                public void allocateHours(int hours) {
                    allocate(end, hours);
                }
            };
        }

        private void allocate(LocalDate end, int hours) {
            LocalDate taskStart = LocalDate.fromDateFields(task.getStartDate());
            LocalDate startInclusive = (task.getFirstDayNotConsolidated()
                    .compareTo(taskStart) >= 0) ? task
                    .getFirstDayNotConsolidated() : taskStart;
            List<T> assignmentsCreated = createAssignments(startInclusive, end,
                    hours);
            resetAssignmentsTo(assignmentsCreated);
            updateResourcesPerDay();
        }

        private void allocate(LocalDate startInclusive, LocalDate endExclusive,
                int hours) {
            LocalDate firstDayNotConsolidated = getTask().getFirstDayNotConsolidated();
            LocalDate start = startInclusive.compareTo(firstDayNotConsolidated) >= 0 ? startInclusive
            : firstDayNotConsolidated;
            List<T> assignmentsCreated = createAssignments(startInclusive,
                    endExclusive, hours);
            resetAssigmentsForInterval(start, endExclusive, assignmentsCreated);
        }

        protected abstract AvailabilityTimeLine getResourcesAvailability();

        private List<T> createAssignments(LocalDate startInclusive,
                LocalDate endExclusive, int hours) {
            Validate.isTrue(hours >= 0);
            List<T> assignmentsCreated = new ArrayList<T>();
            if (hours > 0) {
                AvailabilityTimeLine availability = getAvailability();

                List<LocalDate> days = getDays(startInclusive, endExclusive);
                int[] hoursEachDay = hoursDistribution(availability, days,
                        hours);
                int i = 0;
                for (LocalDate day : days) {
                    // if all days are not available, it would try to assign
                    // them anyway, preventing it with a check
                    if (availability.isValid(day)) {
                        assignmentsCreated.addAll(distributeForDay(day,
                                hoursEachDay[i]));
                    }
                    i++;
                }
            }
            return onlyNonZeroHours(assignmentsCreated);
        }

        private AvailabilityTimeLine getAvailability() {
            AvailabilityTimeLine resourcesAvailability = getResourcesAvailability();
            if (getTaskCalendar() != null) {
                return getTaskCalendar().getAvailability().and(
                        resourcesAvailability);
            } else {
                return resourcesAvailability;
            }
        }

        private List<T> onlyNonZeroHours(List<T> assignmentsCreated) {
            List<T> result = new ArrayList<T>();
            for (T each : assignmentsCreated) {
                if (each.getHours() > 0) {
                    result.add(each);
                }
            }
            return result;
        }

        private int[] hoursDistribution(AvailabilityTimeLine availability,
                List<LocalDate> days, int hoursToSum) {
            List<Share> shares = new ArrayList<Share>();
            for (LocalDate day : days) {
                shares.add(getShareAt(day, availability));
            }
            ShareDivision original = ShareDivision.create(shares);
            ShareDivision newShare = original.plus(hoursToSum);
            return original.to(newShare);
        }

        private Share getShareAt(LocalDate day,
                AvailabilityTimeLine availability) {
            if (availability.isValid(day)) {
                Integer capacityAtDay = getWorkHoursPerDay()
                        .getCapacityAt(day);
                return new Share(-capacityAtDay);
            } else {
                return new Share(Integer.MAX_VALUE);
            }
        }

        protected abstract List<T> distributeForDay(LocalDate day,
                int totalHours);

    }

    private void markAsUnsatisfied() {
        removingAssignments(getAssignments());
        assert isUnsatisfied();
    }

    public boolean isLimiting() {
        return getLimitingResourceQueueElement() != null;
    }

    public boolean isLimitingAndHasDayAssignments() {
        return isLimiting() && hasAssignments();
    }

    public boolean isSatisfied() {
        return hasAssignments();
    }

    public boolean isUnsatisfied() {
        return !isSatisfied();
    }

    public void copyAssignmentsFromOneScenarioToAnother(Scenario from, Scenario to){
        copyAssignments(from, to);
        for (DerivedAllocation each : derivedAllocations) {
            each.copyAssignments(from, to);
        }
    }

    protected abstract void copyAssignments(Scenario from, Scenario to);

    protected void resetAssignmentsTo(List<T> assignments) {
        removingAssignments((List<? extends DayAssignment>) removeConsolidated(getAssignments()));
        addingAssignments(assignments);
        updateOriginalTotalAssigment();
    }

    protected void resetAssigmentsForInterval(LocalDate startInclusive,
            LocalDate endExclusive, List<T> assignmentsCreated) {
        removingAssignments(removeConsolidated(getAssignments(startInclusive,
                endExclusive)));
        addingAssignments(assignmentsCreated);
        updateOriginalTotalAssigment();
        updateResourcesPerDay();
    }

    private List<? extends DayAssignment> removeConsolidated(
            List<? extends DayAssignment> assignments) {
        for (Iterator<? extends DayAssignment> iterator = assignments
                .iterator(); iterator.hasNext();) {
            DayAssignment dayAssignment = (DayAssignment) iterator.next();
            if (dayAssignment.isConsolidated()) {
                iterator.remove();
            }
        }
        return assignments;
    }

    protected final void addingAssignments(Collection<? extends T> assignments) {
        getDayAssignmentsState().addingAssignments(assignments);
    }

    public void removeLimitingDayAssignments() {
        allocateLimitingDayAssignments(Collections.<T>emptyList());
    }

    @SuppressWarnings("unchecked")
    public void allocateLimitingDayAssignments(List<? extends DayAssignment> assignments) {
        assert isLimiting();
        resetAssignmentsTo((List<T>) assignments);
    }

    private void removingAssignments(
            List<? extends DayAssignment> assignments) {
        getDayAssignmentsState().removingAssignments(assignments);
    }

    final int calculateTotalToDistribute(LocalDate day,
            ResourcesPerDay resourcesPerDay) {
        return getWorkHoursPerDay().toHours(day, resourcesPerDay);
    }

    public ResourcesPerDay calculateResourcesPerDayFromAssignments() {
        return calculateResourcesPerDayFromAssignments(getAssignments());
    }

    private ResourcesPerDay calculateResourcesPerDayFromAssignments(
            Collection<? extends T> assignments) {
        Map<LocalDate, List<T>> byDay = DayAssignment.byDay(assignments);
        EffortDuration sumTotalEffort = zero();
        EffortDuration sumWorkableEffort = zero();
        final ResourcesPerDay one = ResourcesPerDay.amount(1);
        for (Entry<LocalDate, List<T>> entry : byDay.entrySet()) {
            sumWorkableEffort = sumWorkableEffort
                    .plus(hours(getWorkHoursPerDay().toHours(entry.getKey(),
                            one)));
            sumTotalEffort = sumTotalEffort.plus(getAssignedDuration(entry
                    .getValue()));
        }
        if (sumWorkableEffort.equals(zero())) {
            return ResourcesPerDay.amount(0);
        }
        return ResourcesPerDay.calculateFrom(sumTotalEffort, sumWorkableEffort);
    }

    private IWorkHours getWorkHoursPerDay() {
        return getWorkHoursGivenTaskHours(getTaskWorkHours());
    }

    private IWorkHours getTaskWorkHours() {
        return new IWorkHours() {
            @Override
            public Integer getCapacityAt(LocalDate day) {
                return getSubyacent().getCapacityAt(day);
            }

            @Override
            public EffortDuration getCapacityDurationAt(LocalDate date) {
                return getSubyacent().getCapacityDurationAt(date);
            }

            private IWorkHours getSubyacent() {
                if (getTaskCalendar() == null) {
                    return SameWorkHoursEveryDay.getDefaultWorkingDay();
                } else {
                    return getTaskCalendar();
                }
            }

            @Override
            public Integer toHours(LocalDate day, ResourcesPerDay amount) {
                return getSubyacent().toHours(day, amount);
            }

            @Override
            public boolean thereAreHoursOn(AvailabilityTimeLine availability,
                    ResourcesPerDay resourcesPerDay, int hoursToAllocate) {
                return ThereAreHoursOnWorkHoursCalculator.thereAreHoursOn(this,
                        availability, resourcesPerDay,
                        hoursToAllocate);
            }

            @Override
            public AvailabilityTimeLine getAvailability() {
                return getSubyacent().getAvailability();
            }
        };
    }

    protected abstract IWorkHours getWorkHoursGivenTaskHours(
            IWorkHours taskWorkHours);

    protected final BaseCalendar getTaskCalendar() {
        return getTask().getCalendar();
    }

    private void resetGenericAssignmentsTo(List<DayAssignment> assignments) {
        resetAssignmentsTo(cast(assignments));
    }

    private List<T> cast(List<DayAssignment> value) {
        List<T> result = new ArrayList<T>();
        for (DayAssignment dayAssignment : value) {
            result.add(getDayAssignmentType().cast(dayAssignment));
        }
        return result;
    }

    protected abstract Class<T> getDayAssignmentType();

    public ResourceAllocation<T> copy(Scenario scenario) {
        Validate.notNull(scenario);
        ResourceAllocation<T> copy = createCopy(scenario);
        copy.resourcesPerDay = resourcesPerDay;
        copy.originalTotalAssignment = originalTotalAssignment;
        copy.task = task;
        copy.assignmentFunction = assignmentFunction;
        return copy;
    }

    abstract ResourceAllocation<T> createCopy(Scenario scenario);

    public AssignmentFunction getAssignmentFunction() {
        return assignmentFunction;
    }

    public void setAssignmentFunction(AssignmentFunction assignmentFunction) {
        this.assignmentFunction = assignmentFunction;
        if (this.assignmentFunction != null) {
            this.assignmentFunction.applyTo(this);
        }
    }

    private void setWithoutApply(AssignmentFunction assignmentFunction) {
        this.assignmentFunction = assignmentFunction;
    }

    public int getAssignedHours() {
        return DayAssignment.sum(getAssignments()).roundToHours();
    }

    protected abstract class DayAssignmentsState {

        private List<T> dayAssignmentsOrdered = null;

        protected List<T> getOrderedDayAssignments() {
            if (dayAssignmentsOrdered == null) {
                dayAssignmentsOrdered = DayAssignment
                        .orderedByDay(getUnorderedAssignments());
            }
            return dayAssignmentsOrdered;
        }

        protected abstract Collection<T> getUnorderedAssignments();

        protected void addingAssignments(Collection<? extends T> assignments) {
            setParentFor(assignments);
            addAssignments(assignments);
            clearCachedData();
        }

        protected void clearCachedData() {
            dayAssignmentsOrdered = null;
            clearFieldsCalculatedFromAssignments();
        }

        private void setParentFor(Collection<? extends T> assignments) {
            for (T each : assignments) {
                setParentFor(each);
            }
        }

        protected abstract void clearFieldsCalculatedFromAssignments();

        protected abstract void setParentFor(T each);

        protected void removingAssignments(
                List<? extends DayAssignment> assignments){
            removeAssignments(assignments);
            clearCachedData();
            for (DayAssignment each : assignments) {
                dayAssignmenteRemoval.onRemoval(ResourceAllocation.this, each);
            }
        }

        protected abstract void removeAssignments(
                List<? extends DayAssignment> assignments);

        protected abstract void addAssignments(
                Collection<? extends T> assignments);

        @SuppressWarnings("unchecked")
        public void mergeAssignments(ResourceAllocation<?> modification) {
            detachAssignments();
            resetTo(((ResourceAllocation<T>) modification).getAssignments());
            clearCachedData();
        }

        protected abstract void resetTo(Collection<T> assignmentsCopied);

        void detachAssignments() {
            for (DayAssignment each : getUnorderedAssignments()) {
                each.detach();
            }
        }

        protected abstract DayAssignmentsState switchTo(Scenario scenario);
    }

    /**
     * It uses the current scenario retrieved from {@link IScenarioManager} in
     * order to return the assignments for that scenario. This state doesn't
     * allow to update the current assignments for that scenario.<br />
     * Note that this implementation doesn't work well if the current scenario
     * is changed since the assignments are cached and the assignments for the
     * previous one would be returned<br />
     */
    protected abstract class NoExplicitlySpecifiedScenario extends
            DayAssignmentsState {

        @Override
        protected final void removeAssignments(
                List<? extends DayAssignment> assignments) {
            modificationsNotAllowed();
        }

        protected final void setParentFor(T each) {
            modificationsNotAllowed();
        }

        @Override
        protected final void addAssignments(Collection<? extends T> assignments) {
            modificationsNotAllowed();
        }

        @Override
        final void detachAssignments() {
            modificationsNotAllowed();
        }

        @Override
        protected final void resetTo(Collection<T> assignmentsCopied) {
            modificationsNotAllowed();
        }

        @Override
        protected final void clearFieldsCalculatedFromAssignments() {
            modificationsNotAllowed();
        }

        private void modificationsNotAllowed() {
            throw new IllegalStateException(
                    "modifications to assignments can't be done "
                            + "if the scenario on which to work on is not explicitly specified");
        }

        @Override
        protected Collection<T> getUnorderedAssignments() {
            Scenario currentScenario = Registry
                    .getScenarioManager().getCurrent();
            return getUnorderedAssignmentsForScenario(currentScenario);
        }

        protected abstract Collection<T> getUnorderedAssignmentsForScenario(
                Scenario scenario);
    }

    protected abstract DayAssignmentsState getDayAssignmentsState();

    public int getConsolidatedHours() {
        return DayAssignment.sum(getConsolidatedAssignments()).roundToHours();
    }

    public int getNonConsolidatedHours() {
        return DayAssignment.sum(getNonConsolidatedAssignments())
                .roundToHours();
    }

    /**
     * @return a list of {@link DayAssignment} ordered by date
     */
    public final List<T> getAssignments() {
        return getDayAssignmentsState().getOrderedDayAssignments();
    }

    public List<T> getNonConsolidatedAssignments() {
        return getDayAssignmentsByConsolidated(false);
    }

    public List<T> getConsolidatedAssignments() {
        return getDayAssignmentsByConsolidated(true);
    }

    private List<T> getDayAssignmentsByConsolidated(
            boolean consolidated) {
        List<T> result = new ArrayList<T>();
        for (T day : getAssignments()) {
            if (day.isConsolidated() == consolidated) {
                result.add(day);
            }
        }
        return result;
    }

    public ResourcesPerDay getNonConsolidatedResourcePerDay() {
        return calculateResourcesPerDayFromAssignments(getNonConsolidatedAssignments());
    }

    public ResourcesPerDay getConsolidatedResourcePerDay() {
        return calculateResourcesPerDayFromAssignments(getConsolidatedAssignments());
    }

    @NotNull
    public ResourcesPerDay getResourcesPerDay() {
        return resourcesPerDay;
    }

    public void createDerived(IWorkerFinder finder) {
        final List<? extends DayAssignment> assignments = getAssignments();
        List<DerivedAllocation> result = new ArrayList<DerivedAllocation>();
        List<Machine> machines = Resource.machines(getAssociatedResources());
        for (Machine machine : machines) {
            for (MachineWorkersConfigurationUnit each : machine
                    .getConfigurationUnits()) {
                result.add(DerivedAllocationGenerator.generate(this, finder,
                        each,
                        assignments));
            }
        }
        resetDerivedAllocationsTo(result);
    }

    /**
     * Resets the derived allocations
     */
    private void resetDerivedAllocationsTo(
            Collection<DerivedAllocation> derivedAllocations) {
        // avoiding error: A collection with cascade="all-delete-orphan" was no
        // longer referenced by the owning entity instance
        this.derivedAllocations.clear();
        this.derivedAllocations.addAll(derivedAllocations);
    }

    public Set<DerivedAllocation> getDerivedAllocations() {
        return Collections.unmodifiableSet(derivedAllocations);
    }

    public LocalDate getStartConsideringAssignments() {
        List<? extends DayAssignment> assignments = getAssignments();
        if (assignments.isEmpty()) {
            return getStartDate();
        }
        return assignments.get(0).getDay();
    }

    public LocalDate getStartDate() {
        return LocalDate.fromDateFields(task.getStartDate());
    }

    public LocalDate getEndDate() {
        List<? extends DayAssignment> assignments = getAssignments();
        if (assignments.isEmpty()) {
            return null;
        }
        return assignments.get(assignments.size() - 1).getDay().plusDays(1);
    }

    public boolean isAlreadyFinishedBy(LocalDate date) {
        if (getEndDate() == null) {
            return false;
        }
        return getEndDate().compareTo(date) <= 0;
    }

    private interface PredicateOnDayAssignment {
        boolean satisfiedBy(DayAssignment dayAssignment);
    }


    public int getAssignedHours(final Resource resource, LocalDate start,
            LocalDate endExclusive) {
        return getAssignedDuration(filter(getAssignments(start, endExclusive),
                new PredicateOnDayAssignment() {
                            @Override
                            public boolean satisfiedBy(
                                    DayAssignment dayAssignment) {
                                return dayAssignment.isAssignedTo(resource);
                            }
                        })).roundToHours();
    }

    public List<DayAssignment> getAssignments(LocalDate start,
            LocalDate endExclusive) {
        return new ArrayList<DayAssignment>(DayAssignment.getAtInterval(
                getAssignments(), start, endExclusive));
    }

    public int getAssignedHours(LocalDate start, LocalDate endExclusive) {
        return getAssignedDuration(getAssignments(start, endExclusive))
                .roundToHours();
    }

    private List<DayAssignment> filter(List<DayAssignment> assignments,
            PredicateOnDayAssignment predicate) {
        List<DayAssignment> result = new ArrayList<DayAssignment>();
        for (DayAssignment dayAssignment : assignments) {
            if (predicate.satisfiedBy(dayAssignment)) {
                result.add(dayAssignment);
            }
        }
        return result;
    }

    private EffortDuration getAssignedDuration(
            List<? extends DayAssignment> assignments) {
        EffortDuration result = zero();
        for (DayAssignment dayAssignment : assignments) {
            result = result.plus(dayAssignment.getDuration());
        }
        return result;
    }

    public void mergeAssignmentsAndResourcesPerDay(Scenario scenario,
            ResourceAllocation<?> modifications) {
        if (modifications == this) {
            return;
        }
        switchToScenario(scenario);
        mergeAssignments(modifications);
        updateOriginalTotalAssigment();
        updateResourcesPerDay();
        setWithoutApply(modifications.getAssignmentFunction());
        mergeDerivedAllocations(scenario, modifications.getDerivedAllocations());
    }

    private void mergeDerivedAllocations(Scenario scenario,
            Set<DerivedAllocation> derivedAllocations) {
        Map<MachineWorkersConfigurationUnit, DerivedAllocation> newMap = DerivedAllocation
                .byConfigurationUnit(derivedAllocations);
        Map<MachineWorkersConfigurationUnit, DerivedAllocation> currentMap = DerivedAllocation
                .byConfigurationUnit(getDerivedAllocations());
        for (Entry<MachineWorkersConfigurationUnit, DerivedAllocation> entry : newMap
                .entrySet()) {
            final MachineWorkersConfigurationUnit key = entry.getKey();
            final DerivedAllocation modification = entry.getValue();
            DerivedAllocation current = currentMap.get(key);
            if (current == null) {
                DerivedAllocation derived = modification.asDerivedFrom(this);
                derived.useScenario(scenario);
                currentMap.put(key, derived);
            } else {
                current.useScenario(scenario);
                current.resetAssignmentsTo(modification.getAssignments());
            }
        }
        resetDerivedAllocationsTo(currentMap.values());
    }

    final void mergeAssignments(ResourceAllocation<?> modifications) {
        getDayAssignmentsState().mergeAssignments(modifications);
    }

    public void detach() {
        getDayAssignmentsState().detachAssignments();
    }

    void associateAssignmentsToResource() {
        for (DayAssignment dayAssignment : getAssignments()) {
            dayAssignment.associateToResource();
        }
    }

    public boolean hasAssignments() {
        return !getAssignments().isEmpty();
    }

    public LimitingResourceQueueElement getLimitingResourceQueueElement() {
        return (!limitingResourceQueueElements.isEmpty()) ? (LimitingResourceQueueElement) limitingResourceQueueElements.iterator().next() : null;
    }

    public void setLimitingResourceQueueElement(LimitingResourceQueueElement element) {
        limitingResourceQueueElements.clear();
        if (element != null) {
            element.setResourceAllocation(this);
            limitingResourceQueueElements.add(element);
        }
    }

    public Integer getIntendedTotalHours() {
        return intendedTotalHours;
    }

    public void setIntendedTotalHours(Integer intendedTotalHours) {
        this.intendedTotalHours = intendedTotalHours;
    }

    /**
     * Do a query to recover a list of resources that are suitable for this
     * allocation. For a {@link SpecificResourceAllocation} returns the current
     * resource. For a {@link GenericResourceAllocation} returns the resources
     * that currently match this allocation criterions
     * @return a list of resources that are proper for this allocation
     */
    public abstract List<Resource> querySuitableResources(IResourceDAO resourceDAO);

    public abstract void makeAssignmentsContainersDontPoseAsTransientAnyMore();

    public void removePredecessorsDayAssignmentsFor(Scenario scenario) {
        for (DerivedAllocation each : getDerivedAllocations()) {
            each.removePredecessorContainersFor(scenario);
        }
        removePredecessorContainersFor(scenario);
    }

    protected abstract void removePredecessorContainersFor(Scenario scenario);

    public void removeDayAssigmentsFor(Scenario scenario) {
        for (DerivedAllocation each : getDerivedAllocations()) {
            each.removeContainersFor(scenario);
        }
        removeContainersFor(scenario);
    }

    protected abstract void removeContainersFor(Scenario scenario);

}
