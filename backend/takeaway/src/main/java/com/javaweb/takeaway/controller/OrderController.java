package com.javaweb.takeaway.controller;

import com.javaweb.takeaway.model.Order;
import com.javaweb.takeaway.repo.OrderRepo;
import com.javaweb.takeaway.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OrderController {
    private final OrderService service;
    private final OrderRepo repo;

    public OrderController(OrderService service, OrderRepo repo) {
        this.service = service;
        this.repo = repo;
    }

    public record CreateReq(Long shopId, Long dishId, Integer quantity) {}

    /** 下单（需登录）。CREATED 状态可取消，PAID 之后走 ML 退款接口走REFUNDED流程。 */
    @PostMapping("/orders")
    public Order create(@RequestBody OrderService.BookReq req, jakarta.servlet.http.HttpServletRequest http) {
        return service.create((Long) http.getAttribute("uid"), req);
    }

    @PostMapping("/orders/{orderNo}/pay")
    public Order pay(@PathVariable String orderNo, jakarta.servlet.http.HttpServletRequest http) {
        return service.pay((Long) http.getAttribute("uid"), orderNo);
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public Order cancel(@PathVariable String orderNo, jakarta.servlet.http.HttpServletRequest http) {
        Order o = service.find(orderNo);
        if (!o.userId.equals((Long) http.getAttribute("uid")))
            throw new IllegalStateException("不是你的订单");
        service.transition(o, "CANCELED");
        return repo.save(o);
    }

    @GetMapping("/orders/mine")
    public List<Order> mine(jakarta.servlet.http.HttpServletRequest http) {
        return service.mine((Long) http.getAttribute("uid"));
    }

    @GetMapping("/orders/{orderNo}")
    public Order get(@PathVariable String orderNo, jakarta.servlet.http.HttpServletRequest http) {
        Order o = service.find(orderNo);
        Long uid = (Long) http.getAttribute("uid");
        // 骑手也能看单
        if (!o.userId.equals(uid) && !"RIDER".equals(http.getAttribute("role")))
            throw new IllegalStateException("无权查看");
        return o;
    }

    /** 骑手确认送达（RL rider role 拦截在方法内实现，教学便于看见 role 校验长啥样）。 */
    @PostMapping("/rider/{orderNo}/deliver")
    public Order deliver(@PathVariable String orderNo, jakarta.servlet.http.HttpServletRequest http) {
        if (!"RIDER".equals(http.getAttribute("role")))
            throw new IllegalStateException("需要骑手身份");
        Order o = service.find(orderNo);
        service.transition(o, "DELIVERED");
        o.deliveredAt = Instant.now();
        return repo.save(o);
    }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("ok", true, "app", "takeaway"); }
}
