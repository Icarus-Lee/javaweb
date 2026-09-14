package com.javaweb.train.controller;

import com.javaweb.train.model.AuditLog;
import com.javaweb.train.model.Booking;
import com.javaweb.train.repo.BookingRepo;
import com.javaweb.train.service.BookingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    private final BookingService service;
    private final BookingRepo repo;
    private final com.javaweb.train.repo.AuditLogRepo auditRepo;

    public BookingController(BookingService service, BookingRepo repo,
                             com.javaweb.train.repo.AuditLogRepo auditRepo) {
        this.service = service;
        this.repo = repo;
        this.auditRepo = auditRepo;
    }

    public record BookReq(Long tripId) {}
    public record OrderOnlyReq(String orderNo) {}

    /** 下单（需登录；返回 UNPAID 订单）。 */
    @PostMapping
    public Booking book(@RequestBody BookReq req, HttpServletRequest http) {
        Long uid = (Long) http.getAttribute("uid");
        return service.book(uid, req.tripId());
    }

    @PostMapping("/pay")
    public Booking pay(@RequestBody OrderOnlyReq req, HttpServletRequest http) {
        return service.pay((Long) http.getAttribute("uid"), req.orderNo());
    }

    @PostMapping("/cancel")
    public Booking cancel(@RequestBody OrderOnlyReq req, HttpServletRequest http) {
        return service.cancel((Long) http.getAttribute("uid"), req.orderNo());
    }

    @GetMapping("/mine")
    public List<Booking> mine(HttpServletRequest http) {
        return repo.findByUserIdOrderByCreatedAtDesc((Long) http.getAttribute("uid"));
    }

    /** 审计台最近 N 条事件（验证 Kafka 解耦链路活着）。 */
    @GetMapping("/audits")
    public List<AuditLog> audits(@RequestParam(defaultValue = "5") int n) {
        org.springframework.data.domain.Page<AuditLog> page = auditRepo
                .findAll(org.springframework.data.domain.PageRequest.of(0, n,
                        org.springframework.data.domain.Sort.by("id").descending()));
        return page.getContent();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "app", "train");
    }
}
