package com.xilidou.jooj.web;

import com.xilidou.jooj.session.Session;
import com.xilidou.jooj.session.SessionService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Sessions REST endpoint —— 让前端管理多对话。
 *
 * <h3>5 个 endpoint</h3>
 *
 * <ul>
 *   <li>{@code POST   /api/sessions}            create(body: {title?})</li>
 *   <li>{@code GET    /api/sessions}            list 全部 session</li>
 *   <li>{@code GET    /api/sessions/{id}}       get 单个</li>
 *   <li>{@code PATCH  /api/sessions/{id}}       update title</li>
 *   <li>{@code DELETE /api/sessions/{id}}       delete(reserved 不允许)</li>
 * </ul>
 *
 * <h3>设计</h3>
 *
 * <p>不抢 agentLock —— 这些是只读 / 元数据操作,可以跟 chat 端点并发。
 * 内部由 {@link SessionService} 自己用 {@code indexLock} 串行 CRUD。
 */
@RestController
@RequestMapping("/api/sessions")
@Slf4j
public class SessionController {

    private final SessionService service;

    public SessionController(SessionService service) {
        this.service = service;
    }

    /**
     * 创建新 session。返回创建好的 {@link Session}。
     *
     * @param body 可选 {@code title} 字段
     * @return 201 Created + Session;若超过 50 上限 → 400
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) CreateRequest body) {
        try {
            String title = body != null ? body.getTitle() : null;
            Session s = service.create(title);
            return ResponseEntity.status(201).body(s);
        } catch (IllegalStateException e) {
            // MAX_SESSIONS 超限
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    /** 列全部 session(创建顺序保留)。 */
    @GetMapping
    public List<Session> list() {
        return service.list();
    }

    /** 获取单个 session;不存在 → 404。 */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        try {
            return ResponseEntity.ok(service.get(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    /** 改 title。404 / 400。 */
    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id,
                                    @RequestBody UpdateRequest body) {
        if (body == null || body.getTitle() == null || body.getTitle().isBlank()) {
            return ResponseEntity.badRequest().body(error("title must not be blank"));
        }
        try {
            return ResponseEntity.ok(service.updateTitle(id, body.getTitle()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    /** 删 session。reserved → 400,不存在 → 404,成功 → 204。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    private static Map<String, String> error(String msg) {
        return Map.of("error", msg);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private String title;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String title;
    }
}
