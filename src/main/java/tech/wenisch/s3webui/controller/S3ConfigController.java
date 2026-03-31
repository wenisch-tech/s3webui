package tech.wenisch.s3webui.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService;

@RestController
@RequestMapping("/api/s3/config")
@RequiredArgsConstructor
public class S3ConfigController {

    private final S3ConnectionSettingsService s3ConnectionSettingsService;

    @GetMapping("/status")
    public ConfigStatusResponse getStatus() {
        var status = s3ConnectionSettingsService.getStatus();
        return new ConfigStatusResponse(
                status.required(),
                status.accessKeyRequired(),
                status.secretKeyRequired(),
                status.endpointUrlRequired(),
            status.region(),
            status.accessKey(),
            status.secretKey(),
            status.endpointUrl(),
            status.accessKeyLocked(),
            status.secretKeyLocked(),
            status.endpointUrlLocked(),
            status.regionLocked()
        );
    }

    @PostMapping
    public ResponseEntity<Void> saveSessionConfig(@RequestBody ConfigRequest request) {
        s3ConnectionSettingsService.saveSessionSettings(new S3ConnectionSettingsService.SubmittedS3Settings(
                request.accessKey(),
                request.secretKey(),
                request.endpointUrl(),
                request.region()
        ));
        return ResponseEntity.ok().build();
    }

    public record ConfigRequest(String accessKey, String secretKey, String endpointUrl, String region) {
    }

    public record ConfigStatusResponse(
            boolean required,
            boolean accessKeyRequired,
            boolean secretKeyRequired,
            boolean endpointUrlRequired,
            String region,
            String accessKey,
            String secretKey,
            String endpointUrl,
            boolean accessKeyLocked,
            boolean secretKeyLocked,
            boolean endpointUrlLocked,
            boolean regionLocked
    ) {
    }
}
