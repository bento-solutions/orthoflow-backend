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
 * The online booking page's API. Unauthenticated by design (the public security
 * chain, behind a per-IP throttle): the token in the path is the only credential,
 * and every endpoint re-checks it. Nothing here returns patient data.
 */
@RestController
@RequestMapping("/public/book/{token}")
@RequiredArgsConstructor
public class PublicBookingController {

    private final BookingService service;
    private final DirectBookingService directBookingService;

    @GetMapping
    public PublicInfo info(@PathVariable String token) {
        return service.info(token);
    }

    @GetMapping("/availability")
    public Availability availability(@PathVariable String token, @RequestParam UUID typeId,
                                     @RequestParam(required = false) UUID practitionerId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(defaultValue = "7") int days) {
        return service.availability(token, typeId, practitionerId, from, days);
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Received submit(@PathVariable String token, @Valid @RequestBody Submit request) {
        return service.submit(token, request);
    }

    @PostMapping("/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    public DirectBookResponse book(@PathVariable String token,
                                   @Valid @RequestBody DirectBookRequest request,
                                   @RequestHeader(value = "X-Booking-Api-Key", required = false) String apiKey,
                                   HttpServletRequest httpRequest) {
        return directBookingService.bookWithToken(token, request, apiKey, ClientAddress.of(httpRequest));
    }
}
