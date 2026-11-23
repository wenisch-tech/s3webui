package tech.wenisch.s3webui.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tech.wenisch.s3webui.model.ConflictPolicy;
import tech.wenisch.s3webui.model.MigrationJobView;
import tech.wenisch.s3webui.model.MigrationStatus;
import tech.wenisch.s3webui.service.JobNotFoundException;
import tech.wenisch.s3webui.service.MigrationService;
import tech.wenisch.s3webui.service.MigrationService.SubmitCommand;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MigrationApiControllerTest {

    private final MigrationService service = mock(MigrationService.class);
    private final S3ConnectionSettingsService settings = mock(S3ConnectionSettingsService.class);
    private final MigrationApiController controller = new MigrationApiController(service, settings);
    private final Authentication alice =
            UsernamePasswordAuthenticationToken.authenticated("alice@example.com", "n/a", List.of());

    @BeforeEach
    void signIn() {
        SecurityContextHolder.getContext().setAuthentication(alice);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitUsesTheActiveSessionKeyAsSourceAndAnswersAccepted() {
        when(settings.getActiveCredentialId()).thenReturn("7");
        MigrationJobView view = view("job-1");
        when(service.submit(any(SubmitCommand.class), eq(alice))).thenReturn(view);

        ResponseEntity<MigrationJobView> response = controller.submit(new MigrationApiController.SubmitRequest(
                "src", "docs/", List.of("docs/a.txt"), "9", "dst", "inbox/", "newer_only", true));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(view, response.getBody());
        ArgumentCaptor<SubmitCommand> command = ArgumentCaptor.forClass(SubmitCommand.class);
        verify(service).submit(command.capture(), eq(alice));
        assertEquals(new SubmitCommand("7", "src", "docs/", List.of("docs/a.txt"), "9", "dst", "inbox/",
                ConflictPolicy.NEWER_ONLY, true), command.getValue());
    }

    @Test
    void submitDefaultsToSkippingExistingObjects() {
        controller.submit(new MigrationApiController.SubmitRequest("src", "", List.of(), "9", "dst", "", null, false));

        ArgumentCaptor<SubmitCommand> command = ArgumentCaptor.forClass(SubmitCommand.class);
        verify(service).submit(command.capture(), eq(alice));
        assertEquals(ConflictPolicy.SKIP, command.getValue().conflictPolicy());
    }

    @Test
    void submitRejectsAnUnknownConflictPolicy() {
        assertThrows(IllegalArgumentException.class, () -> controller.submit(
                new MigrationApiController.SubmitRequest("src", "", List.of(), "9", "dst", "", "explode", false)));
    }

    @Test
    void listGetCancelAndDismissDelegateWithTheCurrentUser() {
        when(service.list(alice)).thenReturn(List.of(view("a")));
        when(service.get("a", alice)).thenReturn(view("a"));
        when(service.cancel("a", alice)).thenReturn(view("a"));

        assertEquals(1, controller.list().size());
        assertEquals("a", controller.get("a").id());
        assertEquals("a", controller.cancel("a").id());
        assertEquals(HttpStatus.NO_CONTENT, controller.dismiss("a").getStatusCode());
        verify(service).dismiss("a", alice);
    }

    @Test
    void anUnknownJobSurfacesAsNotFound() {
        when(service.get("nope", alice)).thenThrow(new JobNotFoundException("nope"));

        assertThrows(JobNotFoundException.class, () -> controller.get("nope"));
        assertEquals(404, new ApiExceptionHandler().handleJobNotFound(new JobNotFoundException("nope"))
                .getStatusCode().value());
    }

    @Test
    void targetBrowsingPassesTheCredentialAndPrefix() {
        when(service.listTargetBuckets(alice, "9")).thenReturn(List.of("one", "two"));
        when(service.listTargetFolders(alice, "9", "one", "a/")).thenReturn(List.of("a/b/"));

        assertEquals(List.of("one", "two"), controller.targetBuckets("9"));
        assertEquals(List.of("a/b/"), controller.targetFolders("9", "one", "a/"));
    }

    private static MigrationJobView view(String id) {
        return new MigrationJobView(id, "alice@example.com", MigrationStatus.QUEUED, true, "S", "src", "", List.of(),
                "T", "dst", "", ConflictPolicy.SKIP, false, 0, 0, 0, 0, 0, 0, null, null, List.of(), null, null,
                null, null);
    }
}
