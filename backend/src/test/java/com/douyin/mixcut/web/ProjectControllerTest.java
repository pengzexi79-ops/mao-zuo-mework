package com.douyin.mixcut.web;

import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.repository.Repositories.ProjectRepo;
import com.douyin.mixcut.service.AiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock private ProjectRepo repo;
    @Mock private AiService aiService;

    private ProjectController controller;
    private final ObjectMapper realMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        controller = new ProjectController(repo, aiService);
    }

    // ---- Draft endpoint ----

    @Test
    void draftReturnsFallbackWhenAiNotReady() {
        ProjectController.DraftReq req = new ProjectController.DraftReq();
        req.setRequirement("测试需求");

        when(aiService.ready()).thenReturn(false);

        R<ProjectController.DraftResp> result = controller.draft(req);

        assertTrue(result.isOk());
        assertNotNull(result.getData());
        assertFalse(result.getData().isAiGenerated());
        assertEquals("测试需求", result.getData().getName());
        assertEquals("美妆", result.getData().getCategory());
        assertEquals("真诚测评", result.getData().getTone());
        assertNotNull(result.getData().getDefaultParams());
    }

    @Test
    void draftReturnsFallbackWhenRequirementIsEmpty() {
        ProjectController.DraftReq req = new ProjectController.DraftReq();
        req.setRequirement("");

        when(aiService.ready()).thenReturn(false);

        R<ProjectController.DraftResp> result = controller.draft(req);

        assertTrue(result.isOk());
        assertNotNull(result.getData());
        assertEquals("我的投放项目", result.getData().getName());
    }

    @Test
    void draftReturnsFallbackWhenReqIsNull() {
        when(aiService.ready()).thenReturn(false);

        R<ProjectController.DraftResp> result = controller.draft(null);

        assertTrue(result.isOk());
        assertNotNull(result.getData());
        assertEquals("我的投放项目", result.getData().getName());
    }

    @Test
    void draftReturnsAiResultWhenAvailable() throws Exception {
        ProjectController.DraftReq req = new ProjectController.DraftReq();
        req.setRequirement("花梨记精华液8月投放");

        String aiJson = """
                {
                  "name":"花梨记精华8月计划",
                  "brand":"花梨记",
                  "category":"护肤",
                  "product":"小棕瓶精华液",
                  "sellingPoints":"3秒吸收不黏腻\\n成分透明可溯源\\n28天回购率62%",
                  "audience":"25-35岁敏感肌上班族",
                  "tone":"真诚测评",
                  "bannedWords":"最,第一,根治",
                  "extraPrompt":"结尾统一引导主页领券",
                  "defaultParams":{"minSec":50,"maxSec":150,"dense":true,"sliceSec":3,"productSlots":3,"celebrityRatio":0.25,"width":1080,"height":1920}
                }
                """;

        when(aiService.ready()).thenReturn(true);
        when(aiService.ask(any(), any(), any(), anyDouble(), anyInt(), isNull()))
                .thenReturn(new AiService.Answer(true, aiJson, null, "test-provider", "test-model"));
        when(aiService.parseJsonLoose(aiJson)).thenReturn(realMapper.readTree(aiJson));

        R<ProjectController.DraftResp> result = controller.draft(req);

        assertTrue(result.isOk());
        assertNotNull(result.getData());
        assertTrue(result.getData().isAiGenerated());
        assertEquals("花梨记精华8月计划", result.getData().getName());
        assertEquals("花梨记", result.getData().getBrand());
        assertEquals("护肤", result.getData().getCategory());
        assertEquals("小棕瓶精华液", result.getData().getProduct());
        assertEquals("真诚测评", result.getData().getTone());
        assertTrue(result.getData().getSellingPoints().contains("3秒吸收"));
    }

    @Test
    void draftSanitizesUnknownCategory() throws Exception {
        ProjectController.DraftReq req = new ProjectController.DraftReq();
        req.setRequirement("测试");

        String aiJson = """
                {
                  "name":"测试项目",
                  "brand":"某品牌",
                  "category":"汽车",
                  "product":"产品",
                  "sellingPoints":"卖点",
                  "audience":"人群",
                  "tone":"搞笑玩梗",
                  "bannedWords":"",
                  "extraPrompt":"",
                  "defaultParams":{}
                }
                """;

        when(aiService.ready()).thenReturn(true);
        when(aiService.ask(any(), any(), any(), anyDouble(), anyInt(), isNull()))
                .thenReturn(new AiService.Answer(true, aiJson, null, "test", "test"));
        when(aiService.parseJsonLoose(aiJson)).thenReturn(realMapper.readTree(aiJson));

        R<ProjectController.DraftResp> result = controller.draft(req);

        assertTrue(result.isOk());
        // Unknown category should fall back to default
        assertEquals("美妆", result.getData().getCategory());
    }

    @Test
    void draftFallsBackOnInvalidAiResponse() {
        ProjectController.DraftReq req = new ProjectController.DraftReq();
        req.setRequirement("测试");

        when(aiService.ready()).thenReturn(true);
        when(aiService.ask(any(), any(), any(), anyDouble(), anyInt(), isNull()))
                .thenReturn(new AiService.Answer(false, null, "API error", null, null));

        R<ProjectController.DraftResp> result = controller.draft(req);

        assertTrue(result.isOk());
        assertNotNull(result.getData());
        assertFalse(result.getData().isAiGenerated());
        assertEquals("测试", result.getData().getName());
    }

    @Test
    void draftRequirementTruncatedTo1000Chars() {
        ProjectController.DraftReq req = new ProjectController.DraftReq();
        req.setRequirement("A".repeat(2000));

        when(aiService.ready()).thenReturn(false);

        R<ProjectController.DraftResp> result = controller.draft(req);

        assertTrue(result.isOk());
        assertNotNull(result.getData());
        assertEquals(50, result.getData().getName().length()); // truncated to max 50
        assertTrue(result.getData().getName().startsWith("A"));
    }

    // ---- CRUD endpoints ----

    @Test
    void listReturnsAllProjects() {
        Project p1 = new Project();
        p1.setId(1L);
        p1.setName("项目1");
        Project p2 = new Project();
        p2.setId(2L);
        p2.setName("项目2");

        when(repo.findAll()).thenReturn(List.of(p1, p2));

        R<List<Project>> result = controller.list();

        assertTrue(result.isOk());
        assertEquals(2, result.getData().size());
    }

    @Test
    void getReturnsProjectWhenFound() {
        Project p = new Project();
        p.setId(1L);
        p.setName("测试项目");

        when(repo.findById(1L)).thenReturn(Optional.of(p));

        R<Project> result = controller.get(1L);

        assertTrue(result.isOk());
        assertEquals("测试项目", result.getData().getName());
    }

    @Test
    void getReturnsFailWhenNotFound() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        R<Project> result = controller.get(99L);

        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("不存在"));
    }

    @Test
    void createFailsWhenNameIsBlank() {
        Project p = new Project();
        p.setName("");

        R<Project> result = controller.create(p);

        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("项目名"));
    }

    @Test
    void createSucceedsWithValidProject() {
        Project p = new Project();
        p.setName("新项目");
        p.setBrand("品牌");

        Project saved = new Project();
        saved.setId(1L);
        saved.setName("新项目");
        saved.setBrand("品牌");

        when(repo.save(any(Project.class))).thenReturn(saved);

        R<Project> result = controller.create(p);

        assertTrue(result.isOk());
        assertEquals(1L, result.getData().getId());
        assertEquals("新项目", result.getData().getName());
    }

    @Test
    void updateDoesNotOverwriteExistingFields() {
        Project existing = new Project();
        existing.setId(1L);
        existing.setName("原名称");
        existing.setBrand("原品牌");
        existing.setCategory("护肤");
        existing.setDefaultParams("{\"minSec\":30}");

        Project input = new Project();
        input.setName("新名称");

        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any(Project.class))).thenReturn(existing);

        R<Project> result = controller.update(1L, input);

        assertTrue(result.isOk());
        assertEquals("新名称", result.getData().getName());
        assertEquals("原品牌", result.getData().getBrand()); // unchanged
        assertEquals("护肤", result.getData().getCategory()); // unchanged
        assertEquals("{\"minSec\":30}", result.getData().getDefaultParams()); // untouched
    }

    @Test
    void updateReturnsFailWhenProjectNotFound() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        R<Project> result = controller.update(99L, new Project());

        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("不存在"));
    }

    @Test
    void deleteCallsRepo() {
        controller.delete(1L);
        verify(repo).deleteById(1L);
    }

    @Test
    void duplicateCopiesTemplateAsEditableProject() {
        Project source = new Project();
        source.setId(1L);
        source.setName("美妆测评模板");
        source.setBrand("Mework 美妆");
        source.setIsBuiltin(true);
        source.setDefaultParams("{\"minSec\":50}");
        when(repo.findById(1L)).thenReturn(Optional.of(source));
        when(repo.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        R<Project> result = controller.duplicate(1L);

        assertTrue(result.isOk());
        assertEquals("美妆测评模板 副本", result.getData().getName());
        assertFalse(result.getData().getIsBuiltin());
        assertEquals(source.getDefaultParams(), result.getData().getDefaultParams());
    }

    @Test
    void builtinTemplateCannotBeUpdatedOrDeleted() {
        Project source = new Project();
        source.setId(1L);
        source.setName("美妆测评模板");
        source.setIsBuiltin(true);
        when(repo.findById(1L)).thenReturn(Optional.of(source));

        assertFalse(controller.update(1L, new Project()).isOk());
        assertFalse(controller.delete(1L).isOk());
        verify(repo, never()).save(any(Project.class));
        verify(repo, never()).deleteById(1L);
    }
}
