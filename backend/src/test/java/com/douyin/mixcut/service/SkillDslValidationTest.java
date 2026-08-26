package com.douyin.mixcut.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure DSL schema tests: no Spring context, database, process or network is started. */
class SkillDslValidationTest {

    private final SkillEngine engine = new SkillEngine(null, null, null, null, null, null, null, null);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalizesAllowedDslToConstrainedShape() throws Exception {
        String result = engine.validateCustomSkillDef("""
                {
                  "version": 1,
                  "steps": [
                    {"op":"select_materials","roles":["body","product","body"],"keyword":"  serum  ","limit":20},
                    {"op":"set_params","params":{"minSec":50,"maxSec":90,"sliceSec":3}},
                    {"op":"note","text":"  审核后渲染  "}
                  ]
                }
                """, "script");

        JsonNode root = mapper.readTree(result);
        assertEquals(1, root.path("version").asInt());
        assertEquals(3, root.path("steps").size());
        assertEquals(2, root.path("steps").get(0).path("roles").size());
        assertEquals("serum", root.path("steps").get(0).path("keyword").asText());
        assertEquals("审核后渲染", root.path("steps").get(2).path("text").asText());
    }

    @Test
    void permitsConstrainedHookAudioFields() throws Exception {
        String result = engine.validateCustomSkillDef("""
                {
                  "version": 1,
                  "steps": [
                    {"op":"pick_audio","hookAudioMaterialId":23,"autoMatchAudio":false,"hookAudioVolume":0.8},
                    {"op":"set_params","params":{"hookAudioMaterialId":23,"autoMatchAudio":true,"hookAudioVolume":1}}
                  ]
                }
                """, "script");

        JsonNode root = mapper.readTree(result);
        assertEquals(23, root.path("steps").get(0).path("hookAudioMaterialId").asInt());
        assertEquals(0.8, root.path("steps").get(0).path("hookAudioVolume").asDouble(), 0.001);
        assertTrue(root.path("steps").get(1).path("params").path("autoMatchAudio").asBoolean());
    }

    @Test
    void rejectsExecutableAndNetworkDslFields() {
        for (String def : new String[]{
                "{\"version\":1,\"steps\":[{\"op\":\"note\",\"text\":\"x\",\"command\":\"whoami\"}]}",
                "{\"version\":1,\"steps\":[{\"op\":\"set_params\",\"params\":{\"fontFile\":\"C:/secret.ttf\"}}]}",
                "{\"version\":1,\"steps\":[{\"op\":\"fetch_web_video\",\"url\":\"https://public.example/a.mp4\"}]}",
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> engine.validateCustomSkillDef(def, "script"));
            assertTrue(error.getMessage() != null && !error.getMessage().isBlank(),
                    "Rejected DSL must return an operator-facing validation reason");
        }
    }

    @Test
    void normalizesLegacyWorkflowSliceKeysBeforeExecution() throws Exception {
        String result = engine.validateWorkflowDefinition("""
                {"steps":[
                  {"skill":"set_slice","args":{"sliceSec":2.5,"sliceJitter":0.4,"explodeLongClips":true,"maxSlicesPerMaterial":4}},
                  {"skill":"set_duration","args":{"minSec":50,"maxSec":80,"dense":true}}
                ]}
                """);

        JsonNode root = mapper.readTree(result);
        JsonNode args = root.path("steps").get(0).path("args");
        assertEquals(2.5, args.path("sliceSec").asDouble(), 0.001);
        assertEquals(0.4, args.path("jitter").asDouble(), 0.001);
        assertTrue(args.path("explode").asBoolean());
        assertEquals(4, args.path("maxPerMaterial").asInt());
    }

    @Test
    void rejectsUnknownWorkflowSkillAndIgnoredArguments() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> engine.validateWorkflowDefinition("{\"steps\":[{\"skill\":\"invent_tool\",\"args\":{}}]}"));
        assertTrue(unknown.getMessage() != null && !unknown.getMessage().isBlank());

        IllegalArgumentException ignored = assertThrows(IllegalArgumentException.class,
                () -> engine.validateWorkflowDefinition("{\"steps\":[{\"skill\":\"set_duration\",\"args\":{\"minSec\":50,\"maxSec\":80,\"silentNoop\":true}}]}"));
        assertTrue(ignored.getMessage().contains("不允许字段"));
    }

    @Test
    void validatesDefaultWorkflowWithTheSameRules() throws Exception {
        String result = engine.validateWorkflowDefinition(engine.defaultWorkflowDef());
        JsonNode root = mapper.readTree(result);
        assertTrue(root.path("steps").size() >= 6);
        assertEquals("select_materials", root.path("steps").get(0).path("skill").asText());
    }
}
