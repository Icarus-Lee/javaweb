package com.javaweb.chat;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

// 聊天室（demo）：服务端主动"推"消息给浏览器（SSE）。
// 关键教学点：服务器也能主动说话——HTTP 不只一问一答。
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final List<Map<String, String>> history = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<SseEmitter> viewers = new CopyOnWriteArrayList<>();   // 所有在线"观众席"

    // 浏览器连上 /stream 后就一直挂着（长连接），服务端见到新消息就逐个广播。
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter em = new SseEmitter(0L);              // 0 = 永不超时
        em.onCompletion(() -> remove(em));
        em.onTimeout(() -> remove(em));
        try {
            em.send(SseEmitter.event().name("history").data(history));   // 先补历史
        } catch (IOException ignored) {}
        viewers.add(em);
        return em;
    }

    private void remove(SseEmitter em) { viewers.remove(em); }

    @PostMapping("/send")
    public Map<String, String> send(@RequestBody Map<String, String> body) {
        String user = body.getOrDefault("user", "匿名");
        String text = body.getOrDefault("text", "");
        Map<String, String> msg = Map.of("user", user, "text", text);
        history.add(msg);
        broadcast(msg);
        return msg;
    }

    private void broadcast(Map<String, String> msg) {
        for (SseEmitter em : viewers) {
            try {
                em.send(SseEmitter.event().name("msg").data(msg));
            } catch (IOException e) {
                viewers.remove(em);   // 断线的观众席撤掉
            }
        }
    }
}
