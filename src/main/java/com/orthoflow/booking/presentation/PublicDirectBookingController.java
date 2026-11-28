package com.orthoflow.booking.presentation;

import com.orthoflow.booking.application.dto.BookingDtos.*;
import com.orthoflow.booking.application.service.BookingService;
import com.orthoflow.booking.application.service.DirectBookingService;
import com.orthoflow.platform.web.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Token-free public endpoints for the Orthoflow patient booking website and global emergency panel.
 * Protected by CORS, per-IP rate limiting ({@code PublicSecurityConfig}), and CAPTCHA on booking submission.
 */
@RestController
@RequestMapping("/public")
@RequiredArgsConstructor
public class PublicDirectBookingController {

    private final BookingService service;
    private final DirectBookingService directBookingService;

    @GetMapping("/config")
    public PublicConfig config() {
        return service.publicConfig();
    }

    @GetMapping("/booking/info")
    public PublicInfo info() {
        return service.directInfo();
    }

    @GetMapping("/booking/availability")
    public Availability availability(@RequestParam(required = false) UUID typeId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(defaultValue = "14") int days,
                                     @RequestParam(required = false) Integer durationMinutes) {
        return service.directAvailability(typeId, from, days, durationMinutes);
    }

    @PostMapping("/booking/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    public DirectBookResponse book(@Valid @RequestBody DirectBookRequest request,
                                   @RequestHeader(value = "X-Booking-Api-Key", required = false) String apiKey,
                                   HttpServletRequest httpRequest) {
        return directBookingService.bookForConfiguredDoctor(request, apiKey, ClientAddress.of(httpRequest));
    }
}
