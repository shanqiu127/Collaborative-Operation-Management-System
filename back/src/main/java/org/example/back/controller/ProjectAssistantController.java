package org.example.back.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.example.back.common.annotation.RequireAdmin;
import org.example.back.common.result.Result;
import org.example.back.dto.ProjectAssistantQueryDTO;
import org.example.back.service.AiConversationService;
import org.example.back.service.ProjectAssistantService;
import org.example.back.service.ProjectKnowledgeBaseService;
import org.example.back.vo.ProjectAssistantAnswerVO;
import org.example.back.vo.ProjectAssistantModelOptionVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/assistant/project")
public class ProjectAssistantController {

    @Autowired
    private ProjectAssistantService assistantService;

    @Autowired
    private ProjectKnowledgeBaseService knowledgeBaseService;

    @Autowired
    private AiConversationService aiConversationService;

    @Autowired
    private ObjectMapper objectMapper;

    private Long getCurrentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    /**
     * 提问接口
     */
    @PostMapping("/query")
    public Result<ProjectAssistantAnswerVO> query(@Valid @RequestBody ProjectAssistantQueryDTO dto) {
        ProjectAssistantAnswerVO answer = assistantService.query(dto.getQuestion(), dto.getMode(), dto.getModelCode(), dto.getConversationId());
        if (dto.getConversationId() != null) {
            aiConversationService.saveMessagePair(
                    dto.getConversationId(),
                    getCurrentUserId(),
                    dto.getQuestion(),
                    answer.getAnswer(),
                    serializeSources(answer),
                    answer.getHitType(),
                    answer.getProviderCode(),
                    answer.getModelCode(),
                    answer.isFallbackUsed()
            );
        }
        return Result.success(answer);
    }

    @GetMapping("/models")
    public Result<List<ProjectAssistantModelOptionVO>> models() {
        return Result.success(assistantService.listAvailableModels());
    }

    private String serializeSources(ProjectAssistantAnswerVO answer) {
        if (answer == null || answer.getSources() == null || answer.getSources().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(answer.getSources());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("来源文档序列化失败", e);
        }
    }

    /**
     * 推荐问题接口
     */
    @GetMapping("/suggestions")
    public Result<List<String>> suggestions() {
        List<String> suggestions = assistantService.getSuggestions();
        return Result.success(suggestions);
    }

    /**
     * 重建知识库接口（仅 superadmin）
     */
    @PostMapping("/rebuild")
    @RequireAdmin("仅超级管理员可重建知识库")
    public Result<Map<String, Object>> rebuild() {
        Map<String, Object> status = knowledgeBaseService.rebuild();
        assistantService.buildSuggestions();
        return Result.success("知识库重建完成", status);
    }

    /**
     * 知识库状态接口
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> status = knowledgeBaseService.getStatus();
        return Result.success(status);
    }
}
