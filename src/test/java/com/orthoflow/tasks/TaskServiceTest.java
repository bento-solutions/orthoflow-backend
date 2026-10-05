package com.orthoflow.tasks;

import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.auth.domain.repository.UserRepository;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.messaging.application.service.StaffNotifier;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.tasks.application.dto.TaskDtos.*;
import com.orthoflow.tasks.application.service.TaskService;
import com.orthoflow.tasks.domain.model.Task;
import com.orthoflow.tasks.infrastructure.TaskJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskServiceTest {

    private final UUID practice = UUID.randomUUID();
    private final UUID me = UUID.randomUUID();
    private final LocalDate today = LocalDate.now(ZoneId.of("Africa/Casablanca"));

    private TaskJpaRepository tasks;
    private CurrentUserProvider currentUser;
    private StaffNotifier notifier;
    private TaskService service;

    @BeforeEach
    void setUp() {
        tasks = mock(TaskJpaRepository.class);
        currentUser = mock(CurrentUserProvider.class);
        notifier = mock(StaffNotifier.class);
        UserRepository users = mock(UserRepository.class);
        when(users.findAll()).thenReturn(List.of());
        PatientLookup patients = mock(PatientLookup.class);
        when(patients.findSummaries(any())).thenReturn(java.util.Map.of());
        when(currentUser.requireRole()).thenReturn("ASSISTANT");
        service = new TaskService(tasks, users, patients, notifier, id -> ZoneId.of("Africa/Casablanca"), mock(LiveEventPublisher.class), currentUser);
    }

    private Task task(LocalDate due) {
        return Task.builder().id(UUID.randomUUID()).practiceId(practice).title("t").dueDate(due).assigneeRole(UserRole.ASSISTANT).build();
    }

    @Test
    void myTasksAreSplitIntoLateTodayAndComing() {
        Task late = task(today.minusDays(2));
        Task now = task(today);
        Task later = task(today.plusDays(9));
        Task someday = task(null);
        when(tasks.openFor(practice, me, UserRole.ASSISTANT)).thenReturn(List.of(late, now, later, someday));
        when(tasks.doneSince(any(), any(), any(), any())).thenReturn(List.of());

        Mine mine = service.mine(practice, me, UserRole.ASSISTANT);

        assertThat(mine.overdue()).hasSize(1);
        assertThat(mine.today()).hasSize(1);
        assertThat(mine.upcoming()).hasSize(2);
    }

    @Test
    void theCounterIsOpenLateAndDueToday() {
        when(tasks.openFor(practice, me, UserRole.ASSISTANT)).thenReturn(List.of(task(today.minusDays(1)), task(today), task(today.plusDays(3)), task(null)));

        Count count = service.count(practice, me, UserRole.ASSISTANT);

        assertThat(count.open()).isEqualTo(4);
        assertThat(count.overdue()).isEqualTo(1);
        assertThat(count.dueToday()).isEqualTo(1);
    }

    @Test
    void aTaskGivenToMyRoleIsMineToTickOff() {
        Task t = task(today);
        when(tasks.findByIdAndPracticeId(t.getId(), practice)).thenReturn(Optional.of(t));

        service.done(practice, me, t.getId());

        assertThat(t.getStatus()).isEqualTo(Task.Status.DONE);
        assertThat(t.getDoneBy()).isEqualTo(me);
        assertThat(t.getDoneAt()).isNotNull();
    }

    @Test
    void someoneElsesTaskIsNotFoundToMeUnlessIAmATaskAdmin() {
        Task theirs = Task.builder().id(UUID.randomUUID()).practiceId(practice).title("t").assigneeId(UUID.randomUUID()).createdBy(UUID.randomUUID()).build();
        when(tasks.findByIdAndPracticeId(theirs.getId(), practice)).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> service.done(practice, me, theirs.getId())).isInstanceOf(NotFoundException.class);

        when(currentUser.hasAuthority("TASKS_ADMIN")).thenReturn(true);
        service.done(practice, me, theirs.getId());
        assertThat(theirs.getStatus()).isEqualTo(Task.Status.DONE);
    }

    @Test
    void handingATaskToSomeoneElseNotifiesThemButHandingItToYourselfDoesNot() {
        UUID colleague = UUID.randomUUID();
        UserRepository users = mock(UserRepository.class);
        when(users.findById(any())).thenAnswer(inv -> Optional.of(com.orthoflow.auth.domain.model.User.builder().id(inv.getArgument(0)).active(true)
                .practiceId(practice).role(UserRole.ASSISTANT).firstName("A").lastName("B").email("e").passwordHash("x").build()));
        when(users.findAll()).thenReturn(List.of());
        when(tasks.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
        PatientLookup patients = mock(PatientLookup.class);
        when(patients.findSummaries(any())).thenReturn(java.util.Map.of());
        TaskService s = new TaskService(tasks, users, patients, notifier, id -> ZoneId.of("UTC"), mock(LiveEventPublisher.class), currentUser);

        s.create(practice, me, new Request("Appeler le labo", null, colleague, null, today, null, null));
        s.create(practice, me, new Request("Pour moi", null, me, null, today, null, null));
        s.create(practice, me, new Request("Pour l'accueil", null, null, UserRole.ASSISTANT, today, null, null));

        verify(notifier, times(1)).toUser(eq(practice), eq(colleague), any(), any(), any(), any(), any());
        verify(notifier, never()).toUser(eq(practice), eq(me), any(), any(), any(), any(), any());
        verify(notifier, times(1)).toRole(eq(practice), eq(UserRole.ASSISTANT), any(), any(), any(), any(), any());
    }

    @Test
    void aTaskCannotNameBothAPersonAndARole() {
        assertThatThrownBy(() -> service.create(practice, me, new Request("x", null, UUID.randomUUID(), UserRole.ASSISTANT, null, null, null)))
                .isInstanceOf(com.orthoflow.common.exception.ValidationException.class);
    }
}
