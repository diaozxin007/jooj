package com.xilidou.jooj.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.JoojTestConfig;
import com.xilidou.jooj.session.Session;
import com.xilidou.jooj.session.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 锁定 {@link SessionController} 5 个 endpoint 的契约。
 *
 * <p>用 {@link MockMvc} 走 HTTP 栈;不需要 mock LLM,session CRUD 不调用 agent。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(JoojTestConfig.class)
@ActiveProfiles({"test", "web"})
class SessionControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SessionService sessionService;

    @BeforeEach
    void cleanUp() {
        // 清掉之前测试创建的非 reserved session(避免触碰 50 上限)
        for (Session s : sessionService.list()) {
            if (!Session.isReserved(s.id())) {
                try { sessionService.delete(s.id()); } catch (Exception ignored) {}
            }
        }
    }

    @Test
    @DisplayName("POST /api/sessions 创建 + 返 201 + Session JSON")
    void create_returns_201() throws Exception {
        mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "项目 A"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.title").value("项目 A"));
    }

    @Test
    @DisplayName("POST /api/sessions 不传 body 也行,自动生成 title")
    void create_without_body_works() throws Exception {
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    @DisplayName("GET /api/sessions 列表至少包含三个 reserved")
    void list_includes_reserved() throws Exception {
        mvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == 'default')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'cli-default')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'cron-default')]").exists());
    }

    @Test
    @DisplayName("GET /api/sessions/{id} 不存在 → 404")
    void get_unknown_returns_404() throws Exception {
        mvc.perform(get("/api/sessions/non-existent-xyz"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /api/sessions/{id} 改 title")
    void patch_updates_title() throws Exception {
        Session s = sessionService.create("old");

        mvc.perform(patch("/api/sessions/" + s.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "new"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("new"));
    }

    @Test
    @DisplayName("PATCH 空 title → 400")
    void patch_empty_title_400() throws Exception {
        Session s = sessionService.create("ok");

        mvc.perform(patch("/api/sessions/" + s.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", ""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /api/sessions/{id} 用户创建的 session 返 204")
    void delete_user_session_returns_204() throws Exception {
        Session s = sessionService.create("to-delete");

        mvc.perform(delete("/api/sessions/" + s.id()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE reserved session → 400")
    void delete_reserved_returns_400() throws Exception {
        mvc.perform(delete("/api/sessions/" + Session.DEFAULT_ID))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/sessions/" + Session.CLI_DEFAULT_ID))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/sessions/" + Session.CRON_DEFAULT_ID))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE 不存在 session → 404")
    void delete_unknown_returns_404() throws Exception {
        mvc.perform(delete("/api/sessions/non-existent-xyz"))
                .andExpect(status().isNotFound());
    }
}
